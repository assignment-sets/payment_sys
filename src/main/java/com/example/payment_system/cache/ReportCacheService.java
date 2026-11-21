package com.example.payment_system.cache;

import com.example.payment_system.dto.AccountReportResponse;
import com.example.payment_system.entity.ReportStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * Plain-TTL Redis cache on {@code payment-redis} (6379) for immutable report
 * snapshots only:
 * <ul>
 *     <li>Past frozen windows keyed by {@code (accountId, fromDate, toDate)}</li>
 *     <li>Terminal ({@code COMPLETED} / {@code FAILED}) reports keyed by {@code reportId}</li>
 * </ul>
 * Current-period {@code POST} requests and {@code PENDING}/{@code PROCESSING}
 * jobs are never cached. All operations fail open to PostgreSQL if Redis is unavailable.
 */
@Slf4j
@Service
public class ReportCacheService {

    private static final String PAST_KEY_PREFIX = "cache:report:past:";
    private static final String ID_KEY_PREFIX = "cache:report:id:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final long pastTtlSeconds;
    private final long completedTtlSeconds;
    private final boolean enabled;

    public ReportCacheService(
            StringRedisTemplate redisTemplate,
            long pastTtlSeconds,
            long completedTtlSeconds) {
        this(redisTemplate, pastTtlSeconds, completedTtlSeconds, true);
    }

    @Autowired
    public ReportCacheService(
            @Nullable @Qualifier("cacheStringRedisTemplate") StringRedisTemplate redisTemplate,
            @Value("${cache.report.past-ttl-seconds:3600}") long pastTtlSeconds,
            @Value("${cache.report.completed-ttl-seconds:300}") long completedTtlSeconds,
            @Value("${cache.enabled:true}") boolean enabled) {
        this.redisTemplate = redisTemplate;
        this.pastTtlSeconds = pastTtlSeconds;
        this.completedTtlSeconds = completedTtlSeconds;
        this.enabled = enabled && redisTemplate != null;
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public Optional<AccountReportResponse> getPastReport(Long accountId, LocalDate fromDate, LocalDate toDate) {
        return getByKey(pastKey(accountId, fromDate, toDate));
    }

    public void putPastReport(AccountReportResponse report) {
        if (report == null) {
            return;
        }
        putByKey(pastKey(report.accountId(), report.fromDate(), report.toDate()), report, pastTtlSeconds);
    }

    public Optional<AccountReportResponse> getCompletedReport(UUID reportId) {
        return getByKey(idKey(reportId));
    }

    public void putCompletedReport(AccountReportResponse report) {
        if (report == null || (report.status() != ReportStatus.COMPLETED && report.status() != ReportStatus.FAILED)) {
            return;
        }
        putByKey(idKey(report.reportId()), report, completedTtlSeconds);
    }

    private Optional<AccountReportResponse> getByKey(String key) {
        if (!enabled) {
            return Optional.empty();
        }
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json == null || json.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(json, AccountReportResponse.class));
        } catch (Exception e) {
            log.warn("Failed reading report cache key={}: {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    private void putByKey(String key, AccountReportResponse report, long ttlSeconds) {
        if (!enabled) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(report);
            redisTemplate.opsForValue().set(key, json, Duration.ofSeconds(ttlSeconds));
        } catch (Exception e) {
            log.warn("Failed writing report cache key={}: {}", key, e.getMessage());
        }
    }

    private String pastKey(Long accountId, LocalDate fromDate, LocalDate toDate) {
        return PAST_KEY_PREFIX + accountId + ":" + fromDate + ":" + toDate;
    }

    private String idKey(UUID reportId) {
        return ID_KEY_PREFIX + reportId;
    }
}
