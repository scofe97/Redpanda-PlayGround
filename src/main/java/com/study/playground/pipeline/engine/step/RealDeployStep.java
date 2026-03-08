package com.study.playground.pipeline.engine.step;

import com.study.playground.adapter.JenkinsAdapter;
import com.study.playground.pipeline.domain.PipelineExecution;
import com.study.playground.pipeline.domain.PipelineStep;
import com.study.playground.pipeline.engine.PipelineStepExecutor;
import com.study.playground.pipeline.event.PipelineCommandProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class RealDeployStep implements PipelineStepExecutor {

    private static final String DEFAULT_JOB = "playground-deploy";

    private final JenkinsAdapter jenkinsAdapter;
    private final PipelineCommandProducer commandProducer;

    @Override
    public void execute(PipelineStep step) throws Exception {
        // default 메서드에서 호출될 경우 (execution 없이) — Mock 폴백
        log.warn("[Real] RealDeployStep.execute(step) called without execution - Mock 폴백");
        Thread.sleep(3000);
        step.setLog("Deploying to target server... done (fallback: no execution context)");
    }

    @Override
    public void execute(PipelineExecution execution, PipelineStep step) throws Exception {
        log.info("[Real] Deploy 시작: {}", step.getStepName());

        String stepName = step.getStepName();

        // Demo: [SLOW] marker - simulate slow deployment for SSE real-time demo
        if (stepName != null && stepName.contains("[SLOW]")) {
            log.info("[Real] SLOW 모드 감지 - 10초 대기");
            Thread.sleep(10000);
        }

        // Demo: [FAIL] marker - simulate deployment failure for DLQ demo
        if (stepName != null && stepName.contains("[FAIL]")) {
            log.error("[Real] FAIL 마커 감지 - 의도적 실패 발생");
            throw new RuntimeException("Deployment failed: connection refused to target server (demo failure)");
        }

        if (!jenkinsAdapter.isAvailable()) {
            log.warn("[Real] Jenkins 연결 불가 - Mock 폴백 실행");
            Thread.sleep(3000);
            step.setLog("Deploying to target server... done (fallback mock: Jenkins unavailable)");
            log.info("[Real] Deploy Mock 폴백 완료");
            return; // waitingForWebhook = false → 동기식 진행
        }

        String jobName = extractJobName(stepName, DEFAULT_JOB);

        Map<String, String> params = Map.of(
                "EXECUTION_ID", step.getExecutionId().toString(),
                "STEP_ORDER", String.valueOf(step.getStepOrder())
        );

        commandProducer.publishJenkinsBuildCommand(execution, step, jobName, params);
        log.info("[Real] Jenkins 배포 커맨드 발행 완료 (via Kafka): job={}", jobName);
        step.setWaitingForWebhook(true);
    }

    @Override
    public void compensate(PipelineExecution execution, PipelineStep step) throws Exception {
        log.info("[SAGA COMPENSATE] Rolling back {}: stepName={}, executionId={}",
                "deployment",
                step.getStepName(), execution.getId());
        // In production: call external system to reverse the deployment
        // e.g., trigger rollback Jenkins job or invoke undeploy API
    }

    private String extractJobName(String stepName, String defaultJob) {
        if (stepName != null && stepName.contains(":")) {
            String candidate = stepName.substring(stepName.indexOf(':') + 1).trim();
            candidate = candidate.replace("[SLOW]", "").replace("[FAIL]", "").trim();
            if (!candidate.isBlank()) {
                return candidate;
            }
        }
        return defaultJob;
    }
}
