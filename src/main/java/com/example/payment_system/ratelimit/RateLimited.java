package com.example.payment_system.ratelimit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller endpoint as rate limited. Limits are enforced per client
 * IP via a Redis-backed token bucket (Bucket4j), shared across app instances.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimited {

    /**
     * Max burst size — tokens the bucket can hold when idle.
     */
    int capacity();

    /**
     * Tokens refilled per {@link #refillDurationMinutes()}.
     */
    int refillTokens();

    /**
     * Refill interval in minutes.
     */
    long refillDurationMinutes() default 1L;
}
