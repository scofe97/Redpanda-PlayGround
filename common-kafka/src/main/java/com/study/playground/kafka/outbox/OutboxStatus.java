package com.study.playground.kafka.outbox;

public enum OutboxStatus {
    PENDING, PROCESSING, SENT, DEAD;
}
