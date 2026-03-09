package com.study.playground.common.audit;

import com.study.playground.avro.audit.AuditEvent;
import com.study.playground.common.outbox.EventPublisher;
import com.study.playground.kafka.serialization.AvroSerializer;
import com.study.playground.kafka.topic.Topics;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class AuditEventPublisher {

    private final EventPublisher eventPublisher;
    private final AvroSerializer avroSerializer;

    public void publish(String actor, String action, String resourceType, String resourceId, String details) {
        publish(actor, action, resourceType, resourceId, details, UUID.randomUUID().toString());
    }

    public void publish(String actor, String action, String resourceType, String resourceId, String details, String correlationId) {
        AuditEvent event = AuditEvent.newBuilder()
                .setActor(actor)
                .setAction(action)
                .setResourceType(resourceType)
                .setResourceId(resourceId)
                .setDetails(details)
                .build();

        eventPublisher.publish("AUDIT", resourceId,
                "AUDIT_" + action.toUpperCase(), avroSerializer.serialize(event),
                Topics.AUDIT_EVENTS, correlationId);
    }
}
