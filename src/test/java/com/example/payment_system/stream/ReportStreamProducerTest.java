package com.example.payment_system.stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StringRecord;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReportStreamProducerTest {

    @Mock
    private StringRedisTemplate queueRedisTemplate;

    @Mock
    private StreamOperations<String, Object, Object> streamOperations;

    private ReportStreamProperties properties;
    private ReportStreamProducer producer;

    @BeforeEach
    void setUp() {
        properties = new ReportStreamProperties();
        properties.setKey("reports:pending");
        properties.setDlqKey("reports:dlq");

        lenient().when(queueRedisTemplate.opsForStream()).thenReturn(streamOperations);

        producer = new ReportStreamProducer(queueRedisTemplate, properties);
    }

    @Test
    void publish_enqueuesRecordWithConfiguredStreamKeyAndReportId() {
        UUID reportId = UUID.randomUUID();
        RecordId expectedId = RecordId.of("1700000000000-0");
        when(streamOperations.add(any(StringRecord.class))).thenReturn(expectedId);

        RecordId actualId = producer.publish(reportId);

        assertThat(actualId).isEqualTo(expectedId);

        ArgumentCaptor<StringRecord> captor = ArgumentCaptor.forClass(StringRecord.class);
        verify(streamOperations).add(captor.capture());

        StringRecord record = captor.getValue();
        assertThat(record.getStream()).isEqualTo("reports:pending");
        assertThat(record.getValue()).containsExactlyEntriesOf(Map.of("reportId", reportId.toString()));
    }

    @Test
    void publish_usesCustomStreamKeyFromProperties() {
        properties.setKey("custom:reports:stream");
        UUID reportId = UUID.randomUUID();
        RecordId expectedId = RecordId.of("1700000000001-0");
        when(streamOperations.add(any(StringRecord.class))).thenReturn(expectedId);

        RecordId actualId = producer.publish(reportId);

        assertThat(actualId).isEqualTo(expectedId);

        ArgumentCaptor<StringRecord> captor = ArgumentCaptor.forClass(StringRecord.class);
        verify(streamOperations).add(captor.capture());
        assertThat(captor.getValue().getStream()).isEqualTo("custom:reports:stream");
    }

    @Test
    void publish_whenRedisReturnsNullRecordId_returnsNull() {
        UUID reportId = UUID.randomUUID();
        when(streamOperations.add(any(StringRecord.class))).thenReturn(null);

        RecordId actualId = producer.publish(reportId);

        assertThat(actualId).isNull();
    }

    @Test
    void publish_whenReportIdIsNull_throwsNullPointerException() {
        assertThatThrownBy(() -> producer.publish(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void publish_whenRedisThrowsException_propagatesException() {
        UUID reportId = UUID.randomUUID();
        when(streamOperations.add(any(StringRecord.class)))
                .thenThrow(new RedisConnectionFailureException("Redis unavailable"));

        assertThatThrownBy(() -> producer.publish(reportId))
                .isInstanceOf(RedisConnectionFailureException.class)
                .hasMessageContaining("Redis unavailable");
    }

    @Test
    void publishToDlq_withAllFields_enqueuesDlqRecordWithPayload() {
        String reportId = UUID.randomUUID().toString();
        RecordId expectedId = RecordId.of("1700000000002-0");
        when(streamOperations.add(any(StringRecord.class))).thenReturn(expectedId);

        RecordId actualId = producer.publishToDlq(reportId, "GENERATION_FAILED", "DB timeout");

        assertThat(actualId).isEqualTo(expectedId);

        ArgumentCaptor<StringRecord> captor = ArgumentCaptor.forClass(StringRecord.class);
        verify(streamOperations).add(captor.capture());

        StringRecord record = captor.getValue();
        assertThat(record.getStream()).isEqualTo("reports:dlq");
        Map<String, String> value = record.getValue();
        assertThat(value)
                .containsEntry("reportId", reportId)
                .containsEntry("reason", "GENERATION_FAILED")
                .containsEntry("detail", "DB timeout")
                .containsKey("failedAt");
        assertThat(OffsetDateTime.parse(value.get("failedAt"))).isNotNull();
    }

    @Test
    void publishToDlq_whenReportIdAndDetailAreNull_fallsBackToDefaults() {
        RecordId expectedId = RecordId.of("1700000000003-0");
        when(streamOperations.add(any(StringRecord.class))).thenReturn(expectedId);

        RecordId actualId = producer.publishToDlq(null, "MALFORMED", null);

        assertThat(actualId).isEqualTo(expectedId);

        ArgumentCaptor<StringRecord> captor = ArgumentCaptor.forClass(StringRecord.class);
        verify(streamOperations).add(captor.capture());

        Map<String, String> value = captor.getValue().getValue();
        assertThat(value)
                .containsEntry("reportId", "unknown")
                .containsEntry("reason", "MALFORMED")
                .containsEntry("detail", "")
                .containsKey("failedAt");
    }

    @Test
    void publishToDlq_whenReasonIsNull_throwsNullPointerException() {
        assertThatThrownBy(() -> producer.publishToDlq("rep-1", null, "detail"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void publishToDlq_whenRedisThrowsException_propagatesException() {
        when(streamOperations.add(any(StringRecord.class)))
                .thenThrow(new RedisConnectionFailureException("DLQ write failed"));

        assertThatThrownBy(() -> producer.publishToDlq("rep-1", "EXHAUSTED", "timeout"))
                .isInstanceOf(RedisConnectionFailureException.class)
                .hasMessageContaining("DLQ write failed");
    }
}
