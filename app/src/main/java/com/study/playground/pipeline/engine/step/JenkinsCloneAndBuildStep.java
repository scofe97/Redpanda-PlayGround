package com.study.playground.pipeline.engine.step;

import com.study.playground.adapter.JenkinsAdapter;
import com.study.playground.pipeline.domain.PipelineExecution;
import com.study.playground.pipeline.domain.PipelineStep;
import com.study.playground.pipeline.engine.PipelineStepExecutor;
import com.study.playground.pipeline.event.PipelineCommandProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
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
        throw new RuntimeException("JenkinsCloneAndBuildStep requires PipelineExecution context");
    }

    @Override
    public void execute(PipelineExecution execution, PipelineStep step) throws Exception {
        log.info("[Real] JenkinsCloneAndBuild 시작: {}", step.getStepName());

        String stepName = step.getStepName();

        // 데모: [FAIL] 마커 - SAGA 데모를 위한 clone/build 실패 시뮬레이션
        if (stepName != null && stepName.contains("[FAIL]")) {
            log.error("[Real] FAIL 마커 감지 - 의도적 실패 발생: {}", stepName);
            Thread.sleep(2000);
            throw new RuntimeException("Git clone/build failed: authentication error (demo failure)");
        }

        if (!jenkinsAdapter.isAvailable()) {
            throw new RuntimeException("Jenkins 연결 불가: " + step.getStepName());
        }

        String jobName = DEFAULT_JOB;

        // 스텝 이름에서 GIT_URL, BRANCH 파싱: "Clone: http://localhost:29180/root/repo#main"
        Map<String, String> params = new HashMap<>();
        params.put("EXECUTION_ID", step.getExecutionId().toString());
        params.put("STEP_ORDER", String.valueOf(step.getStepOrder()));

        if (stepName != null && stepName.contains(":")) {
            String raw = stepName.substring(stepName.indexOf(':') + 1).trim()
                    .replace("[FAIL]", "").trim();
            String gitUrl = raw.contains("#") ? raw.substring(0, raw.lastIndexOf('#')) : raw;
            String branch = raw.contains("#") ? raw.substring(raw.lastIndexOf('#') + 1) : "main";

            // Docker 네트워크 내부 URL 변환
            String internalUrl = gitUrl.replace("localhost:29180", "gitlab:29180");

            params.put("GIT_URL", internalUrl);
            params.put("BRANCH", branch);
            log.info("[Real] Git 파라미터: URL={}, branch={}", internalUrl, branch);
        }

        commandProducer.publishJenkinsBuildCommand(execution, step, jobName, params);
        log.info("[Real] Jenkins 빌드 커맨드 발행 완료 (via Kafka): job={}", jobName);
        step.setWaitingForWebhook(true);
    }

    @Override
    public void compensate(PipelineExecution execution, PipelineStep step) throws Exception {
        log.info("[SAGA COMPENSATE] Rolling back {}: stepName={}, executionId={}",
                "Jenkins workspace",
                step.getStepName(), execution.getId());
        // 프로덕션: Jenkins 작업을 트리거하여 워크스페이스 정리 및 VCS 상태 복원
        // 예: jenkinsAdapter.triggerCleanupJob(execution, step)
    }


}
