package com.study.playground.common.outbox;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@Builder
public class OutboxEvent {
    private Long id;
    private String aggregateType;
    private String aggregateId;
    private String eventType;
    private byte[] payload;
    private String topic;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime sentAt;
    private Integer retryCount;
    private String correlationId;

    public static OutboxEvent of(String aggregateType, String aggregateId,
                                  String eventType, byte[] payload, String topic,
                                  String correlationId) {
        return OutboxEvent.builder()
                .aggregateType(aggregateType)
                .aggregateId(aggregateId)
                .eventType(eventType)
                .payload(payload)
                .topic(topic)
                .status("PENDING")
                .retryCount(0)
                .correlationId(correlationId)
                .build();
    }
}
