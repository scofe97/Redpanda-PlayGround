package com.study.playground.common.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxPoller {

    private static final int MAX_RETRIES = 5;
    private static final String CE_SPECVERSION = "1.0";

    private final OutboxMapper outboxMapper;
    private final KafkaTemplate<String, byte[]> kafkaTemplate;

    @Value("${spring.application.name}")
    private String applicationName;

    @Scheduled(fixedDelay = 500)
    public void pollAndPublish() {
        List<OutboxEvent> events = outboxMapper.findPendingEvents(50);
        for (OutboxEvent event : events) {
            try {
                ProducerRecord<String, byte[]> record = new ProducerRecord<>(
                        event.getTopic(), null, event.getAggregateId(), event.getPayload());
                // CloudEvents 필수 속성 (spec v1.0)
                record.headers().add("ce_specversion", CE_SPECVERSION.getBytes(StandardCharsets.UTF_8));
                record.headers().add("ce_id", String.valueOf(event.getId()).getBytes(StandardCharsets.UTF_8));
                record.headers().add("ce_source", ("/" + applicationName).getBytes(StandardCharsets.UTF_8));
                record.headers().add("ce_type", event.getEventType().getBytes(StandardCharsets.UTF_8));
                // 레거시 호환 + 확장 속성
                record.headers().add("eventType", event.getEventType().getBytes(StandardCharsets.UTF_8));
                if (event.getCorrelationId() != null) {
                    record.headers().add("correlationId", event.getCorrelationId().getBytes(StandardCharsets.UTF_8));
                }

                kafkaTemplate.send(record).get(5, TimeUnit.SECONDS);
                outboxMapper.markAsSent(event.getId());
                log.debug("Published outbox event: type={}, aggregateId={}",
                        event.getEventType(), event.getAggregateId());
            } catch (Exception e) {
                log.error("Failed to publish outbox event: id={}, type={}, retryCount={}",
                        event.getId(), event.getEventType(), event.getRetryCount(), e);
                if (event.getRetryCount() != null && event.getRetryCount() >= MAX_RETRIES) {
                    outboxMapper.markAsDead(event.getId());
                    log.warn("Outbox event exceeded max retries, marked as DEAD: id={}", event.getId());
                } else {
                    outboxMapper.incrementRetryCount(event.getId());
                }
            }
        }
    }
}
