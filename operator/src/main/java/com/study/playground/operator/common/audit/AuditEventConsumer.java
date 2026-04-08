package com.study.playground.operator.common.audit;

import com.study.playground.avro.audit.AuditEvent;
import com.study.playground.kafka.serialization.AvroSerializer;
import com.study.playground.kafka.topic.Topics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.DltStrategy;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AuditEventConsumer {

    private final AvroSerializer avroSerializer;

    @RetryableTopic(
            attempts = "3",
            backoff = @Backoff(delay = 1000, multiplier = 2.0, maxDelay = 4000),
            dltStrategy = DltStrategy.FAIL_ON_ERROR,
            dltTopicSuffix = "-dlt",
            retryTopicSuffix = "-retry"
    )
    @KafkaListener(topics = Topics.AUDIT_EVENTS, groupId = "audit-logger")
    public void onAuditEvent(ConsumerRecord<String, byte[]> record) {
        AuditEvent event = avroSerializer.deserialize(record.value(), AuditEvent.getClassSchema());
        log.info("[AUDIT] key={}, actor={}, action={}, resource={}/{}",
                record.key(), event.getActor(), event.getAction(),
                event.getResourceType(), event.getResourceId());
    }

    @DltHandler
    public void onAuditEventDlt(ConsumerRecord<String, byte[]> record) {
        log.error("[DLT] Audit event failed after retries: topic={}, key={}, partition={}, offset={}",
                record.topic(), record.key(), record.partition(), record.offset());
    }
}
