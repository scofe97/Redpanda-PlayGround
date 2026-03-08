package com.study.playground.common.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxPoller {

    private static final int MAX_RETRIES = 5;

    private final OutboxMapper outboxMapper;
    private final KafkaTemplate<String, byte[]> kafkaTemplate;

    @Scheduled(fixedDelay = 500)
    public void pollAndPublish() {
        List<OutboxEvent> events = outboxMapper.findPendingEvents(50);
        for (OutboxEvent event : events) {
            try {
                ProducerRecord<String, byte[]> record = new ProducerRecord<>(
                        event.getTopic(), null, event.getAggregateId(), event.getPayload());
                // CloudEvents: ce_type은 이벤트별로 다르므로 여기서 설정 (나머지는 Interceptor가 자동 부착)
                record.headers().add("ce_type", event.getEventType().getBytes(StandardCharsets.UTF_8));
                record.headers().add("eventType", event.getEventType().getBytes(StandardCharsets.UTF_8));

                kafkaTemplate.send(record).get();
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
