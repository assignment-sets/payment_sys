package com.example.payment_system.cache;

import com.example.payment_system.dto.AccountResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Versioned Redis Hash cache for {@link AccountResponse}.
 *
 * Uses atomic Lua scripts keyed on the entity's monotonic {@code version}
 * (optimistic locking) plus tombstones on invalidation so a slow read can never
 * overwrite a newer DB state.
 */
@Slf4j
@Service
public class AccountCacheService {

    private static final String ACCOUNT_KEY_PREFIX = "cache:account:";
    private static final String USER_INDEX_PREFIX = "cache:account:user:";
    private static final String FIELD_VERSION = "version";
    private static final String FIELD_PAYLOAD = "payload";

    private static final DefaultRedisScript<Long> SET_IF_NEWER_SCRIPT = new DefaultRedisScript<>("""
            local cur = redis.call('HGET', KEYS[1], 'version')
            local hasPayload = redis.call('HEXISTS', KEYS[1], 'payload')
            if (not cur) or (tonumber(ARGV[1]) > tonumber(cur)) or (tonumber(ARGV[1]) == tonumber(cur) and hasPayload == 0) then
                redis.call('HSET', KEYS[1], 'version', ARGV[1], 'payload', ARGV[2])
                redis.call('EXPIRE', KEYS[1], tonumber(ARGV[3]))
                return 1
            end
            return 0
            """, Long.class);

    private static final DefaultRedisScript<Long> INVALIDATE_IF_OLDER_SCRIPT = new DefaultRedisScript<>("""
            local cur = redis.call('HGET', KEYS[1], 'version')
            if (not cur) or (tonumber(ARGV[1]) >= tonumber(cur)) then
                redis.call('HDEL', KEYS[1], 'payload')
                redis.call('HSET', KEYS[1], 'version', ARGV[1])
                redis.call('EXPIRE', KEYS[1], tonumber(ARGV[2]))
                return 1
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final long ttlSeconds;
    private final boolean enabled;

    public AccountCacheService(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            long ttlSeconds) {
        this(redisTemplate, objectMapper, ttlSeconds, true);
    }

    @Autowired
    public AccountCacheService(
            @Nullable @Qualifier("cacheStringRedisTemplate") StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            @Value("${cache.account.ttl-seconds:300}") long ttlSeconds,
            @Value("${cache.enabled:true}") boolean enabled) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.ttlSeconds = ttlSeconds;
        this.enabled = enabled && redisTemplate != null;
    }

    public Optional<AccountResponse> getByAccountId(Long accountId) {
        if (!enabled) {
            return Optional.empty();
        }
        try {
            Object rawPayload = redisTemplate.opsForHash().get(accountKey(accountId), FIELD_PAYLOAD);
            if (rawPayload == null) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(rawPayload.toString(), AccountResponse.class));
        } catch (Exception e) {
            log.warn("Failed reading account cache for accountId={}, falling back to DB: {}", accountId, e.getMessage());
            return Optional.empty();
        }
    }

    public Optional<AccountResponse> getByUserId(Long userId) {
        if (!enabled) {
            return Optional.empty();
        }
        try {
            String accountIdStr = redisTemplate.opsForValue().get(userIndexKey(userId));
            if (accountIdStr == null) {
                return Optional.empty();
            }
            return getByAccountId(Long.parseLong(accountIdStr));
        } catch (Exception e) {
            log.warn("Failed reading user->account index for userId={}, falling back to DB: {}", userId, e.getMessage());
            return Optional.empty();
        }
    }

    public boolean putIfNewer(Long accountId, Long userId, long version, AccountResponse dto) {
        if (!enabled) {
            return false;
        }
        try {
            String json = objectMapper.writeValueAsString(dto);
            Long result = redisTemplate.execute(
                    SET_IF_NEWER_SCRIPT,
                    List.of(accountKey(accountId)),
                    String.valueOf(version),
                    json,
                    String.valueOf(ttlSeconds)
            );
            redisTemplate.opsForValue().set(userIndexKey(userId), String.valueOf(accountId), Duration.ofSeconds(ttlSeconds));
            boolean stored = result != null && result == 1L;
            if (!stored) {
                log.debug("Rejected stale cache write for accountId={} (version={})", accountId, version);
            }
            return stored;
        } catch (JsonProcessingException e) {
            log.warn("Failed serializing AccountResponse for accountId={}: {}", accountId, e.getMessage());
            return false;
        } catch (Exception e) {
            log.warn("Failed writing account cache for accountId={}: {}", accountId, e.getMessage());
            return false;
        }
    }

    public boolean invalidate(long accountId, long version) {
        if (!enabled) {
            return false;
        }
        try {
            Long result = redisTemplate.execute(
                    INVALIDATE_IF_OLDER_SCRIPT,
                    List.of(accountKey(accountId)),
                    String.valueOf(version),
                    String.valueOf(ttlSeconds)
            );
            boolean invalidated = result != null && result == 1L;
            log.debug("Invalidated account cache accountId={} version={} applied={}", accountId, version, invalidated);
            return invalidated;
        } catch (Exception e) {
            log.warn("Failed invalidating account cache for accountId={} version={}: {}", accountId, version, e.getMessage());
            return false;
        }
    }

    private String accountKey(Long accountId) {
        return ACCOUNT_KEY_PREFIX + accountId;
    }

    private String userIndexKey(Long userId) {
        return USER_INDEX_PREFIX + userId;
    }
}
