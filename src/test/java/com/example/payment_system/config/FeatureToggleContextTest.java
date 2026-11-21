package com.example.payment_system.config;

import com.example.payment_system.cache.AccountCacheService;
import com.example.payment_system.cache.AccountInvalidationListener;
import com.example.payment_system.cache.RedisCacheConfig;
import com.example.payment_system.cache.ReportCacheService;
import com.example.payment_system.ratelimit.RateLimitAspect;
import com.example.payment_system.ratelimit.RateLimitConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class FeatureToggleContextTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(ctx -> ctx.getBeanFactory().setConversionService(ApplicationConversionService.getSharedInstance()))
            .withUserConfiguration(
                    RateLimitConfig.class,
                    RateLimitAspect.class,
                    RedisCacheConfig.class,
                    AccountCacheService.class,
                    ReportCacheService.class,
                    AccountInvalidationListener.class
            )
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(DataSource.class, () -> mock(DataSource.class))
            .withPropertyValues(
                    "spring.data.redis.host=localhost",
                    "spring.data.redis.port=6379"
            );

    @Test
    void whenRateLimitAndCacheDisabled_optionalBeansAreAbsentAndCacheServicesNoOp() {
        contextRunner
                .withPropertyValues(
                        "rate-limit.enabled=false",
                        "cache.enabled=false"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(RateLimitConfig.class);
                    assertThat(context).doesNotHaveBean(RateLimitAspect.class);
                    assertThat(context).doesNotHaveBean("rateLimitRedisClient");
                    assertThat(context).doesNotHaveBean("rateLimitProxyManager");

                    assertThat(context).doesNotHaveBean(RedisCacheConfig.class);
                    assertThat(context).doesNotHaveBean(AccountInvalidationListener.class);
                    assertThat(context).doesNotHaveBean("cacheRedisConnectionFactory");
                    assertThat(context).doesNotHaveBean("cacheStringRedisTemplate");
                    assertThat(context).doesNotHaveBean("cacheManager");

                    assertThat(context).hasSingleBean(AccountCacheService.class);
                    assertThat(context).hasSingleBean(ReportCacheService.class);
                    assertThat(context.getBean(AccountCacheService.class).getByAccountId(1L)).isEmpty();
                    assertThat(context.getBean(ReportCacheService.class).getCompletedReport(java.util.UUID.randomUUID())).isEmpty();
                });
    }

    @Test
    void whenCacheEnabled_cacheBeansAreRegistered() {
        contextRunner
                .withPropertyValues(
                        "rate-limit.enabled=false",
                        "cache.enabled=true",
                        "cache.account.notify.enabled=false"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(RedisCacheConfig.class);
                    assertThat(context).hasBean("cacheRedisConnectionFactory");
                    assertThat(context).hasBean("cacheStringRedisTemplate");
                    assertThat(context).hasBean("cacheManager");
                    assertThat(context).doesNotHaveBean(AccountInvalidationListener.class);
                });
    }
}
