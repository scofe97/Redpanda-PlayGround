package com.study.playground.kafka.outbox.application;

import com.study.playground.kafka.outbox.*;
import com.study.playground.kafka.outbox.domain.OutboxRetryPolicy;
import com.study.playground.kafka.outbox.infrastructure.publishing.OutboxDlqPublisher;
import com.study.playground.kafka.outbox.infrastructure.publishing.OutboxKafkaPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Outbox 폴링의 핵심 오케스트레이션 서비스.
 * executor 모듈의 {@code DispatchEvaluatorService}와 동일한 역할을 수행한다.
 *
 * <h3>처리 흐름</h3>
 * <ol>
 *   <li><b>조회 TX</b>: aggregate head 조회/락 → PENDING → PROCESSING 마킹</li>
 *   <li><b>발행 루프</b>: aggregate별 stop-on-failure로 순서 보장 (트랜잭션 밖)</li>
 *   <li><b>마킹 TX</b>: 성공 → SENT, 스킵 → PENDING 복구, 실패 → 재시도/DEAD</li>
 * </ol>
 *
 * <h3>순서 보장 메커니즘</h3>
 * <p>동일 aggregate의 이벤트 순서는 두 계층에서 보장된다:
 * <ul>
 *   <li><b>인스턴스 간</b>: {@code findHeadPendingIdsForProcessing}의 head 선별 조건.
 *       PROCESSING 상태 또는 선행 PENDING/PROCESSING이 남아 있으면 조회하지 않는다.</li>
 *   <li><b>배치 내</b>: {@code failedAggregates} Set.
 *       한 이벤트가 실패하면 동일 aggregate의 후속 이벤트를 skip하여 순서 역전을 방지한다.</li>
 * </ul>
 *
 * <h3>트랜잭션 경계 설계</h3>
 * <p>Kafka 발행은 트랜잭션 밖에서 수행한다.
 * 이유: 발행이 TX 안에 있으면 Kafka 타임아웃이 DB 커넥션을 점유하여
 * 커넥션 풀 고갈 위험이 있기 때문이다. 대신 발행 실패 시 재시도 로직으로 보상한다.
 */
@Slf4j
@Service
public class OutboxPollService {

    private final OutboxEventRepository outboxEventRepository;
    private final OutboxKafkaPublisher kafkaPublisher;
    private final OutboxDlqPublisher dlqPublisher;
    private final OutboxRetryPolicy retryPolicy;
    private final OutboxMetrics metrics;
    private final OutboxProperties properties;
    private final TransactionTemplate txTemplate;
    private final List<OutboxEventHandler> customHandlers;

    public OutboxPollService(OutboxEventRepository outboxEventRepository
            , OutboxKafkaPublisher kafkaPublisher
            , OutboxDlqPublisher dlqPublisher
            , OutboxRetryPolicy retryPolicy
            , OutboxMetrics metrics
            , OutboxProperties properties
            , TransactionTemplate txTemplate
            , ObjectProvider<OutboxEventHandler> customHandlerProvider) {
        this.outboxEventRepository = outboxEventRepository;
        this.kafkaPublisher = kafkaPublisher;
        this.dlqPublisher = dlqPublisher;
        this.retryPolicy = retryPolicy;
        this.metrics = metrics;
        this.properties = properties;
        this.txTemplate = txTemplate;
        this.customHandlers = customHandlerProvider.orderedStream().toList();
    }

