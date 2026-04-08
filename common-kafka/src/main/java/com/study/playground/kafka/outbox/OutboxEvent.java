package com.study.playground.kafka.outbox;

import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "outbox_event")
@Getter
@Setter
@NoArgsConstructor
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "aggregate_type", nullable = false, length = 50)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, length = 100)
    private String aggregateId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "payload", nullable = false)
    private byte[] payload;

    @Column(name = "topic", nullable = false, length = 200)
    private String topic;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OutboxStatus status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    @Column(name = "retry_count", nullable = false)
    private Integer retryCount;

    @Column(name = "correlation_id", length = 100)
    private String correlationId;

    @Column(name = "trace_parent")
    private String traceParent;

    @Column(name = "next_retry_at")
    private LocalDateTime nextRetryAt;

    @Builder
    public OutboxEvent(Long id, String aggregateType, String aggregateId
            , String eventType, byte[] payload, String topic, OutboxStatus status
            , LocalDateTime createdAt, LocalDateTime sentAt, Integer retryCount
            , String correlationId, String traceParent, LocalDateTime nextRetryAt) {
        this.id = id;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payload = payload;
        this.topic = topic;
        this.status = status;
        this.createdAt = createdAt;
        this.sentAt = sentAt;
        this.retryCount = retryCount;
        this.correlationId = correlationId;
        this.traceParent = traceParent;
        this.nextRetryAt = nextRetryAt;
    }

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public static OutboxEvent of(String aggregateType, String aggregateId
            , String eventType, byte[] payload, String topic
            , String correlationId) {
        return OutboxEvent.builder()
                .aggregateType(aggregateType)
                .aggregateId(aggregateId)
                .eventType(eventType)
                .payload(payload)
                .topic(topic)
                .status(OutboxStatus.PENDING)
                .retryCount(0)
                .correlationId(correlationId)
                .build();
    }
}
