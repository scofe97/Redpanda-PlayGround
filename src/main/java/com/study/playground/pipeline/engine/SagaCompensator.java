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
     * Compensates completed steps in reverse order after a step failure.
     * Each step's compensate() is called; if compensation itself fails,
     * the step is marked COMPENSATION_FAILED and logged for manual intervention.
     *
     * @param execution the pipeline execution
     * @param failedStepOrder the order of the step that failed (1-based)
     * @param stepExecutors map of step type to executor (provided by PipelineEngine)
     */
    public void compensate(PipelineExecution execution, int failedStepOrder,
                           Map<PipelineStepType, PipelineStepExecutor> stepExecutors) {
        List<PipelineStep> steps = execution.getSteps();

        log.warn("[SAGA] Starting compensation for execution={}, failedStep={}",
                execution.getId(), failedStepOrder);

        boolean allCompensated = true;

        // Iterate completed steps in reverse order (before the failed step)
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
                stepMapper.updateStatus(step.getId(), StepStatus.SKIPPED.name(),
                        "Compensated after saga rollback", LocalDateTime.now());
                eventProducer.publishStepChanged(execution, step, StepStatus.SKIPPED);
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
