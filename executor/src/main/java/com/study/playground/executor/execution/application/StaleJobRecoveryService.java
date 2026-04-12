package com.study.playground.executor.execution.application;

import com.study.playground.executor.config.ExecutorProperties;
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

    /** 시작 webhook이 유실된 SUBMITTED 작업을 Jenkins 상태 재조회로 복구한다. */
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

    /** 완료 webhook이 유실된 RUNNING 작업을 Jenkins 상태 재조회로 복구한다. */
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

    /** execute command가 유실된 QUEUED 작업을 retryOrFail로 되돌린다. */
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
        if (!jenkinsQueryPort.isHealthy(defInfo.jenkinsInstanceId())) {
            log.warn("[StaleRecovery] Skip SUBMITTED recovery for unhealthy Jenkins: instanceId={}, jobExcnId={}"
                    , defInfo.jenkinsInstanceId(), job.getJobExcnId());
            return;
        }

        var buildStatus = jenkinsQueryPort.queryBuildStatus(
                defInfo.jenkinsInstanceId(), defInfo.jenkinsJobPath(), job.getBuildNo());

        switch (buildStatus.phase()) {
            case NOT_FOUND -> {
                // Jenkins 큐 대기일 수 있으므로 submittedStaleSeconds * 3까지는 한 번 더 기다린다.
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
                // started webhook만 빠진 경우라서 RUNNING 전이와 started notify를 다시 보낸다.
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
                // started/completed webhook이 모두 빠진 경우를 방어한다.
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
        if (!jenkinsQueryPort.isHealthy(defInfo.jenkinsInstanceId())) {
            log.warn("[StaleRecovery] Skip RUNNING recovery for unhealthy Jenkins: instanceId={}, jobExcnId={}"
                    , defInfo.jenkinsInstanceId(), job.getJobExcnId());
            return;
        }

        var buildStatus = jenkinsQueryPort.queryBuildStatus(
                defInfo.jenkinsInstanceId(), defInfo.jenkinsJobPath(), job.getBuildNo());

        switch (buildStatus.phase()) {
            case BUILDING -> log.info("[StaleRecovery] Still running: jobExcnId={}", job.getJobExcnId());
            case COMPLETED -> {
                // completed webhook만 빠진 경우라서 terminal 상태와 completion notify를 복구한다.
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
                // Jenkins에도 흔적이 없으면 실행 경로가 끊긴 것으로 보고 retryOrFail 한다.
                log.warn("[StaleRecovery] RUNNING but build not found, retryOrFail: jobExcnId={}"
                        , job.getJobExcnId());
                dispatchService.retryOrFail(job, properties.getJobMaxRetries());
                jobPort.save(job);
            }
        }
    }
}
