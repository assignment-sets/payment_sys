package com.example.payment_system.ratelimit;

import com.example.payment_system.entity.Role;
import com.example.payment_system.exception.RateLimitExceededException;
import com.example.payment_system.security.AuthenticatedUser;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.BucketProxy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.distributed.proxy.RemoteBucketBuilder;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RateLimitAspectTest {

    @Mock
    private ProxyManager<byte[]> proxyManager;
    @Mock
    private RemoteBucketBuilder<byte[]> bucketBuilder;
    @Mock
    private BucketProxy bucketProxy;
    @Mock
    private ProceedingJoinPoint pjp;
    @Mock
    private Signature signature;
    @Mock
    private ConsumptionProbe probe;

    private RateLimitAspect aspect;
    private MockHttpServletRequest request;

    @RateLimited(capacity = 10, refillTokens = 5, refillDurationMinutes = 2)
    @SuppressWarnings("unused")
    private void customRateLimitedMethod() {
    }

    @RateLimited(capacity = 3, refillTokens = 3)
    @SuppressWarnings("unused")
    private void defaultDurationRateLimitedMethod() {
    }

    @BeforeEach
    void setUp() {
        aspect = new RateLimitAspect(proxyManager);
        request = new MockHttpServletRequest();
        request.setRemoteAddr("192.168.1.50");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        SecurityContextHolder.clearContext();

        lenient().when(pjp.getSignature()).thenReturn(signature);
        lenient().when(signature.toShortString()).thenReturn("AuthController.login(..)");
        lenient().when(proxyManager.builder()).thenReturn(bucketBuilder);
        lenient().when(bucketBuilder.build(any(byte[].class), org.mockito.ArgumentMatchers.<Supplier<BucketConfiguration>>any())).thenReturn(bucketProxy);
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
        SecurityContextHolder.clearContext();
    }

    private RateLimited annotationFor(String methodName) throws NoSuchMethodException {
        return RateLimitAspectTest.class.getDeclaredMethod(methodName).getAnnotation(RateLimited.class);
    }

    @Test
    void enforce_whenConsumed_proceedsAndReturnsResult() throws Throwable {
        when(bucketProxy.tryConsumeAndReturnRemaining(1)).thenReturn(probe);
        when(probe.isConsumed()).thenReturn(true);
        when(pjp.proceed()).thenReturn("ok");

        Object result = aspect.enforce(pjp, annotationFor("customRateLimitedMethod"));

        assertThat(result).isEqualTo("ok");
        verify(pjp).proceed();
    }

    @Test
    void enforce_whenExceeded_throwsRateLimitExceededWithCalculatedRetryAfter() throws Throwable {
        when(bucketProxy.tryConsumeAndReturnRemaining(1)).thenReturn(probe);
        when(probe.isConsumed()).thenReturn(false);
        when(probe.getNanosToWaitForRefill()).thenReturn(5_000_000_000L);

        RateLimited rateLimited = annotationFor("customRateLimitedMethod");

        assertThatThrownBy(() -> aspect.enforce(pjp, rateLimited))
                .isInstanceOf(RateLimitExceededException.class)
                .satisfies(ex -> assertThat(((RateLimitExceededException) ex).getRetryAfterSeconds()).isEqualTo(6L))
                .hasMessage("Rate limit exceeded. Try again in 6 seconds.");

        verify(pjp, never()).proceed();
    }

    @Test
    void enforce_whenExceededWithZeroNanos_enforcesMinimumOneSecondRetryAfter() throws Throwable {
        when(bucketProxy.tryConsumeAndReturnRemaining(1)).thenReturn(probe);
        when(probe.isConsumed()).thenReturn(false);
        when(probe.getNanosToWaitForRefill()).thenReturn(0L);

        RateLimited rateLimited = annotationFor("customRateLimitedMethod");

        assertThatThrownBy(() -> aspect.enforce(pjp, rateLimited))
                .isInstanceOf(RateLimitExceededException.class)
                .satisfies(ex -> assertThat(((RateLimitExceededException) ex).getRetryAfterSeconds()).isEqualTo(1L));

        verify(pjp, never()).proceed();
    }

    @Test
    void enforce_whenBucketThrows_failsOpenAndProceeds() throws Throwable {
        when(bucketProxy.tryConsumeAndReturnRemaining(1)).thenThrow(new RuntimeException("Redis down"));
        when(pjp.proceed()).thenReturn("allowed-fail-open");

        Object result = aspect.enforce(pjp, annotationFor("customRateLimitedMethod"));

        assertThat(result).isEqualTo("allowed-fail-open");
        verify(pjp).proceed();
    }

    @Test
    void enforce_whenProxyManagerIsNull_throwsNullPointerException() throws Throwable {
        RateLimitAspect nullProxyAspect = new RateLimitAspect(null);
        RateLimited rateLimited = annotationFor("customRateLimitedMethod");

        assertThatThrownBy(() -> nullProxyAspect.enforce(pjp, rateLimited))
                .isInstanceOf(NullPointerException.class);

        verify(pjp, never()).proceed();
    }

    @Test
    void enforce_whenNoServletRequestBound_throwsIllegalStateException() throws Throwable {
        RequestContextHolder.resetRequestAttributes();
        RateLimited rateLimited = annotationFor("customRateLimitedMethod");

        assertThatThrownBy(() -> aspect.enforce(pjp, rateLimited))
                .isInstanceOf(IllegalStateException.class);

        verify(pjp, never()).proceed();
    }

    @Test
    void enforce_withAuthenticatedUser_usesUserIdInBucketKey() throws Throwable {
        AuthenticatedUser user = new AuthenticatedUser(42L, "alice@example.com", "hash", Role.USER);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities())
        );
        when(bucketProxy.tryConsumeAndReturnRemaining(1)).thenReturn(probe);
        when(probe.isConsumed()).thenReturn(true);
        when(pjp.proceed()).thenReturn(null);

        aspect.enforce(pjp, annotationFor("customRateLimitedMethod"));

        ArgumentCaptor<byte[]> keyCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(bucketBuilder).build(keyCaptor.capture(), org.mockito.ArgumentMatchers.<Supplier<BucketConfiguration>>any());
        assertThat(new String(keyCaptor.getValue(), StandardCharsets.UTF_8))
                .isEqualTo("rl:AuthController.login(..):user:42");
    }

    @Test
    void enforce_withAnonymousPrincipal_fallsBackToRemoteAddr() throws Throwable {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("anonymousUser", null, List.of())
        );
        when(bucketProxy.tryConsumeAndReturnRemaining(1)).thenReturn(probe);
        when(probe.isConsumed()).thenReturn(true);

        aspect.enforce(pjp, annotationFor("customRateLimitedMethod"));

        ArgumentCaptor<byte[]> keyCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(bucketBuilder).build(keyCaptor.capture(), org.mockito.ArgumentMatchers.<Supplier<BucketConfiguration>>any());
        assertThat(new String(keyCaptor.getValue(), StandardCharsets.UTF_8))
                .isEqualTo("rl:AuthController.login(..):192.168.1.50");
    }

    @Test
    void enforce_withXForwardedForHeader_usesFirstTrimmedIp() throws Throwable {
        request.addHeader("X-Forwarded-For", " 203.0.113.10 , 10.0.0.1, 10.0.0.2 ");
        when(bucketProxy.tryConsumeAndReturnRemaining(1)).thenReturn(probe);
        when(probe.isConsumed()).thenReturn(true);

        aspect.enforce(pjp, annotationFor("customRateLimitedMethod"));

        ArgumentCaptor<byte[]> keyCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(bucketBuilder).build(keyCaptor.capture(), org.mockito.ArgumentMatchers.<Supplier<BucketConfiguration>>any());
        assertThat(new String(keyCaptor.getValue(), StandardCharsets.UTF_8))
                .isEqualTo("rl:AuthController.login(..):203.0.113.10");
    }

    @Test
    void enforce_withBlankXForwardedForHeader_fallsBackToRemoteAddr() throws Throwable {
        request.addHeader("X-Forwarded-For", "   ");
        when(bucketProxy.tryConsumeAndReturnRemaining(1)).thenReturn(probe);
        when(probe.isConsumed()).thenReturn(true);

        aspect.enforce(pjp, annotationFor("customRateLimitedMethod"));

        ArgumentCaptor<byte[]> keyCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(bucketBuilder).build(keyCaptor.capture(), org.mockito.ArgumentMatchers.<Supplier<BucketConfiguration>>any());
        assertThat(new String(keyCaptor.getValue(), StandardCharsets.UTF_8))
                .isEqualTo("rl:AuthController.login(..):192.168.1.50");
    }

    @Test
    @SuppressWarnings("unchecked")
    void enforce_buildsBucketConfigurationFromRateLimitedAnnotation() throws Throwable {
        when(bucketProxy.tryConsumeAndReturnRemaining(1)).thenReturn(probe);
        when(probe.isConsumed()).thenReturn(true);

        aspect.enforce(pjp, annotationFor("customRateLimitedMethod"));

        ArgumentCaptor<Supplier<BucketConfiguration>> configCaptor = ArgumentCaptor.forClass(Supplier.class);
        verify(bucketBuilder).build(any(byte[].class), configCaptor.capture());

        BucketConfiguration config = configCaptor.getValue().get();
        Bandwidth[] bandwidths = config.getBandwidths();
        assertThat(bandwidths).hasSize(1);
        assertThat(bandwidths[0].getCapacity()).isEqualTo(10L);
        assertThat(bandwidths[0].getRefillTokens()).isEqualTo(5L);
        assertThat(bandwidths[0].getRefillPeriodNanos()).isEqualTo(Duration.ofMinutes(2).toNanos());
    }

    @Test
    @SuppressWarnings("unchecked")
    void enforce_usesDefaultRefillDurationWhenOmittedInAnnotation() throws Throwable {
        when(bucketProxy.tryConsumeAndReturnRemaining(1)).thenReturn(probe);
        when(probe.isConsumed()).thenReturn(true);

        aspect.enforce(pjp, annotationFor("defaultDurationRateLimitedMethod"));

        ArgumentCaptor<Supplier<BucketConfiguration>> configCaptor = ArgumentCaptor.forClass(Supplier.class);
        verify(bucketBuilder).build(any(byte[].class), configCaptor.capture());

        Bandwidth bandwidth = configCaptor.getValue().get().getBandwidths()[0];
        assertThat(bandwidth.getCapacity()).isEqualTo(3L);
        assertThat(bandwidth.getRefillTokens()).isEqualTo(3L);
        assertThat(bandwidth.getRefillPeriodNanos()).isEqualTo(Duration.ofMinutes(1).toNanos());
    }
}
