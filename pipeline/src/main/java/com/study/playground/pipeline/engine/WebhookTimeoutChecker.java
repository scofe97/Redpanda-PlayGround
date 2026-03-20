package com.study.playground.pipeline.engine;

import com.study.playground.kafka.tracing.TraceContextUtil;
import com.study.playground.pipeline.domain.PipelineJobExecution;
import com.study.playground.pipeline.domain.PipelineJobType;
import com.study.playground.pipeline.domain.PipelineStatus;
import com.study.playground.pipeline.domain.JobExecutionStatus;
import com.study.playground.pipeline.event.PipelineEventProducer;
import com.study.playground.pipeline.mapper.PipelineExecutionMapper;
import com.study.playground.pipeline.mapper.PipelineJobExecutionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * WAITING_WEBHOOK 상태에서 일정 시간 이상 응답이 없는 Job을 감지하고 실패 처리한다.
 *
 * <p>Break-and-Resume 패턴에서는 외부 시스템(Jenkins)이 webhook을 보내지 않으면
 * 파이프라인이 영원히 대기 상태에 머문다. 이를 방지하기 위해 30초마다 폴링하여
 * {@value #TIMEOUT_MINUTES}분 이상 대기 중인 Job을 타임아웃 처리한다.
 *
 * <p>타임아웃 감지 후 상태 전환은 CAS(Compare-And-Swap) 방식을 사용한다.
 * {@link PipelineEngine#resumeAfterWebhook}과 이 체커가 동시에 실행될 때,
 * 먼저 상태를 변경한 쪽만 유효하고 나머지는 조용히 무시된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebhookTimeoutChecker {

    /** webhook 응답 대기 허용 시간(분). 이 시간을 초과하면 타임아웃으로 간주한다. */
    private static final int TIMEOUT_MINUTES = 5;

    private final PipelineJobExecutionMapper jobExecutionMapper;
    private final PipelineExecutionMapper executionMapper;
    private final PipelineEventProducer eventProducer;
    private final SagaCompensator sagaCompensator;
    private final Map<PipelineJobType, PipelineJobExecutor> jobExecutors;
    private final DagExecutionCoordinator dagCoordinator;

    /**
     * 30초마다 실행되어 타임아웃된 webhook 대기 Job을 처리한다.
     *
     * <p>처리 순서: 타임아웃 Job 조회 → CAS로 FAILED 전환 → SAGA 보상 → 실행 FAILED 처리.
     * CAS 결과가 0이면 webhook 콜백이 이미 도착하여 상태가 변경된 것이므로 건너뛴다.
     */
    @Scheduled(fixedDelay = 30000)
    public void checkTimeouts() {
        var timedOutJobs = jobExecutionMapper.findWaitingWebhookOlderThan(TIMEOUT_MINUTES);
        if (timedOutJobs.isEmpty()) {
            return;
        }

        for (var je : timedOutJobs) {
            log.warn("Webhook timeout detected: executionId={}, job={}, jobOrder={}",
                    je.getExecutionId(), je.getJobName(), je.getJobOrder());

            String errorMsg = String.format("Webhook timeout: no callback received within %d minutes", TIMEOUT_MINUTES);

            // CAS: WAITING_WEBHOOK → FAILED (webhook 콜백과의 경쟁 조건을 방지하기 위해 낙관적 잠금 사용)
            int affected = jobExecutionMapper.updateStatusIfCurrent(
                    je.getId(),
                    JobExecutionStatus.WAITING_WEBHOOK.name(),
                    JobExecutionStatus.FAILED.name(),
                    errorMsg,
                    LocalDateTime.now());
            if (affected == 0) {
                log.info("Timeout checker: job already processed by webhook callback: executionId={}, jobOrder={}",
                        je.getExecutionId(), je.getJobOrder());
                continue;
            }

            var execution = executionMapper.findById(je.getExecutionId());
            if (execution != null) {
                // 원래 trace에 연결하여 timeout 스팬을 생성한다
                final String timeoutErrorMsg = errorMsg;
                TraceContextUtil.executeWithRestoredTrace(
                        execution.getTraceParent()
                        , "WebhookTimeoutChecker.timeout"
                        , Map.of("pipeline.execution.id", execution.getId().toString()
                                , "timeout.minutes", String.valueOf(TIMEOUT_MINUTES))
                        , () -> {
                            // DAG 모드: coordinator에 실패 통지
                            if (dagCoordinator.isManaged(je.getExecutionId())) {
                                Long jobId = dagCoordinator.findJobIdByJobOrder(
                                        je.getExecutionId(), je.getJobOrder());
                                if (jobId != null) {
                                    eventProducer.publishJobExecutionChanged(execution, je, JobExecutionStatus.FAILED);
                                    dagCoordinator.onJobFailed(je.getExecutionId(), je.getJobOrder(), jobId);
                                }
                                return;
                            }

                            // 기존 순차 모드
                            execution.setJobExecutions(jobExecutionMapper.findByExecutionId(je.getExecutionId()));
                            eventProducer.publishJobExecutionChanged(execution, je, JobExecutionStatus.FAILED);

                            sagaCompensator.compensate(execution, je.getJobOrder(), jobExecutors);

                            executionMapper.updateStatus(
                                    je.getExecutionId(),
                                    PipelineStatus.FAILED.name(),
                                    LocalDateTime.now(),
                                    timeoutErrorMsg);
                            eventProducer.publishExecutionCompleted(
                                    execution,
                                    com.study.playground.avro.common.PipelineStatus.FAILED,
                                    0,
                                    timeoutErrorMsg);
                        }
                );
            }
        }

        if (!timedOutJobs.isEmpty()) {
            log.info("Webhook timeout checker: {} job(s) timed out", timedOutJobs.size());
        }
    }
}
