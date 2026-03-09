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

/**
 * Transactional Outbox 패턴의 폴링 퍼블리셔.
 *
 * DB outbox 테이블에 저장된 이벤트를 주기적으로 폴링하여 Kafka로 발행한다.
 * 트랜잭션과 메시지 발행의 원자성을 보장하기 위해, 비즈니스 로직은 DB에만 쓰고
 * 이 폴러가 비동기로 Kafka 발행을 담당한다.
 */
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

    /**
     * PENDING 상태의 outbox 이벤트를 폴링하여 Kafka로 발행한다.
     *
     * 흐름: PENDING 조회 → Kafka 발행(동기 대기 5s) → SENT 마킹.
     * 발행 실패 시 retryCount를 증가시키고, MAX_RETRIES 초과 시 DEAD로 전이하여
     * 무한 재시도를 방지한다.
     */
    @Scheduled(fixedDelay = 500)
    public void pollAndPublish() {
        List<OutboxEvent> events = outboxMapper.findPendingEvents(50);
        for (OutboxEvent event : events) {
            try {
                ProducerRecord<String, byte[]> record = new ProducerRecord<>(
                        event.getTopic()
                        , null
                        , event.getAggregateId()
                        , event.getPayload()
                );
                addHeaders(record, event);

                // 메시지 적재
                kafkaTemplate.send(record).get(5, TimeUnit.SECONDS);

                // 메시지 적재 성공 처리
                outboxMapper.markAsSent(event.getId());

                log.debug("Published outbox event: type={}, aggregateId={}",
                        event.getEventType(), event.getAggregateId());
            } catch (Exception e) {
                log.error("Failed to publish outbox event: id={}, type={}, retryCount={}",
                        event.getId(), event.getEventType(), event.getRetryCount(), e);

                // MAX_RETRIES 초과 시 DEAD 처리하여 poison event가 폴링을 계속 점유하는 것을 방지
                if (event.getRetryCount() != null && event.getRetryCount() >= MAX_RETRIES) {
                    outboxMapper.markAsDead(event.getId());
                    log.warn("Outbox event exceeded max retries, marked as DEAD: id={}", event.getId());
                } else {
                    // 재시도 카운트 증가
                    outboxMapper.incrementRetryCount(event.getId());
                }
            }
        }
    }

    /**
     * ProducerRecord에 CloudEvents 표준 헤더와 레거시 호환 헤더를 설정한다.
     *
     * CloudEvents spec v1.0 필수 속성(specversion, id, source, type)을 ce_ 접두사로 추가하여
     * 컨슈머가 이벤트 메타데이터를 표준 방식으로 파싱할 수 있게 한다.
     * correlationId는 CloudEvents 확장 속성으로 ce_ 접두사를 붙여 전달한다.
     */
    private void addHeaders(ProducerRecord<String, byte[]> record, OutboxEvent event) {
        // CloudEvents 필수 속성 (spec v1.0)
        record.headers().add("ce_specversion", CE_SPECVERSION.getBytes(StandardCharsets.UTF_8));
        record.headers().add("ce_id", String.valueOf(event.getId()).getBytes(StandardCharsets.UTF_8));
        record.headers().add("ce_source", ("/" + applicationName).getBytes(StandardCharsets.UTF_8));
        record.headers().add("ce_type", event.getEventType().getBytes(StandardCharsets.UTF_8));

        // CloudEvents 확장 속성: 분산 추적용 correlation ID
        if (event.getCorrelationId() != null) {
            record.headers().add("ce_correlationid", event.getCorrelationId().getBytes(StandardCharsets.UTF_8));
        }
    }
}
