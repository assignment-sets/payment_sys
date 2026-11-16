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
public class TransferStreamProducer {

    private final StringRedisTemplate queueRedisTemplate;
    private final TransferStreamProperties properties;

    public TransferStreamProducer(
            @Qualifier("queueStringRedisTemplate") StringRedisTemplate queueRedisTemplate,
            TransferStreamProperties properties) {
        this.queueRedisTemplate = queueRedisTemplate;
        this.properties = properties;
    }

    public RecordId publish(UUID transactionId) {
        RecordId recordId = queueRedisTemplate.opsForStream().add(
                StreamRecords.string(Map.of("transactionId", transactionId.toString()))
                        .withStreamKey(properties.getKey())
        );
        log.debug("Published transfer {} to stream {} (recordId={})",
                transactionId, properties.getKey(), recordId);
        return recordId;
    }

    public RecordId publishToDlq(String transactionId, String reason, String detail) {
        Map<String, String> payload = Map.of(
                "transactionId", transactionId != null ? transactionId : "unknown",
                "reason", reason,
                "detail", detail != null ? detail : "",
                "failedAt", OffsetDateTime.now().toString()
        );
        RecordId recordId = queueRedisTemplate.opsForStream().add(
                StreamRecords.string(payload).withStreamKey(properties.getDlqKey())
        );
        log.warn("Routed transfer {} to DLQ {} (reason={}, recordId={})",
                transactionId, properties.getDlqKey(), reason, recordId);
        return recordId;
    }
}
