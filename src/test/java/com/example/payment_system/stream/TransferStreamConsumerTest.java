package com.example.payment_system.stream;

import com.example.payment_system.entity.TransferFailureCategory;
import com.example.payment_system.exception.InsufficientBalanceException;
import com.example.payment_system.service.TransferRecoveryService;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Map;
import java.util.UUID;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TransferStreamConsumerTest {

    @Mock
    private TransferExecutionDelegate executionDelegate;

    @Mock
    private TransferRecoveryService transferRecoveryService;

    @Mock
    private TransferStreamProducer streamProducer;

    @Mock
    private StringRedisTemplate queueRedisTemplate;

    @Mock
    private StreamOperations<String, Object, Object> streamOperations;

    private TransferStreamProperties properties;
    private TransferStreamConsumer consumer;

    @BeforeEach
    void setUp() {
        properties = new TransferStreamProperties();
        properties.setKey("transfers:pending");
        properties.setDlqKey("transfers:dlq");
        properties.setGroup("transfer-workers");

        lenient().when(queueRedisTemplate.opsForStream()).thenReturn(streamOperations);

        consumer = new TransferStreamConsumer(
                executionDelegate,
                transferRecoveryService,
                streamProducer,
                queueRedisTemplate,
                properties
        );
    }

    private MapRecord<String, String, String> recordFor(UUID txId) {
        return StreamRecords.newRecord()
                .in("transfers:pending")
                .ofMap(Map.of("transactionId", txId.toString()))
                .withId(RecordId.of("1700000000000-0"));
    }

    @Test
    void onMessage_happyPath_executesAndAcknowledges() {
        UUID txId = UUID.randomUUID();
        MapRecord<String, String, String> record = recordFor(txId);

        consumer.onMessage(record);

        verify(executionDelegate).execute(txId);
        verify(streamOperations).acknowledge("transfers:pending", "transfer-workers", record.getId());
        verifyNoInteractions(streamProducer);
    }

    @Test
    void onMessage_businessPaymentException_marksFailedPublishesToDlqAndAcknowledges() {
        UUID txId = UUID.randomUUID();
        MapRecord<String, String, String> record = recordFor(txId);
        doThrow(new InsufficientBalanceException(1L)).when(executionDelegate).execute(txId);

        consumer.onMessage(record);

        verify(transferRecoveryService).markFailed(txId, TransferFailureCategory.BUSINESS, 1);
        verify(streamProducer).publishToDlq(eq(txId.toString()), eq("BUSINESS"), anyString());
        verify(streamOperations).acknowledge("transfers:pending", "transfer-workers", record.getId());
    }

    @Test
    void onMessage_optimisticLockingExhausted_publishesToDlqAndAcknowledges() {
        UUID txId = UUID.randomUUID();
        MapRecord<String, String, String> record = recordFor(txId);
        doThrow(new OptimisticLockingFailureException("exhausted")).when(executionDelegate).execute(txId);

        consumer.onMessage(record);

        verify(streamProducer).publishToDlq(eq(txId.toString()), eq("TRANSIENT_EXHAUSTED"), anyString());
        verify(streamOperations).acknowledge("transfers:pending", "transfer-workers", record.getId());
    }

    @Test
    void onMessage_circuitBreakerOpen_leavesMessageUnacked() {
        UUID txId = UUID.randomUUID();
        MapRecord<String, String, String> record = recordFor(txId);
        doThrow(mock(CallNotPermittedException.class)).when(executionDelegate).execute(txId);

        consumer.onMessage(record);

        verifyNoInteractions(streamProducer);
        verify(streamOperations, never()).acknowledge(anyString(), anyString(), any(RecordId.class));
    }

    @Test
    void onMessage_bulkheadFull_leavesMessageUnacked() {
        UUID txId = UUID.randomUUID();
        MapRecord<String, String, String> record = recordFor(txId);
        doThrow(mock(BulkheadFullException.class)).when(executionDelegate).execute(txId);

        consumer.onMessage(record);

        verifyNoInteractions(streamProducer);
        verify(streamOperations, never()).acknowledge(anyString(), anyString(), any(RecordId.class));
    }
}
