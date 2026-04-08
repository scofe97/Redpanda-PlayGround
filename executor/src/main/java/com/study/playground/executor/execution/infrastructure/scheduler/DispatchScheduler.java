package com.study.playground.executor.execution.infrastructure.scheduler;

import com.study.playground.executor.execution.domain.port.in.EvaluateDispatchUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class DispatchScheduler {

    private final EvaluateDispatchUseCase evaluateDispatchUseCase;

    @Scheduled(fixedDelayString = "${executor.dispatch-interval-ms:3000}")
    public void scheduledDispatch() {
        evaluateDispatchUseCase.tryDispatch();
    }
}
