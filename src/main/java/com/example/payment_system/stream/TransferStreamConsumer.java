package com.example.payment_system.stream;

import com.example.payment_system.entity.TransferFailureCategory;
import com.example.payment_system.exception.PaymentException;
import com.example.payment_system.service.TransferRecoveryService;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamListener;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Slf4j
@Component
public class TransferStreamConsumer implements StreamListener<String, MapRecord<String, String, String>> {

    private final TransferExecutionDelegate executionDelegate;
    private final TransferRecoveryService transferRecoveryService;
    private final TransferStreamProducer streamProducer;
    private final StringRedisTemplate queueRedisTemplate;
    private final TransferStreamProperties properties;

    public TransferStreamConsumer(
            TransferExecutionDelegate executionDelegate,
            TransferRecoveryService transferRecoveryService,
            TransferStreamProducer streamProducer,
            @Qualifier("queueStringRedisTemplate") StringRedisTemplate queueRedisTemplate,
            TransferStreamProperties properties) {
        this.executionDelegate = executionDelegate;
        this.transferRecoveryService = transferRecoveryService;
        this.streamProducer = streamProducer;
        this.queueRedisTemplate = queueRedisTemplate;
        this.properties = properties;
    }

    @Override
    public void onMessage(MapRecord<String, String, String> message) {
        String rawId = message.getValue().get("transactionId");
        UUID transactionId;
        try {
            if (rawId == null || rawId.isBlank()) {
                throw new IllegalArgumentException("Missing transactionId in stream record " + message.getId());
            }
            transactionId = UUID.fromString(rawId);
        } catch (IllegalArgumentException e) {
            log.error("Malformed stream record {} (transactionId={}), routing to DLQ", message.getId(), rawId, e);
            streamProducer.publishToDlq(rawId, "MALFORMED_RECORD", e.getMessage());
            acknowledge(message);
            return;
        }

        try {
            executionDelegate.execute(transactionId);
            acknowledge(message);
        } catch (PaymentException e) {
            log.warn("Transfer {} failed with business error: {}", transactionId, e.getMessage());
            transferRecoveryService.markFailed(transactionId, TransferFailureCategory.BUSINESS, 1);
            streamProducer.publishToDlq(transactionId.toString(), "BUSINESS", e.getMessage());
            acknowledge(message);
        } catch (OptimisticLockingFailureException e) {
            log.warn("Transfer {} exhausted optimistic lock retries, routing to DLQ: {}", transactionId, e.getMessage());
            streamProducer.publishToDlq(transactionId.toString(), "TRANSIENT_EXHAUSTED", e.getMessage());
            acknowledge(message);
        } catch (CallNotPermittedException | BulkheadFullException e) {
            log.warn("Resilience guard triggered for transfer {} ({}), leaving unacked for redelivery",
                    transactionId, e.getClass().getSimpleName());
        } catch (Exception e) {
            log.error("Unexpected error processing transfer {} (recordId={}), leaving unacked",
                    transactionId, message.getId(), e);
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
