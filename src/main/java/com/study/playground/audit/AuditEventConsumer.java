package com.study.playground.audit;

import io.github.springwolf.core.asyncapi.annotations.AsyncListener;
import io.github.springwolf.core.asyncapi.annotations.AsyncOperation;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class AuditEventConsumer {

    @AsyncListener(operation = @AsyncOperation(
            channelName = "playground.audit.events",
            description = "감사 이벤트를 수신하여 로그로 기록한다."
    ))
    @KafkaListener(topics = "playground.audit.events", groupId = "audit-logger")
    public void onAuditEvent(ConsumerRecord<String, byte[]> record) {
        String payload = new String(record.value());
        log.info("[AUDIT] key={}, payload={}", record.key(), payload);
    }
}
