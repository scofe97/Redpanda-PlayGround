package com.study.playground.pipeline.engine;

import com.study.playground.pipeline.domain.*;
import com.study.playground.pipeline.event.PipelineEventProducer;
import com.study.playground.pipeline.mapper.PipelineStepMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class SagaCompensator {

    private final PipelineStepMapper stepMapper;
    private final PipelineEventProducer eventProducer;

    /**
     * 스텝 실패 후 완료된 스텝들을 역순으로 보상 처리한다.
     * 각 스텝의 compensate()를 호출하며, 보상 자체가 실패하면
     * COMPENSATION_FAILED로 표시하고 수동 개입이 필요하다고 로그를 남긴다.
     *
     * @param execution 파이프라인 실행 정보
     * @param failedStepOrder 실패한 스텝의 순서 (1-based)
     * @param stepExecutors 스텝 타입별 실행기 맵 (PipelineEngine이 제공)
     */
    public void compensate(PipelineExecution execution, int failedStepOrder,
                           Map<PipelineStepType, PipelineStepExecutor> stepExecutors) {
        List<PipelineStep> steps = execution.getSteps();

        log.warn("[SAGA] Starting compensation for execution={}, failedStep={}",
                execution.getId(), failedStepOrder);

        boolean allCompensated = true;

        // 실패한 스텝 이전의 완료된 스텝들을 역순으로 순회
        for (int i = failedStepOrder - 2; i >= 0; i--) {
            PipelineStep step = steps.get(i);
            if (step.getStatus() != StepStatus.SUCCESS) {
                continue;
            }

            try {
                log.info("[SAGA] Compensating step: {} (order={})", step.getStepName(), step.getStepOrder());
                PipelineStepExecutor executor = stepExecutors.get(step.getStepType());
                if (executor != null) {
                    executor.compensate(execution, step);
                }
                stepMapper.updateStatus(step.getId(), StepStatus.COMPENSATED.name(),
                        "Compensated after saga rollback", LocalDateTime.now());
                eventProducer.publishStepChanged(execution, step, StepStatus.COMPENSATED);
                log.info("[SAGA] Compensated step: {} (order={})", step.getStepName(), step.getStepOrder());
            } catch (Exception ce) {
                allCompensated = false;
                log.error("[SAGA] Compensation FAILED for step: {} (order={}) - MANUAL INTERVENTION REQUIRED",
                        step.getStepName(), step.getStepOrder(), ce);
                stepMapper.updateStatus(step.getId(), StepStatus.FAILED.name(),
                        "COMPENSATION_FAILED: " + ce.getMessage(), LocalDateTime.now());
            }
        }

        if (allCompensated) {
            log.info("[SAGA] All steps compensated successfully for execution={}", execution.getId());
        } else {
            log.error("[SAGA] Some compensations failed for execution={} - requires manual intervention",
                    execution.getId());
        }
    }
}
