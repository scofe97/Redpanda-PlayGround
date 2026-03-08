package com.study.playground.audit;

import com.study.playground.avro.audit.AuditEvent;
import com.study.playground.kafka.topic.Topics;
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
public class AuditEventConsumer {

    @RetryableTopic(
            attempts = "3",
            backoff = @Backoff(delay = 1000, multiplier = 2.0, maxDelay = 4000),
            dltStrategy = DltStrategy.FAIL_ON_ERROR,
            dltTopicSuffix = "-dlt",
            retryTopicSuffix = "-retry"
    )
    @KafkaListener(topics = Topics.AUDIT_EVENTS, groupId = "audit-logger")
    public void onAuditEvent(ConsumerRecord<String, byte[]> record) {
        String payload = new String(record.value());
        log.info("[AUDIT] key={}, payload={}", record.key(), payload);
    }

    @DltHandler
    public void onAuditEventDlt(ConsumerRecord<String, byte[]> record) {
        log.error("[DLT] Audit event failed after retries: topic={}, key={}, partition={}, offset={}",
                record.topic(), record.key(), record.partition(), record.offset());
    }
}
