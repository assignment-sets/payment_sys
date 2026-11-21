package com.example.payment_system.stream;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Duration;

/**
 * Configures the async report Redis Stream listener on the existing
 * {@code redis-queue} (port 6380) instance using a bounded worker pool.
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(ReportStreamProperties.class)
public class ReportStreamConfig {

    @Bean
    public ThreadPoolTaskExecutor reportWorkerExecutor(ReportStreamProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.getExecutor().getCorePoolSize());
        executor.setMaxPoolSize(properties.getExecutor().getMaxPoolSize());
        executor.setQueueCapacity(properties.getExecutor().getQueueCapacity());
        executor.setThreadNamePrefix("report-worker-");
        executor.initialize();
        return executor;
    }

    @Bean(destroyMethod = "stop")
    @ConditionalOnProperty(name = "report.stream.enabled", havingValue = "true", matchIfMissing = true)
    public StreamMessageListenerContainer<String, MapRecord<String, String, String>> reportStreamListenerContainer(
            @Qualifier("queueRedisConnectionFactory") RedisConnectionFactory connectionFactory,
            @Qualifier("queueStringRedisTemplate") StringRedisTemplate queueRedisTemplate,
            @Qualifier("reportWorkerExecutor") ThreadPoolTaskExecutor workerExecutor,
            ReportStreamProperties properties,
            ReportStreamConsumer consumer) {

        TransferStreamConfig.ensureConsumerGroup(queueRedisTemplate, properties.getKey(), properties.getGroup());

        StreamMessageListenerContainer.StreamMessageListenerContainerOptions<String, MapRecord<String, String, String>> options =
                StreamMessageListenerContainer.StreamMessageListenerContainerOptions.builder()
                        .pollTimeout(Duration.ofSeconds(1))
                        .executor(workerExecutor)
                        .build();

        StreamMessageListenerContainer<String, MapRecord<String, String, String>> container =
                StreamMessageListenerContainer.create(connectionFactory, options);

        container.receive(
                Consumer.from(properties.getGroup(), properties.getConsumer()),
                StreamOffset.create(properties.getKey(), ReadOffset.lastConsumed()),
                consumer
        );

        container.start();
        log.info("Started Redis Stream listener on '{}' (group={}, consumer={})",
                properties.getKey(), properties.getGroup(), properties.getConsumer());
        return container;
    }
}
