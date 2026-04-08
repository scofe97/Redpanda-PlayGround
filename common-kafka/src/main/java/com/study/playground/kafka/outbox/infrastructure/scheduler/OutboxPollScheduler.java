package com.study.playground.kafka.outbox.infrastructure.scheduler;

import com.study.playground.kafka.outbox.application.OutboxPollService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Outbox 폴링 스케줄러. executor의 {@code DispatchScheduler}와 동일한 패턴.
 *
 * <p>이 클래스는 스케줄링 주기만 담당하고, 실제 폴링 로직은
 * {@code OutboxPollService}에 위임한다.
 * 스케줄링과 비즈니스 로직을 분리하여 테스트와 유지보수를 용이하게 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxPollScheduler {

    private final OutboxPollService outboxPollService;

    @Scheduled(fixedDelayString = "${outbox.poll-interval-ms:500}")
    public void scheduledPoll() {
        outboxPollService.poll();
    }
}
