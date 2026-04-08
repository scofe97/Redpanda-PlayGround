package com.study.playground.executor.execution.application;

import com.study.playground.executor.config.ExecutorProperties;
import com.study.playground.executor.execution.domain.model.ExecutionJob;
import com.study.playground.executor.execution.domain.model.ExecutionJobStatus;
import com.study.playground.executor.execution.domain.port.in.ExecuteJobUseCase;
import com.study.playground.executor.execution.domain.port.out.ExecutionJobPort;
import com.study.playground.executor.execution.domain.port.out.JenkinsQueryPort;
import com.study.playground.executor.execution.domain.port.out.JenkinsTriggerPort;
import com.study.playground.executor.execution.domain.port.out.JobDefinitionQueryPort;
import com.study.playground.executor.execution.domain.service.DispatchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CMD_JOB_EXECUTE 수신 처리.
 * Jenkins API로 빌드를 트리거하고 SUBMITTED 상태로 전환한다.
 * triggerBuild 성공 후 nextBuildNumber를 조회하여 DB에 기록한다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class JobExecuteService implements ExecuteJobUseCase {

    private final ExecutionJobPort jobPort;
    private final JenkinsTriggerPort jenkinsTriggerPort;
    private final JenkinsQueryPort jenkinsQueryPort;
    private final JobDefinitionQueryPort jobDefinitionQueryPort;
    private final DispatchService dispatchService;
    private final ExecutorProperties properties;

    @Override
    @Transactional
    public void execute(String jobExcnId) {
        ExecutionJob job = jobPort.findById(jobExcnId)
                .orElseThrow(() -> new IllegalStateException("Unknown jobExcnId=" + jobExcnId));

        if (job.getStatus() != ExecutionJobStatus.QUEUED) {
            log.debug("[JobExecute] Not QUEUED: jobExcnId={}, status={}"
                    , jobExcnId, job.getStatus());
            return;
        }

        try {
            var defInfo = jobDefinitionQueryPort.load(job.getJobId());
            long jenkinsInstanceId = defInfo.jenkinsInstanceId();
            var jenkinsJobPath = defInfo.jenkinsJobPath();

            int nextBuildNo = jenkinsQueryPort.queryNextBuildNumber(jenkinsInstanceId, jenkinsJobPath);
            jenkinsTriggerPort.triggerBuild(jenkinsInstanceId, jenkinsJobPath, job.getJobId());

            dispatchService.markAsSubmitted(job, nextBuildNo);
            jobPort.save(job);

            log.info("[JobExecute] Build triggered: jobExcnId={}, buildNo={}, path={}"
                    , jobExcnId, nextBuildNo, jenkinsJobPath);
        } catch (Exception e) {
            log.error("[JobExecute] Failed: jobExcnId={}, error={}"
                    , jobExcnId, e.getMessage());
            boolean retried = dispatchService.retryOrFail(job, properties.getJobMaxRetries());
            jobPort.save(job);
            if (retried) {
                log.warn("[JobExecute] Retry #{} for jobExcnId={}"
                        , job.getRetryCnt(), jobExcnId);
            }
        }
    }
}
