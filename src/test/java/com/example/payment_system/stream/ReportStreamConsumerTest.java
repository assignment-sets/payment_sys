package com.example.payment_system.stream;

import com.example.payment_system.exception.ReportNotFoundException;
import com.example.payment_system.service.ReportProcessor;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReportStreamConsumerTest {

    @Mock
    private ReportExecutionDelegate executionDelegate;
    @Mock
    private ReportProcessor reportProcessor;
    @Mock
    private ReportStreamProducer streamProducer;
    @Mock
    private StringRedisTemplate queueRedisTemplate;
    @Mock
    private StreamOperations<String, Object, Object> streamOps;

    private ReportStreamProperties properties;
    private ReportStreamConsumer consumer;

    @BeforeEach
    void setUp() {
        properties = new ReportStreamProperties();
        lenient().when(queueRedisTemplate.opsForStream()).thenReturn(streamOps);
        consumer = new ReportStreamConsumer(
                executionDelegate, reportProcessor, streamProducer, queueRedisTemplate, properties);
    }

    @Test
    void onMessage_validReport_executesAndAcks() {
        UUID reportId = UUID.randomUUID();
        MapRecord<String, String, String> record = record("1-0", Map.of("reportId", reportId.toString()));

        consumer.onMessage(record);

        verify(executionDelegate).execute(reportId);
        verify(streamOps).acknowledge("reports:pending", "report-workers", RecordId.of("1-0"));
        verifyNoInteractions(streamProducer);
    }

    @Test
    void onMessage_malformedRecord_routesToDlqAndAcks() {
        MapRecord<String, String, String> record = record("2-0", Map.of("reportId", "not-a-uuid"));

        consumer.onMessage(record);

        verifyNoInteractions(executionDelegate);
        verify(streamProducer).publishToDlq(eq("not-a-uuid"), eq("MALFORMED_RECORD"), any());
        verify(streamOps).acknowledge("reports:pending", "report-workers", RecordId.of("2-0"));
    }

    @Test
    void onMessage_permanentPaymentException_marksFailedDlqAndAcks() {
        UUID reportId = UUID.randomUUID();
        MapRecord<String, String, String> record = record("3-0", Map.of("reportId", reportId.toString()));
        doThrow(new ReportNotFoundException(reportId)).when(executionDelegate).execute(reportId);

        consumer.onMessage(record);

        verify(reportProcessor).markFailed(eq(reportId), any());
        verify(streamProducer).publishToDlq(eq(reportId.toString()), eq("PERMANENT_ERROR"), any());
        verify(streamOps).acknowledge("reports:pending", "report-workers", RecordId.of("3-0"));
    }

    @Test
    void onMessage_bulkheadFull_leavesUnackedForRedelivery() {
        UUID reportId = UUID.randomUUID();
        MapRecord<String, String, String> record = record("4-0", Map.of("reportId", reportId.toString()));
        doThrow(mock(BulkheadFullException.class)).when(executionDelegate).execute(reportId);

        consumer.onMessage(record);

        verifyNoInteractions(streamProducer, reportProcessor);
        verify(streamOps, never()).acknowledge(any(), any(), any(RecordId[].class));
    }

    private MapRecord<String, String, String> record(String id, Map<String, String> body) {
        return StreamRecords.string(body)
                .withStreamKey("reports:pending")
                .withId(RecordId.of(id));
    }
}
