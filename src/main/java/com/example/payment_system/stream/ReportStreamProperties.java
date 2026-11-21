package com.example.payment_system.stream;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "report.stream")
public class ReportStreamProperties {

    private boolean enabled = true;
    private String key = "reports:pending";
    private String dlqKey = "reports:dlq";
    private String group = "report-workers";
    private String consumer = "report-worker-1";
    private Executor executor = new Executor();

    @Getter
    @Setter
    public static class Executor {
        private int corePoolSize = 2;
        private int maxPoolSize = 4;
        private int queueCapacity = 500;
    }
}
