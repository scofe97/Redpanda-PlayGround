package com.study.playground.kafka.outbox;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "outbox")
public class OutboxProperties {
    private int batchSize = 50;
    private long pollIntervalMs = 500;
    private long pollInitialDelayMs = 0;
    private int maxRetries = 5;
    private int cleanupRetentionDays = 7;
    private String cleanupCron = "0 0 3 * * *";
}
