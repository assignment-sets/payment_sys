package com.example.payment_system.stream;

import com.example.payment_system.exception.PaymentException;
import com.example.payment_system.service.ReportProcessor;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamListener;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Slf4j
@Component
public class ReportStreamConsumer implements StreamListener<String, MapRecord<String, String, String>> {

    private final ReportExecutionDelegate executionDelegate;
    private final ReportProcessor reportProcessor;
    private final ReportStreamProducer streamProducer;
    private final StringRedisTemplate queueRedisTemplate;
    private final ReportStreamProperties properties;

    public ReportStreamConsumer(
            ReportExecutionDelegate executionDelegate,
            ReportProcessor reportProcessor,
            ReportStreamProducer streamProducer,
            @Qualifier("queueStringRedisTemplate") StringRedisTemplate queueRedisTemplate,
            ReportStreamProperties properties) {
        this.executionDelegate = executionDelegate;
        this.reportProcessor = reportProcessor;
        this.streamProducer = streamProducer;
        this.queueRedisTemplate = queueRedisTemplate;
        this.properties = properties;
    }

    @Override
    public void onMessage(MapRecord<String, String, String> message) {
        String rawId = message.getValue().get("reportId");
        UUID reportId;
        try {
            if (rawId == null || rawId.isBlank()) {
                throw new IllegalArgumentException("Missing reportId in stream record " + message.getId());
            }
            reportId = UUID.fromString(rawId);
        } catch (IllegalArgumentException e) {
            log.error("Malformed report stream record {} (reportId={}), routing to DLQ", message.getId(), rawId, e);
            streamProducer.publishToDlq(rawId, "MALFORMED_RECORD", e.getMessage());
            acknowledge(message);
            return;
        }

        try {
            executionDelegate.execute(reportId);
            acknowledge(message);
        } catch (PaymentException e) {
            log.warn("Report {} failed permanently: {}", reportId, e.getMessage());
            reportProcessor.markFailed(reportId, e.getMessage());
            streamProducer.publishToDlq(reportId.toString(), "PERMANENT_ERROR", e.getMessage());
            acknowledge(message);
        } catch (CallNotPermittedException | BulkheadFullException e) {
            log.warn("Resilience guard triggered for report {} ({}), leaving unacked for redelivery",
                    reportId, e.getClass().getSimpleName());
        } catch (Exception e) {
            log.error("Unexpected error processing report {} (recordId={}), leaving unacked",
                    reportId, message.getId(), e);
        }
    }

    private void acknowledge(MapRecord<String, String, String> message) {
        queueRedisTemplate.opsForStream().acknowledge(
                properties.getKey(),
                properties.getGroup(),
                message.getId()
        );
    }
}
