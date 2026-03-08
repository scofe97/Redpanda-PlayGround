package com.study.playground.pipeline.engine;

import com.study.playground.pipeline.domain.PipelineStep;
import com.study.playground.pipeline.domain.PipelineStatus;
import com.study.playground.pipeline.domain.StepStatus;
import com.study.playground.pipeline.event.PipelineEventProducer;
import com.study.playground.pipeline.mapper.PipelineExecutionMapper;
import com.study.playground.pipeline.mapper.PipelineStepMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class WebhookTimeoutChecker {

    private static final int TIMEOUT_MINUTES = 5;

    private final PipelineStepMapper stepMapper;
    private final PipelineExecutionMapper executionMapper;
    private final PipelineEventProducer eventProducer;

    @Scheduled(fixedDelay = 30000)
    public void checkTimeouts() {
        List<PipelineStep> timedOutSteps = stepMapper.findWaitingWebhookStepsOlderThan(TIMEOUT_MINUTES);

        for (PipelineStep step : timedOutSteps) {
            log.warn("Webhook timeout detected: executionId={}, step={}, stepOrder={}",
                    step.getExecutionId(), step.getStepName(), step.getStepOrder());

            String errorMsg = String.format("Webhook timeout: no callback received within %d minutes", TIMEOUT_MINUTES);

            // CAS: WAITING_WEBHOOK → FAILED (webhook 콜백과의 경쟁 조건 방지)
            int affected = stepMapper.updateStatusIfCurrent(
                    step.getId(), StepStatus.WAITING_WEBHOOK.name(), StepStatus.FAILED.name(),
                    errorMsg, LocalDateTime.now());
            if (affected == 0) {
                log.info("Timeout checker: step already processed by webhook callback: executionId={}, stepOrder={}",
                        step.getExecutionId(), step.getStepOrder());
                continue;
            }

            var execution = executionMapper.findById(step.getExecutionId());
            if (execution != null) {
                execution.setSteps(stepMapper.findByExecutionId(step.getExecutionId()));
                eventProducer.publishStepChanged(execution, step, StepStatus.FAILED);

                executionMapper.updateStatus(step.getExecutionId(), PipelineStatus.FAILED.name(),
                        LocalDateTime.now(), errorMsg);
                eventProducer.publishExecutionCompleted(execution,
                        com.study.playground.avro.common.PipelineStatus.FAILED, 0, errorMsg);
            }
        }

        if (!timedOutSteps.isEmpty()) {
            log.info("Webhook timeout checker: {} step(s) timed out", timedOutSteps.size());
        }
    }
}
