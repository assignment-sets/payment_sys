package com.example.payment_system.cache;

import com.example.payment_system.dto.AccountReportResponse;
import com.example.payment_system.entity.ReportSource;
import com.example.payment_system.entity.ReportStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReportCacheServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private ReportCacheService reportCacheService;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        reportCacheService = new ReportCacheService(redisTemplate, 3600L, 300L);
    }

    @Test
    void putCompletedReport_skipsPendingAndProcessing() {
        AccountReportResponse pending = sample(UUID.randomUUID(), ReportStatus.PENDING, ReportSource.ASYNC_QUEUE);
        AccountReportResponse processing = sample(UUID.randomUUID(), ReportStatus.PROCESSING, ReportSource.ASYNC_QUEUE);

        reportCacheService.putCompletedReport(pending);
        reportCacheService.putCompletedReport(processing);

        verifyNoInteractions(valueOperations);
    }

    @Test
    void putAndGetCompletedReport_roundTripsJson() {
        UUID reportId = UUID.randomUUID();
        AccountReportResponse completed = sample(reportId, ReportStatus.COMPLETED, ReportSource.ASYNC_QUEUE);

        reportCacheService.putCompletedReport(completed);

        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(eq("cache:report:id:" + reportId), captor.capture(), eq(Duration.ofSeconds(300)));

        when(valueOperations.get("cache:report:id:" + reportId)).thenReturn(captor.getValue());
        Optional<AccountReportResponse> loaded = reportCacheService.getCompletedReport(reportId);

        assertThat(loaded).isPresent();
        assertThat(loaded.get().reportId()).isEqualTo(reportId);
        assertThat(loaded.get().closingBalance()).isEqualTo(1200L);
    }

    @Test
    void putAndGetPastReport_usesPastKeyAndTtl() {
        UUID reportId = UUID.randomUUID();
        AccountReportResponse past = sample(reportId, ReportStatus.COMPLETED, ReportSource.FROZEN_SNAPSHOT);

        reportCacheService.putPastReport(past);

        verify(valueOperations).set(
                eq("cache:report:past:10:2026-10-01:2026-10-31"),
                any(String.class),
                eq(Duration.ofSeconds(3600))
        );
    }

    @Test
    void getPastReport_whenRedisThrows_failsOpenToEmpty() {
        when(valueOperations.get(any())).thenThrow(new RuntimeException("Redis down"));

        Optional<AccountReportResponse> result = reportCacheService.getPastReport(
                10L, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));

        assertThat(result).isEmpty();
    }

    @Test
    void whenDisabled_allOperationsShortCircuitWithoutTouchingRedis() {
        ReportCacheService disabledService = new ReportCacheService(redisTemplate, 3600L, 300L, false);
        UUID reportId = UUID.randomUUID();
        AccountReportResponse completed = sample(reportId, ReportStatus.COMPLETED, ReportSource.ASYNC_QUEUE);

        assertThat(disabledService.getPastReport(10L, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)))
                .isEmpty();
        assertThat(disabledService.getCompletedReport(reportId)).isEmpty();
        disabledService.putPastReport(completed);
        disabledService.putCompletedReport(completed);

        verifyNoInteractions(valueOperations);
    }

    private AccountReportResponse sample(UUID id, ReportStatus status, ReportSource source) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return new AccountReportResponse(
                id,
                10L,
                LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 10, 31),
                status,
                source,
                1000L,
                1200L,
                200L,
                0L,
                0L,
                200L,
                0,
                List.of(),
                null,
                now,
                status == ReportStatus.COMPLETED ? now : null
        );
    }
}
