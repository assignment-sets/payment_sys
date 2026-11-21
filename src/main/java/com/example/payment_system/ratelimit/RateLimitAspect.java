package com.example.payment_system.ratelimit;

import com.example.payment_system.exception.RateLimitExceededException;
import com.example.payment_system.security.AuthenticatedUser;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Enforces {@link RateLimited} endpoints using a Redis-backed token bucket
 * keyed by endpoint + client IP.
 *
 * Fail-open policy: if Redis is unreachable, requests are allowed through and
 * a warning is logged — rate limiting must not take the API down.
 */
@Slf4j
@Aspect
@Component
@ConditionalOnProperty(name = "rate-limit.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class RateLimitAspect {

    private final ProxyManager<byte[]> proxyManager;

    @Around("@annotation(rateLimited)")
    public Object enforce(ProceedingJoinPoint pjp, RateLimited rateLimited) throws Throwable {
        HttpServletRequest request = ((ServletRequestAttributes)
                RequestContextHolder.currentRequestAttributes()).getRequest();

        String key = "rl:" + pjp.getSignature().toShortString() + ":" + resolvePrincipalOrIp(request);

        BucketConfiguration config = BucketConfiguration.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(rateLimited.capacity())
                        .refillGreedy(rateLimited.refillTokens(),
                                Duration.ofMinutes(rateLimited.refillDurationMinutes()))
                        .build())
                .build();

        var bucket = proxyManager.builder()
                .build(key.getBytes(StandardCharsets.UTF_8), () -> config);

        ConsumptionProbe probe;
        try {
            probe = bucket.tryConsumeAndReturnRemaining(1);
        } catch (Exception e) {
            log.warn("Rate limiter unavailable, failing open for {}", key, e);
            return pjp.proceed();
        }

        if (probe.isConsumed()) {
            return pjp.proceed();
        }

        long retryAfterSeconds = Math.max(1, probe.getNanosToWaitForRefill() / 1_000_000_000 + 1);
        throw new RateLimitExceededException(retryAfterSeconds);
    }

    /**
     * Uses authenticated user ID when available; falls back to client IP for
     * unauthenticated auth endpoints or when security is disabled.
     */
    private String resolvePrincipalOrIp(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthenticatedUser user) {
            return "user:" + user.id();
        }
        return resolveClientIp(request);
    }

    private String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
