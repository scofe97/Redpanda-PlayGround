package com.study.playground.kafka.outbox;

import com.study.playground.kafka.topic.Topics;
import com.study.playground.kafka.tracing.TraceContextUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import org.springframework.beans.factory.ObjectProvider;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Transactional Outbox 패턴의 폴링 퍼블리셔.
 *
 * <p>DB outbox 테이블에 저장된 이벤트를 주기적으로 폴링하여 Kafka로 발행한다.
 * 트랜잭션과 메시지 발행의 원자성을 보장하기 위해, 비즈니스 로직은 DB에만 쓰고
 * 이 폴러가 비동기로 Kafka 발행을 담당한다.
 *
 * <h3>순서 보장</h3>
 * <p>동일 aggregate의 이벤트 순서는 두 가지 메커니즘으로 보장된다:
 * <ul>
 *   <li><b>인스턴스 간</b>: {@code findAndMarkProcessing}의 {@code NOT EXISTS} 가드.
 *       PROCESSING 상태인 이벤트가 있는 aggregate는 다른 인스턴스가 조회하지 않는다.</li>
 *   <li><b>배치 내</b>: {@code failedAggregates} Set.
 *       실패한 aggregate의 후속 이벤트를 skip하여 순서 역전을 방지한다.</li>
 * </ul>
 *
 * <h3>DLQ 라우팅</h3>
 * <p>최대 재시도 초과 시 DEAD 마킹 후 {@code playground.dlq} 토픽으로 best-effort 전송한다.
 * DLQ 전송 실패는 DEAD 상태 전환을 막지 않는다.
 */
@Slf4j
@Component
public class OutboxPoller {

    private static final String CE_SPECVERSION = "1.0";

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, byte[]> kafkaTemplate;
    private final TransactionTemplate txTemplate;
    private final OutboxProperties properties;
    private final OutboxMetrics metrics;
    private final List<OutboxEventHandler> customHandlers;

    @Value("${spring.application.name}")
    private String applicationName;

    public OutboxPoller(OutboxEventRepository outboxEventRepository
            , KafkaTemplate<String, byte[]> kafkaTemplate
            , TransactionTemplate txTemplate
            , OutboxProperties properties
            , OutboxMetrics metrics
            , ObjectProvider<OutboxEventHandler> customHandlerProvider) {
        this.outboxEventRepository = outboxEventRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.txTemplate = txTemplate;
        this.properties = properties;
        this.metrics = metrics;
        this.customHandlers = customHandlerProvider.orderedStream().toList();
    }

    /**
     * PENDING 상태의 outbox 이벤트를 폴링하여 Kafka로 발행한다.
     *
     * 흐름:
     * 1. 조회 TX: PENDING → PROCESSING 마킹 (FOR UPDATE SKIP LOCKED + UPDATE)
     * 2. 발행 루프: aggregate별 stop-on-failure로 순서 보장 (트랜잭션 밖)
     * 3. 마킹 TX: 성공 → SENT, 스킵 → PENDING 복구, 실패 → 재시도/DEAD
     */
    @Scheduled(fixedDelayString = "${outbox.poll-interval-ms:500}")
    public void pollAndPublish() {
        // 1. 조회 + PROCESSING 마킹을 하나의 TX에서 수행
        var events = txTemplate.execute(status ->
                outboxEventRepository.findAndMarkProcessing(properties.getBatchSize()));
        if (events == null || events.isEmpty()) {
            return;
        }

        Set<String> failedAggregates = new HashSet<>();
        List<Long> sentIds = new ArrayList<>();
        List<Long> skippedIds = new ArrayList<>();

        // 2. 트랜잭션 밖에서 Kafka 발행
        for (var event : events) {
            if (failedAggregates.contains(event.getAggregateId())) {
                skippedIds.add(event.getId());
                continue;
            }
            try {
                if (routeToCustomHandler(event)) {
                    sentIds.add(event.getId());
                    metrics.incrementPublished();
                    log.debug("Routed outbox event to custom handler: type={}, aggregateType={}"
                            , event.getEventType(), event.getAggregateType());
                    continue;
                }

                ProducerRecord<String, byte[]> record = new ProducerRecord<>(
                        event.getTopic()
                        , null
                        , event.getAggregateId()
                        , event.getPayload()
                );

                // 헤더 추가
                addHeaders(record, event);

                // 전송
                publishWithTraceContext(record, event);
                sentIds.add(event.getId());
                metrics.incrementPublished();

                log.debug("Published outbox event: type={}, aggregateId={}"
                        , event.getEventType(), event.getAggregateId());
            } catch (Exception e) {
                // 아웃박스 전송 실패
                log.error("Failed to publish outbox event: id={}, type={}, retryCount={}"
                        , event.getId(), event.getEventType(), event.getRetryCount(), e);
                failedAggregates.add(event.getAggregateId());

                //
                txTemplate.executeWithoutResult(status -> handleFailure(event));
                metrics.incrementFailed();
            }
        }

        // 3. 성공 → SENT, 스킵된 이벤트 → PENDING 복구
        if (!sentIds.isEmpty()) {
            txTemplate.executeWithoutResult(status ->
                    outboxEventRepository.batchMarkAsSent(sentIds));
        }
        if (!skippedIds.isEmpty()) {
            txTemplate.executeWithoutResult(status ->
                    outboxEventRepository.revertToPending(skippedIds));
        }
    }

