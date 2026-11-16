package com.example.payment_system.stream;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Slf4j
@Configuration
@EnableConfigurationProperties(TransferStreamProperties.class)
public class TransferStreamConfig {

    @Bean
    public LettuceConnectionFactory queueRedisConnectionFactory(TransferStreamProperties properties) {
        RedisStandaloneConfiguration serverConfig = new RedisStandaloneConfiguration(
                properties.getRedis().getHost(),
                properties.getRedis().getPort()
        );
        LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
                .commandTimeout(properties.getRedis().getTimeout())
                .build();
        return new LettuceConnectionFactory(serverConfig, clientConfig);
    }

    @Bean
    public StringRedisTemplate queueStringRedisTemplate(
            @Qualifier("queueRedisConnectionFactory") RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }

    @Bean
    public ThreadPoolTaskExecutor transferWorkerExecutor(TransferStreamProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.getExecutor().getCorePoolSize());
        executor.setMaxPoolSize(properties.getExecutor().getMaxPoolSize());
        executor.setQueueCapacity(properties.getExecutor().getQueueCapacity());
        executor.setThreadNamePrefix("transfer-worker-");
        executor.initialize();
        return executor;
    }

    @Bean(destroyMethod = "stop")
    @ConditionalOnProperty(name = "transfer.stream.enabled", havingValue = "true", matchIfMissing = true)
    public StreamMessageListenerContainer<String, MapRecord<String, String, String>> transferStreamListenerContainer(
            @Qualifier("queueRedisConnectionFactory") RedisConnectionFactory connectionFactory,
            @Qualifier("queueStringRedisTemplate") StringRedisTemplate queueRedisTemplate,
            @Qualifier("transferWorkerExecutor") ThreadPoolTaskExecutor workerExecutor,
            TransferStreamProperties properties,
            TransferStreamConsumer consumer) {

        ensureConsumerGroup(queueRedisTemplate, properties.getKey(), properties.getGroup());

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

    private void ensureConsumerGroup(StringRedisTemplate redisTemplate, String streamKey, String group) {
        try {
            redisTemplate.execute((RedisCallback<Void>) connection -> {
                connection.streamCommands().xGroupCreate(
                        streamKey.getBytes(StandardCharsets.UTF_8),
                        group,
                        ReadOffset.from("0-0"),
                        true
                );
                return null;
            });
            log.info("Created consumer group '{}' on stream '{}'", group, streamKey);
        } catch (RedisSystemException e) {
            if (isBusyGroupError(e)) {
                log.debug("Consumer group '{}' already exists on stream '{}'", group, streamKey);
            } else {
                throw e;
            }
        }
    }

    private boolean isBusyGroupError(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            if (cur.getMessage() != null && cur.getMessage().contains("BUSYGROUP")) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }
}