    /**
     * PENDING 상태의 outbox 이벤트를 폴링하여 발행한다.
     *
     * <p>{@code OutboxPollScheduler}에서 주기적으로 호출된다.
     * 이벤트별로 커스텀 핸들러 → Kafka 발행 순서로 라우팅하며,
     * 실패한 aggregate의 후속 이벤트는 skip하여 순서를 보장한다.
     */
    public void poll() {
        // 1. 조회 + PROCESSING 마킹을 하나의 TX에서 수행
        //    aggregate별 head 이벤트만 잠그고 PROCESSING으로 전환한다.
        var events = txTemplate.execute(status -> {
            List<Long> lockedIds = outboxEventRepository
                    .findHeadPendingIdsForProcessing(properties.getBatchSize());
            if (lockedIds.isEmpty()) {
                return Collections.<OutboxEvent>emptyList();
            }
            outboxEventRepository.markAsProcessingByIds(lockedIds);
            return outboxEventRepository.findAllByIdInOrderByCreatedAtAscIdAsc(lockedIds);
        });
        if (events == null || events.isEmpty()) {
            return;
        }

        // failedAggregates: 발행 실패한 aggregate를 추적하여 동일 aggregate 후속 이벤트를 skip
        Set<String> failedAggregates = new HashSet<>();
        List<Long> sentIds = new ArrayList<>();
        List<Long> skippedIds = new ArrayList<>();

        // 2. 트랜잭션 밖에서 이벤트별 발행 (Kafka 타임아웃이 DB 커넥션을 점유하지 않도록)
        for (var event : events) {
            // 동일 aggregate의 선행 이벤트가 실패했으면 skip → 순서 역전 방지
            if (failedAggregates.contains(event.getAggregateId())) {
                skippedIds.add(event.getId());
                continue;
            }
            try {
                // 커스텀 핸들러가 처리 가능하면 Kafka 대신 핸들러로 라우팅
                if (routeToCustomHandler(event)) {
                    sentIds.add(event.getId());
                    metrics.incrementPublished();
                    log.debug("Routed outbox event to custom handler: type={}, aggregateType={}"
                            , event.getEventType(), event.getAggregateType());
                    continue;
                }

                // 기본 경로: Kafka 발행 (CloudEvents 헤더 + OTel trace 포함)
                kafkaPublisher.publish(event);
                sentIds.add(event.getId());
                metrics.incrementPublished();

                log.debug("Published outbox event: type={}, aggregateId={}"
                        , event.getEventType(), event.getAggregateId());
            } catch (Exception e) {
                log.error("Failed to publish outbox event: id={}, type={}, retryCount={}"
                        , event.getId(), event.getEventType(), event.getRetryCount(), e);
                // 동일 aggregate의 후속 이벤트도 skip되도록 등록
                failedAggregates.add(event.getAggregateId());

                // 실패 처리: 재시도 횟수 증가 또는 DEAD 전환 (별도 TX)
                txTemplate.executeWithoutResult(status -> handleFailure(event));
                metrics.incrementFailed();
            }
        }

        // 3. 성공 → SENT 마킹, 스킵된 이벤트 → PENDING 복구 (각각 별도 TX)
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
     *
     * <p>등록된 핸들러를 순회하며 {@code supports(aggregateType)}이 true인
     * 첫 번째 핸들러에 처리를 위임한다. 처리 가능한 핸들러가 없으면 false를 반환하여
     * 기본 Kafka 발행 경로로 진행한다.
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

    /**
     * 발행 실패 이벤트를 처리한다.
     *
     * <p>OutboxRetryPolicy에 재시도 초과 여부를 위임하여 판정한다:
     * <ul>
     *   <li>초과 시: DEAD 마킹 → DLQ 전송 → 커스텀 핸들러 통지</li>
     *   <li>미초과 시: 재시도 횟수 증가 + 지수 백오프 기반 다음 재시도 시각 설정</li>
     * </ul>
     */
    private void handleFailure(OutboxEvent event) {
        if (retryPolicy.isMaxRetriesExceeded(event.getRetryCount(), properties.getMaxRetries())) {
            // 최대 재시도 초과: DEAD로 전환하고 DLQ + 핸들러에 통지
            outboxEventRepository.markAsDead(event.getId());
            metrics.incrementDead();
            dlqPublisher.publishToDlq(event);
            notifyDeadToHandler(event);
            log.warn("Outbox event exceeded max retries, marked as DEAD: id={}", event.getId());
        } else {
            // 재시도 가능: 지수 백오프로 다음 재시도 시각 계산
            LocalDateTime nextRetry = retryPolicy.calculateNextRetryAt(event.getRetryCount());
            outboxEventRepository.incrementRetryAndSetNextRetryAt(event.getId(), nextRetry);
        }
    }

    /**
     * DEAD 상태 전환 시 해당 aggregate의 커스텀 핸들러에 통지한다.
     * 핸들러는 보상 로직(예: 상태를 FAILED로 변경)을 수행할 수 있다.
     */
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
}
