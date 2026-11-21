package com.example.payment_system.cache;

import com.example.payment_system.dto.AccountResponse;
import com.example.payment_system.entity.AccountStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AccountCacheServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private ObjectMapper objectMapper;
    private AccountCacheService cacheService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        lenient().when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        cacheService = new AccountCacheService(redisTemplate, objectMapper, 300L);
    }

    @Test
    void getByAccountId_whenCached_returnsDeserializedDto() throws Exception {
        AccountResponse dto = new AccountResponse(1L, 10L, 500L, AccountStatus.ACTIVE, OffsetDateTime.now());
        when(hashOperations.get("cache:account:1", "payload")).thenReturn(objectMapper.writeValueAsString(dto));

        Optional<AccountResponse> result = cacheService.getByAccountId(1L);

        assertThat(result).isPresent();
        assertThat(result.get().balance()).isEqualTo(500L);
        assertThat(result.get().status()).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    void getByAccountId_whenTombstoneOrMiss_returnsEmpty() {
        when(hashOperations.get("cache:account:1", "payload")).thenReturn(null);

        assertThat(cacheService.getByAccountId(1L)).isEmpty();
    }

    @Test
    void putIfNewer_executesLuaScriptAndReturnsTrueWhenStored() {
        AccountResponse dto = new AccountResponse(1L, 10L, 500L, AccountStatus.ACTIVE, OffsetDateTime.now());
        when(redisTemplate.execute(any(RedisScript.class), eq(List.of("cache:account:1")), eq("5"), anyString(), eq("300")))
                .thenReturn(1L);

        boolean stored = cacheService.putIfNewer(1L, 10L, 5L, dto);

        assertThat(stored).isTrue();
        verify(valueOperations).set(eq("cache:account:user:10"), eq("1"), any());
    }

    @Test
    void putIfNewer_whenRejectedAsStale_returnsFalse() {
        AccountResponse dto = new AccountResponse(1L, 10L, 500L, AccountStatus.ACTIVE, OffsetDateTime.now());
        when(redisTemplate.execute(any(RedisScript.class), eq(List.of("cache:account:1")), eq("4"), anyString(), eq("300")))
                .thenReturn(0L);

        boolean stored = cacheService.putIfNewer(1L, 10L, 4L, dto);

        assertThat(stored).isFalse();
    }

    @Test
    void invalidate_executesTombstoneScriptWithVersion() {
        when(redisTemplate.execute(any(RedisScript.class), eq(List.of("cache:account:1")), eq("6"), eq("300")))
                .thenReturn(1L);

        boolean invalidated = cacheService.invalidate(1L, 6L);

        assertThat(invalidated).isTrue();
    }

    @Test
    void whenDisabled_allOperationsShortCircuitWithoutTouchingRedis() {
        AccountCacheService disabledService = new AccountCacheService(redisTemplate, objectMapper, 300L, false);
        AccountResponse dto = new AccountResponse(1L, 10L, 500L, AccountStatus.ACTIVE, OffsetDateTime.now());

        assertThat(disabledService.getByAccountId(1L)).isEmpty();
        assertThat(disabledService.getByUserId(10L)).isEmpty();
        assertThat(disabledService.putIfNewer(1L, 10L, 5L, dto)).isFalse();
        assertThat(disabledService.invalidate(1L, 6L)).isFalse();

        verifyNoInteractions(hashOperations, valueOperations);
        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList(), any());
    }
}
