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

/**
 * SAGA 패턴의 보상 트랜잭션을 담당한다.
 *
 * <p>파이프라인은 분산 환경에서 여러 외부 시스템(Jenkins, Nexus, Registry)을 순서대로
 * 호출한다. 중간 스텝이 실패하면 이미 완료된 스텝들의 부수효과를 되돌려야 한다.
 * 2PC(2단계 커밋) 없이 이를 구현하는 방법이 SAGA 보상 트랜잭션이다.
 *
 * <p>보상 순서가 실행 역순인 이유는 의존성 때문이다. 예를 들어 BUILD가 GIT_CLONE에
 * 의존하므로, 롤백 시 BUILD를 먼저 되돌린 뒤 GIT_CLONE을 되돌려야 일관성이 유지된다.
 *
 * <p>보상 자체가 실패하면 COMPENSATION_FAILED로 기록하고 로그를 남긴다. 이 상태는
 * 자동 복구가 불가능하므로 운영자의 수동 개입이 필요하다.
 */
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
     * <p>반복 시작 인덱스를 {@code failedStepOrder - 2}로 설정하는 이유:
     * stepOrder는 1-based이므로 실패 스텝 바로 이전 스텝의 인덱스(0-based)는
     * {@code failedStepOrder - 1 - 1 = failedStepOrder - 2}이다.
     * 실패한 스텝 자체는 보상 대상이 아니다.
     *
     * @param execution        파이프라인 실행 정보
     * @param failedStepOrder  실패한 스텝의 순서 (1-based)
     * @param stepExecutors    스텝 타입별 실행기 맵 (PipelineEngine이 제공)
     */
    public void compensate(
            PipelineExecution execution,
            int failedStepOrder,
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
                stepMapper.updateStatus(
                        step.getId(),
                        StepStatus.COMPENSATED.name(),
                        "Compensated after saga rollback",
                        LocalDateTime.now());
                eventProducer.publishStepChanged(execution, step, StepStatus.COMPENSATED);
                log.info("[SAGA] Compensated step: {} (order={})", step.getStepName(), step.getStepOrder());
            } catch (Exception ce) {
                allCompensated = false;
                log.error("[SAGA] Compensation FAILED for step: {} (order={}) - MANUAL INTERVENTION REQUIRED",
                        step.getStepName(), step.getStepOrder(), ce);
                stepMapper.updateStatus(
                        step.getId(),
                        StepStatus.FAILED.name(),
                        "COMPENSATION_FAILED: " + ce.getMessage(),
                        LocalDateTime.now());
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
