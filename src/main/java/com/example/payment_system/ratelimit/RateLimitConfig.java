package com.example.payment_system.ratelimit;

import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.RedisClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
@ConditionalOnProperty(name = "rate-limit.enabled", havingValue = "true", matchIfMissing = true)
public class RateLimitConfig {

    /**
     * Dedicated Lettuce client for the rate limiter. Kept separate from
     * Spring Data Redis so its lifecycle is fully owned here.
     */
    @Bean(destroyMethod = "shutdown")
    public RedisClient rateLimitRedisClient(
            @Value("${spring.data.redis.host}") String host,
            @Value("${spring.data.redis.port}") int port
    ) {
        return RedisClient.create("redis://" + host + ":" + port);
    }

    /**
     * Proxy manager storing bucket state in Redis via atomic compare-and-swap,
     * so limits hold across all app instances.
     *
     * Expiration strategy: an idle bucket's key TTL equals the time it would
     * take to refill to full capacity (capped at 2 min), so buckets of clients
     * who stop calling are garbage-collected by Redis instead of leaking keys.
     */
    @Bean
    public LettuceBasedProxyManager<byte[]> rateLimitProxyManager(RedisClient rateLimitRedisClient) {
        return LettuceBasedProxyManager.builderFor(rateLimitRedisClient)
                .withExpirationStrategy(ExpirationAfterWriteStrategy
                        .basedOnTimeForRefillingBucketUpToMax(Duration.ofMinutes(2)))
                .build();
    }
}
