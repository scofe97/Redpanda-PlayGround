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
public class JenkinsCloneAndBuildStep implements PipelineStepExecutor {

    private static final String DEFAULT_JOB = "playground-build";

    private final JenkinsAdapter jenkinsAdapter;
    private final PipelineCommandProducer commandProducer;

    @Override
    public void execute(PipelineStep step) throws Exception {
        // default 메서드에서 호출될 경우 (execution 없이) — Mock 폴백
        log.warn("[Real] JenkinsCloneAndBuildStep.execute(step) called without execution - Mock 폴백");
        Thread.sleep(3000);
        step.setLog("Cloning and building via Jenkins... done (fallback: no execution context)");
    }

    @Override
    public void execute(PipelineExecution execution, PipelineStep step) throws Exception {
        log.info("[Real] JenkinsCloneAndBuild 시작: {}", step.getStepName());

        String stepName = step.getStepName();

        // Demo: [FAIL] marker - simulate clone/build failure for SAGA demo
        if (stepName != null && stepName.contains("[FAIL]")) {
            log.error("[Real] FAIL 마커 감지 - 의도적 실패 발생: {}", stepName);
            Thread.sleep(2000);
            throw new RuntimeException("Git clone/build failed: authentication error (demo failure)");
        }

        if (!jenkinsAdapter.isAvailable()) {
            log.warn("[Real] Jenkins 연결 불가 - Mock 폴백 실행");
            Thread.sleep(3000);
            step.setLog("Cloning and building via Jenkins... done (fallback mock: Jenkins unavailable)");
            log.info("[Real] JenkinsCloneAndBuild Mock 폴백 완료");
            return; // waitingForWebhook = false → 동기식 진행
        }

        String jobName = DEFAULT_JOB;

        Map<String, String> params = Map.of(
                "EXECUTION_ID", step.getExecutionId().toString(),
                "STEP_ORDER", String.valueOf(step.getStepOrder())
        );

        commandProducer.publishJenkinsBuildCommand(execution, step, jobName, params);
        log.info("[Real] Jenkins 빌드 커맨드 발행 완료 (via Kafka): job={}", jobName);
        step.setWaitingForWebhook(true);
    }

    @Override
    public void compensate(PipelineExecution execution, PipelineStep step) throws Exception {
        log.info("[SAGA COMPENSATE] Rolling back {}: stepName={}, executionId={}",
                "Jenkins workspace",
                step.getStepName(), execution.getId());
        // In production: trigger Jenkins job to clean up workspace and revert VCS state
        // e.g., jenkinsAdapter.triggerCleanupJob(execution, step)
    }

    private String extractJobName(String stepName, String defaultJob) {
        if (stepName != null && stepName.contains(":")) {
            String candidate = stepName.substring(stepName.indexOf(':') + 1).trim();
            if (!candidate.isBlank()) {
                return candidate;
            }
        }
        return defaultJob;
    }
}
