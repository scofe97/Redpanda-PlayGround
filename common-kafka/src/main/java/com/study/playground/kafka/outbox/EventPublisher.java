package com.study.playground.kafka.outbox;

import com.study.playground.kafka.serialization.AvroSerializer;
import com.study.playground.kafka.tracing.TraceContextUtil;
import lombok.RequiredArgsConstructor;
import org.apache.avro.specific.SpecificRecord;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class EventPublisher {

    private final OutboxEventRepository outboxEventRepository;
    private final AvroSerializer avroSerializer;

    /** Avro 레코드를 직렬화하여 Outbox에 저장한다. 대부분의 호출은 이 메서드를 사용. */
    public void publish(String aggregateType
            , String aggregateId
            , String eventType
            , SpecificRecord record
            , String topic
            , String correlationId) {
        publish(aggregateType, aggregateId, eventType, avroSerializer.serialize(record), topic, correlationId);
    }

    /** 이미 직렬화된 payload를 Outbox에 저장한다. */
    public void publish(String aggregateType
            , String aggregateId
            , String eventType
            , byte[] payload
            , String topic
            , String correlationId) {
        OutboxEvent event = OutboxEvent.of(aggregateType, aggregateId, eventType, payload, topic, correlationId);
        event.setTraceParent(TraceContextUtil.captureTraceParent());
        outboxEventRepository.save(event);
    }
}
