package com.study.playground.common.audit;

import com.study.playground.avro.audit.AuditEvent;
import com.study.playground.avro.common.EventMetadata;
import com.study.playground.common.outbox.EventPublisher;
import com.study.playground.common.util.AvroSerializer;
import io.github.springwolf.core.asyncapi.annotations.AsyncOperation;
import io.github.springwolf.core.asyncapi.annotations.AsyncPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class AuditEventPublisher {

    private final EventPublisher eventPublisher;

    @AsyncPublisher(operation = @AsyncOperation(
            channelName = "playground.audit.events",
            description = "감사 이벤트(AuditEvent)를 Avro 직렬화하여 발행한다. 리소스 생성/삭제 시 자동 기록."
    ))
    public void publish(String actor, String action, String resourceType, String resourceId, String details) {
        AuditEvent event = AuditEvent.newBuilder()
                .setMetadata(EventMetadata.newBuilder()
                        .setEventId(UUID.randomUUID().toString())
                        .setCorrelationId(UUID.randomUUID().toString())
                        .setEventType("AUDIT_" + action.toUpperCase())
                        .setTimestamp(Instant.now())
                        .setSource("audit-publisher")
                        .build())
                .setActor(actor)
                .setAction(action)
                .setResourceType(resourceType)
                .setResourceId(resourceId)
                .setDetails(details)
                .build();

        eventPublisher.publish("AUDIT", resourceId,
                "AUDIT_" + action.toUpperCase(), AvroSerializer.serialize(event),
                "playground.audit.events");
    }
}
