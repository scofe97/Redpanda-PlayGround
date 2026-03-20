package com.study.playground.kafka.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "outbox")
public class OutboxProperties {
    private int batchSize = 50;
    private long pollIntervalMs = 500;
    private int maxRetries = 5;
    private int cleanupRetentionDays = 7;
    private String cleanupCron = "0 0 3 * * *";

    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }

    public long getPollIntervalMs() { return pollIntervalMs; }
    public void setPollIntervalMs(long pollIntervalMs) { this.pollIntervalMs = pollIntervalMs; }

    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }

    public int getCleanupRetentionDays() { return cleanupRetentionDays; }
    public void setCleanupRetentionDays(int cleanupRetentionDays) { this.cleanupRetentionDays = cleanupRetentionDays; }

    public String getCleanupCron() { return cleanupCron; }
    public void setCleanupCron(String cleanupCron) { this.cleanupCron = cleanupCron; }
}
