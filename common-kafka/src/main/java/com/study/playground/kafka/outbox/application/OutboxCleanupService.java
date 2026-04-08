package com.study.playground.kafka.outbox.application;

import com.study.playground.kafka.outbox.OutboxEventRepository;
import com.study.playground.kafka.outbox.OutboxProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * SENT 상태의 오래된 outbox 레코드를 정리하는 애플리케이션 서비스.
 *
 * <p>보존 기간(기본 7일) 초과 레코드를 삭제하여 outbox 테이블 비대화를 방지한다.
 * 스케줄링 자체는 {@code OutboxCleanupScheduler}가 담당하고,
 * 이 서비스는 정리 로직만 수행한다 (executor 패턴과 동일한 Scheduler → Service 분리).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxCleanupService {

    private final OutboxEventRepository outboxEventRepository;
    private final OutboxProperties properties;

    /**
     * 보존 기간 초과 SENT 레코드를 삭제한다.
     *
     * <p>삭제 기준: sentAt < (현재 시각 - cleanupRetentionDays).
     * 기본 보존 기간은 7일이며 {@code outbox.cleanup-retention-days}로 변경 가능하다.
     */
    @Transactional
    public void cleanup() {
        var before = LocalDateTime.now().minusDays(properties.getCleanupRetentionDays());
        outboxEventRepository.deleteOlderThan(before);
        log.info("Cleaned up SENT outbox events older than {}", before);
    }
}
