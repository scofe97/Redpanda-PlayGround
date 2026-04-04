package com.study.playground.kafka.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Outbox 폴링 메트릭. Prometheus /actuator/prometheus에서 확인 가능.
 *
 * - outbox.events.published: 성공 발행 건수
 * - outbox.events.failed: 발행 실패 건수 (재시도 대상)
 * - outbox.events.dead: 최대 재시도 초과로 DEAD 전이된 건수
 * - outbox.queue.pending: 현재 PENDING 상태 이벤트 수 (gauge)
 */
public class OutboxMetrics {

    private final Counter publishedCounter;
    private final Counter failedCounter;
    private final Counter deadCounter;

    public OutboxMetrics(MeterRegistry registry, OutboxEventRepository outboxEventRepository) {
        this.publishedCounter = Counter.builder("outbox.events.published")
                .description("Number of outbox events successfully published to Kafka")
                .register(registry);
        this.failedCounter = Counter.builder("outbox.events.failed")
                .description("Number of outbox event publish failures")
                .register(registry);
        this.deadCounter = Counter.builder("outbox.events.dead")
                .description("Number of outbox events marked as DEAD after max retries")
                .register(registry);
        registry.gauge("outbox.queue.pending"
                , outboxEventRepository
                , OutboxEventRepository::countPending);
    }

    public void incrementPublished() { publishedCounter.increment(); }
    public void incrementFailed() { failedCounter.increment(); }
    public void incrementDead() { deadCounter.increment(); }
}
