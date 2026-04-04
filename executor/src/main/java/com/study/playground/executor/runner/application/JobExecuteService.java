package com.study.playground.executor.runner.application;

import com.study.playground.executor.config.ExecutorProperties;
import com.study.playground.executor.dispatch.domain.model.ExecutionJob;
import com.study.playground.executor.dispatch.domain.model.ExecutionJobStatus;
import com.study.playground.executor.dispatch.domain.port.out.ExecutionJobPort;
import com.study.playground.executor.dispatch.domain.service.DispatchService;
import com.study.playground.executor.runner.infrastructure.jenkins.JenkinsClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CMD_JOB_EXECUTE 수신 처리.
 * Jenkins API로 빌드를 트리거하고 BUILD_NO를 채번한다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class JobExecuteService {

    private final ExecutionJobPort jobPort;
    private final JenkinsClient jenkinsClient;
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
            // TODO: job → Jenkins 인스턴스 매핑 (Purpose → PurposeEntry → SupportTool)
            long jenkinsInstanceId = 1L;
            String jobName = job.getJobId();

            int buildNo = jenkinsClient.triggerBuild(jenkinsInstanceId, jobName, job.getJobExcnId());
            job.recordBuildNo(buildNo);
            jobPort.save(job);

            log.info("[JobExecute] Build triggered: jobExcnId={}, jobName={}, buildNo={}"
                    , jobExcnId, jobName, buildNo);
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
