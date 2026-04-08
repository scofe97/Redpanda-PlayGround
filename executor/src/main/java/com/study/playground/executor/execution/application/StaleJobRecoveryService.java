package com.study.playground.executor.execution.application;

import com.study.playground.executor.config.ExecutorProperties;
import com.study.playground.executor.execution.domain.model.BuildStatusResult;
import com.study.playground.executor.execution.domain.model.ExecutionJob;
import com.study.playground.executor.execution.domain.model.ExecutionJobStatus;
import com.study.playground.executor.execution.domain.port.out.ExecutionJobPort;
import com.study.playground.executor.execution.domain.port.out.JenkinsQueryPort;
import com.study.playground.executor.execution.domain.port.out.JobDefinitionQueryPort;
import com.study.playground.executor.execution.domain.port.out.NotifyJobCompletedPort;
import com.study.playground.executor.execution.domain.port.out.NotifyJobStartedPort;
import com.study.playground.executor.execution.domain.service.DispatchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Slf4j
public class StaleJobRecoveryService {

    private final ExecutionJobPort jobPort;
    private final JenkinsQueryPort jenkinsQueryPort;
    private final JobDefinitionQueryPort jobDefinitionQueryPort;
    private final DispatchService dispatchService;
    private final NotifyJobCompletedPort notifyCompletedPort;
    private final NotifyJobStartedPort notifyStartedPort;
    private final ExecutorProperties properties;

    /**
     * SUBMITTED 상태에서 submittedStaleSeconds 이상 체류한 Job을 방어한다.
     * Jenkins API로 빌드 상태를 직접 확인하여 적절한 상태로 전이한다.
     */
    @Transactional
    public void recoverStaleSubmitted() {
        var cutoff = LocalDateTime.now().minusSeconds(properties.getSubmittedStaleSeconds());
        var staleJobs = jobPort.findByStatusAndMdfcnDtBefore(ExecutionJobStatus.SUBMITTED, cutoff);

        for (ExecutionJob job : staleJobs) {
            try {
                recoverSubmitted(job);
            } catch (Exception e) {
                log.error("[StaleRecovery] SUBMITTED recovery failed: jobExcnId={}"
                        , job.getJobExcnId(), e);
            }
        }
    }

    /**
     * RUNNING 상태에서 runningStaleMinutes 이상 체류한 Job을 방어한다.
     * Jenkins API로 빌드가 여전히 실행 중인지 확인한다.
     */
    @Transactional
    public void recoverStaleRunning() {
        var cutoff = LocalDateTime.now().minusMinutes(properties.getRunningStaleMinutes());
        var staleJobs = jobPort.findByStatusAndBgngDtBefore(ExecutionJobStatus.RUNNING, cutoff);

        for (ExecutionJob job : staleJobs) {
            try {
                recoverRunning(job);
            } catch (Exception e) {
                log.error("[StaleRecovery] RUNNING recovery failed: jobExcnId={}"
                        , job.getJobExcnId(), e);
            }
        }
    }

    /**
     * QUEUED 상태에서 queuedStaleSeconds 이상 체류한 Job을 방어한다.
     * execute 메시지가 유실된 경우 PENDING으로 복귀시키거나 FAILURE로 전환한다.
     */
    @Transactional
    public void recoverStaleQueued() {
        var cutoff = LocalDateTime.now().minusSeconds(properties.getQueuedStaleSeconds());
        var staleJobs = jobPort.findByStatusAndMdfcnDtBefore(ExecutionJobStatus.QUEUED, cutoff);

        for (ExecutionJob job : staleJobs) {
            log.warn("[StaleRecovery] QUEUED stale detected: jobExcnId={}, lastModified={}"
                    , job.getJobExcnId(), job.getMdfcnDt());
            dispatchService.retryOrFail(job, properties.getJobMaxRetries());
            jobPort.save(job);
        }
    }

