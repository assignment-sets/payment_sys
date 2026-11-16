package com.example.payment_system.stream;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@Getter
@Setter
@ConfigurationProperties(prefix = "transfer.stream")
public class TransferStreamProperties {

    private boolean enabled = true;
    private String key = "transfers:pending";
    private String dlqKey = "transfers:dlq";
    private String group = "transfer-workers";
    private String consumer = "worker-1";
    private Redis redis = new Redis();
    private Executor executor = new Executor();

    @Getter
    @Setter
    public static class Redis {
        private String host = "localhost";
        private int port = 6380;
        private Duration timeout = Duration.ofSeconds(5);
    }

    @Getter
    @Setter
    public static class Executor {
        private int corePoolSize = 20;
        private int maxPoolSize = 50;
        private int queueCapacity = 500;
    }
}
