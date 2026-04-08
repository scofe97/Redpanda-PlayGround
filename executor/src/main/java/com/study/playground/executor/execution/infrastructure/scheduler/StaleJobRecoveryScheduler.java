package com.study.playground.executor.execution.infrastructure.scheduler;

import com.study.playground.executor.execution.application.StaleJobRecoveryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 웹훅 유실 방어 스케줄러.
 * SUBMITTED/RUNNING 상태에서 장기 체류하는 Job을 Jenkins API로 직접 확인한다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StaleJobRecoveryScheduler {

    private final StaleJobRecoveryService recoveryService;

    @Scheduled(fixedDelayString = "${executor.submitted-check-interval-ms:10000}")
    public void recoverStaleSubmitted() {
        recoveryService.recoverStaleSubmitted();
    }

    @Scheduled(fixedDelayString = "${executor.timeout-check-interval-ms:60000}")
    public void recoverStaleRunning() {
        recoveryService.recoverStaleRunning();
    }
}
