package com.study.playground.common.outbox;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class EventPublisher {

    private final OutboxMapper outboxMapper;

    public void publish(String aggregateType, String aggregateId,
                        String eventType, byte[] payload, String topic) {
        outboxMapper.insert(OutboxEvent.of(aggregateType, aggregateId, eventType, payload, topic));
    }
}
