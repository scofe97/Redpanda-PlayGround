package com.study.playground.kafka.topic;

/**
 * Kafka 토픽 이름 상수.
 * 모든 Producer/Consumer에서 이 상수를 사용하여 하드코딩을 제거한다.
 */
public final class Topics {

    private Topics() {}

    public static final String PIPELINE_COMMANDS = "playground.pipeline.commands";
    public static final String PIPELINE_EVENTS = "playground.pipeline.events";
    public static final String TICKET_EVENTS = "playground.ticket.events";
    public static final String WEBHOOK_INBOUND = "playground.webhook.inbound";
    public static final String AUDIT_EVENTS = "playground.audit.events";
    public static final String DLQ = "playground.dlq";
}