    /**
     * 커스텀 핸들러가 이벤트를 처리할 수 있으면 위임한다.
     * 처리 가능한 핸들러가 없으면 false를 반환하여 기본 Kafka 발행으로 진행한다.
     */
    private boolean routeToCustomHandler(OutboxEvent event) throws Exception {
        for (OutboxEventHandler handler : customHandlers) {
            if (handler.supports(event.getAggregateType())) {
                handler.handle(event);
                return true;
            }
        }
        return false;
    }

    private void handleFailure(OutboxEvent event) {
        if (event.getRetryCount() != null && event.getRetryCount() >= properties.getMaxRetries()) {
            outboxEventRepository.markAsDead(event.getId());
            metrics.incrementDead();
            publishToDlq(event);
            notifyDeadToHandler(event);
            log.warn("Outbox event exceeded max retries, marked as DEAD: id={}", event.getId());
        } else {
            LocalDateTime nextRetry = LocalDateTime.now()
                    .plusSeconds((long) Math.pow(2, event.getRetryCount() == null ? 0 : event.getRetryCount()));
            outboxEventRepository.incrementRetryAndSetNextRetryAt(event.getId(), nextRetry);
        }
    }

    /**
     * SENT 상태의 오래된 레코드를 정리한다.
     * 보존 기간(기본 7일) 초과 레코드를 삭제하여 테이블 비대화를 방지한다.
     */
    @Scheduled(cron = "${outbox.cleanup-cron:0 0 3 * * *}")
    public void cleanupSentEvents() {
        var before = LocalDateTime.now().minusDays(properties.getCleanupRetentionDays());
        outboxEventRepository.deleteOlderThan(before);
        log.info("Cleaned up SENT outbox events older than {}", before);
    }

    /** DEAD 상태의 원본 이벤트를 DLQ 토픽으로 best-effort 전송한다. */
    private void publishToDlq(OutboxEvent event) {
        try {
            var record = new ProducerRecord<String, byte[]>(
                    Topics.DLQ
                    , null
                    , event.getAggregateId()
                    , event.getPayload()
            );
            record.headers().add("dlq.original.topic"
                    , event.getTopic().getBytes(StandardCharsets.UTF_8));
            record.headers().add("dlq.original.event.type"
                    , event.getEventType().getBytes(StandardCharsets.UTF_8));
            record.headers().add("dlq.original.aggregate.type"
                    , event.getAggregateType().getBytes(StandardCharsets.UTF_8));
            record.headers().add("dlq.failure.reason"
                    , "max_retries_exceeded".getBytes(StandardCharsets.UTF_8));
            record.headers().add("dlq.retry.count"
                    , String.valueOf(event.getRetryCount()).getBytes(StandardCharsets.UTF_8));

            kafkaTemplate.send(record).get(5, TimeUnit.SECONDS);
            log.info("Published DEAD outbox event to DLQ: id={}, type={}"
                    , event.getId(), event.getEventType());
        } catch (Exception e) {
            log.error("Failed to publish DEAD event to DLQ: id={}", event.getId(), e);
        }
    }

    /** DEAD 상태 전환 시 커스텀 핸들러에 통지한다. */
    private void notifyDeadToHandler(OutboxEvent event) {
        for (OutboxEventHandler handler : customHandlers) {
            if (handler.supports(event.getAggregateType())) {
                try {
                    handler.onDead(event);
                } catch (Exception e) {
                    log.error("onDead handler failed: id={}", event.getId(), e);
                }
                break;
            }
        }
    }

    /**
     * 저장된 traceparent로 trace context를 복원하고 Kafka 메시지를 발행한다.
     *
     * OTel이 classpath에 없으면 trace 복원 없이 바로 발행한다.
     * OTel이 있고 traceparent가 유효하면 원래 HTTP 요청 trace의 자식 스팬으로 발행된다.
     */
    private void publishWithTraceContext(
            ProducerRecord<String, byte[]> record
            , OutboxEvent event
    ) throws Exception {

        //
        if (!TraceContextUtil.isOtelAvailable()) {
            kafkaTemplate.send(record).get(5, TimeUnit.SECONDS);
            return;
        }

        TraceContextUtil.publishWithTrace(
                event.getTraceParent()
                , "OutboxPoller.publish"
                , event.getId()
                , event.getEventType()
                , event.getAggregateId()
                , () -> {
                    String traceparent = TraceContextUtil.captureTraceParent();
                    if (traceparent != null) {
                        record.headers().add("traceparent"
                                , traceparent.getBytes(StandardCharsets.UTF_8));
                    }
                    try {
                        kafkaTemplate.send(record).get(5, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
    }

    /**
     * ProducerRecord에 CloudEvents 표준 헤더와 레거시 호환 헤더를 설정한다.
     */
    private void addHeaders(ProducerRecord<String, byte[]> record, OutboxEvent event) {
        record.headers().add("ce_specversion", CE_SPECVERSION.getBytes(StandardCharsets.UTF_8));
        record.headers().add("ce_id", String.valueOf(event.getId()).getBytes(StandardCharsets.UTF_8));
        record.headers().add("ce_source", ("/" + applicationName).getBytes(StandardCharsets.UTF_8));
        record.headers().add("ce_type", event.getEventType().getBytes(StandardCharsets.UTF_8));

        if (event.getCorrelationId() != null) {
            record.headers().add("ce_correlationid", event.getCorrelationId().getBytes(StandardCharsets.UTF_8));
        }
    }
}