    private void recoverSubmitted(ExecutionJob job) {
        var defInfo = jobDefinitionQueryPort.load(job.getJobId());
        var buildStatus = jenkinsQueryPort.queryBuildStatus(
                defInfo.jenkinsInstanceId(), defInfo.jenkinsJobPath(), job.getBuildNo());

        switch (buildStatus.phase()) {
            case NOT_FOUND -> {
                // 아직 큐 대기 중이거나 빌드를 찾을 수 없음
                // submittedStaleSeconds * 3 이상이면 retryOrFail
                var longCutoff = LocalDateTime.now().minusSeconds(
                        (long) properties.getSubmittedStaleSeconds() * 3);
                if (job.getMdfcnDt().isBefore(longCutoff)) {
                    log.warn("[StaleRecovery] SUBMITTED too long, retryOrFail: jobExcnId={}"
                            , job.getJobExcnId());
                    dispatchService.retryOrFail(job, properties.getJobMaxRetries());
                    jobPort.save(job);
                } else {
                    log.debug("[StaleRecovery] SUBMITTED still in queue: jobExcnId={}"
                            , job.getJobExcnId());
                }
            }
            case BUILDING -> {
                // 실행 중인데 시작 웹훅 유실 → RUNNING 전이
                log.info("[StaleRecovery] SUBMITTED→RUNNING (webhook missed): jobExcnId={}"
                        , job.getJobExcnId());
                dispatchService.markAsRunning(job, job.getBuildNo());
                jobPort.save(job);
                notifyStartedPort.notify(
                        job.getJobExcnId()
                        , job.getPipelineExcnId()
                        , job.getJobId()
                        , job.getBuildNo()
                );
            }
            case COMPLETED -> {
                // 이미 완료 — 터미널 상태로 전이
                log.info("[StaleRecovery] SUBMITTED→{} (webhooks missed): jobExcnId={}"
                        , buildStatus.result(), job.getJobExcnId());
                dispatchService.markAsCompleted(job, buildStatus.result());
                jobPort.save(job);
                var status = ExecutionJobStatus.fromJenkinsResult(buildStatus.result());
                boolean success = status == ExecutionJobStatus.SUCCESS;
                notifyCompletedPort.notify(
                        job.getJobExcnId()
                        , job.getPipelineExcnId()
                        , success
                        , buildStatus.result()
                        , null
                        , "N"
                        , success ? null : buildStatus.result()
                );
            }
        }
    }

    private void recoverRunning(ExecutionJob job) {
        var defInfo = jobDefinitionQueryPort.load(job.getJobId());
        var buildStatus = jenkinsQueryPort.queryBuildStatus(
                defInfo.jenkinsInstanceId(), defInfo.jenkinsJobPath(), job.getBuildNo());

        switch (buildStatus.phase()) {
            case BUILDING -> {
                // 여전히 실행 중 — 다음 주기까지 대기
                log.info("[StaleRecovery] Still running: jobExcnId={}", job.getJobExcnId());
            }
            case COMPLETED -> {
                // 완료되었으나 웹훅 유실
                log.info("[StaleRecovery] RUNNING→{} (webhook missed): jobExcnId={}"
                        , buildStatus.result(), job.getJobExcnId());
                dispatchService.markAsCompleted(job, buildStatus.result());
                jobPort.save(job);
                var status = ExecutionJobStatus.fromJenkinsResult(buildStatus.result());
                boolean success = status == ExecutionJobStatus.SUCCESS;
                notifyCompletedPort.notify(
                        job.getJobExcnId()
                        , job.getPipelineExcnId()
                        , success
                        , buildStatus.result()
                        , null
                        , "N"
                        , success ? null : buildStatus.result()
                );
            }
            case NOT_FOUND -> {
                // 빌드 정보 없음 — retryOrFail
                log.warn("[StaleRecovery] RUNNING but build not found, retryOrFail: jobExcnId={}"
                        , job.getJobExcnId());
                dispatchService.retryOrFail(job, properties.getJobMaxRetries());
                jobPort.save(job);
            }
        }
    }
}
