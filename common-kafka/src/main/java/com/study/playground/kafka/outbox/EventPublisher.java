package com.study.playground.kafka.outbox;

import com.study.playground.kafka.tracing.TraceContextUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class EventPublisher {

    private final OutboxMapper outboxMapper;

    public void publish(String aggregateType
            , String aggregateId
            , String eventType
            , byte[] payload
            , String topic
            , String correlationId) {
        OutboxEvent event = OutboxEvent.of(aggregateType, aggregateId, eventType, payload, topic, correlationId);
        event.setTraceParent(TraceContextUtil.captureTraceParent());
        outboxMapper.insert(event);
    }
}
