package com.study.playground.kafka.outbox.infrastructure.scheduler;

import com.study.playground.kafka.outbox.application.OutboxCleanupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * SENT 이벤트 정리 스케줄러.
 *
 * <p>매일 새벽 3시(기본값)에 {@code OutboxCleanupService}를 호출한다.
 * cron 표현식은 {@code outbox.cleanup-cron} 프로퍼티로 변경 가능하다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxCleanupScheduler {

    private final OutboxCleanupService outboxCleanupService;

    @Scheduled(cron = "${outbox.cleanup-cron:0 0 3 * * *}")
    public void scheduledCleanup() {
        outboxCleanupService.cleanup();
    }
}
