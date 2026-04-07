package com.study.playground.executor.runner.application;

import com.study.playground.executor.config.ExecutorProperties;
import com.study.playground.executor.dispatch.domain.model.ExecutionJob;
import com.study.playground.executor.dispatch.domain.model.ExecutionJobStatus;
import com.study.playground.executor.dispatch.domain.port.out.ExecutionJobPort;
import com.study.playground.executor.dispatch.domain.port.out.JobDefinitionQueryPort;
import com.study.playground.executor.dispatch.domain.service.DispatchService;
import com.study.playground.executor.runner.infrastructure.jenkins.JenkinsClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CMD_JOB_EXECUTE 수신 처리.
 * Jenkins API로 빌드를 트리거한다. BUILD_NO 채번은 STARTED 콜백에서 수행한다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class JobExecuteService {

    private final ExecutionJobPort jobPort;
    private final JenkinsClient jenkinsClient;
    private final JobDefinitionQueryPort jobDefinitionQueryPort;
    private final DispatchService dispatchService;
    private final ExecutorProperties properties;

    @Transactional
    public void execute(String jobExcnId) {
        ExecutionJob job = jobPort.findById(jobExcnId).orElse(null);

        if (job == null) {
            log.warn("[JobExecute] Unknown jobExcnId={}", jobExcnId);
            return;
        }

        if (job.getStatus() != ExecutionJobStatus.QUEUED) {
            log.debug("[JobExecute] Not QUEUED: jobExcnId={}, status={}"
                    , jobExcnId, job.getStatus());
            return;
        }

        try {
            var defInfo = jobDefinitionQueryPort.load(job.getJobId());
            long jenkinsInstanceId = defInfo.jenkinsInstanceId();
            var jenkinsJobPath = defInfo.jobName();

            jenkinsClient.triggerBuild(jenkinsInstanceId, jenkinsJobPath, job.getJobId());

            log.info("[JobExecute] Build triggered: jobExcnId={}, path={}"
                    , jobExcnId, jenkinsJobPath);
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
