package com.example.payment_system.stream;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
public class ReportStreamProducer {

    private final StringRedisTemplate queueRedisTemplate;
    private final ReportStreamProperties properties;

    public ReportStreamProducer(
            @Qualifier("queueStringRedisTemplate") StringRedisTemplate queueRedisTemplate,
            ReportStreamProperties properties) {
        this.queueRedisTemplate = queueRedisTemplate;
        this.properties = properties;
    }

    public RecordId publish(UUID reportId) {
        RecordId recordId = queueRedisTemplate.opsForStream().add(
                StreamRecords.string(Map.of("reportId", reportId.toString()))
                        .withStreamKey(properties.getKey())
        );
        log.debug("Published report {} to stream {} (recordId={})",
                reportId, properties.getKey(), recordId);
        return recordId;
    }

    public RecordId publishToDlq(String reportId, String reason, String detail) {
        Map<String, String> payload = Map.of(
                "reportId", reportId != null ? reportId : "unknown",
                "reason", reason,
                "detail", detail != null ? detail : "",
                "failedAt", OffsetDateTime.now().toString()
        );
        RecordId recordId = queueRedisTemplate.opsForStream().add(
                StreamRecords.string(payload).withStreamKey(properties.getDlqKey())
        );
        log.warn("Routed report {} to DLQ {} (reason={}, recordId={})",
                reportId, properties.getDlqKey(), reason, recordId);
        return recordId;
    }
}
