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
public class RealDeployStep implements PipelineStepExecutor {

    private static final String DEFAULT_JOB = "playground-deploy";

    private final JenkinsAdapter jenkinsAdapter;
    private final PipelineCommandProducer commandProducer;

    @Override
    public void execute(PipelineStep step) throws Exception {
        throw new RuntimeException("RealDeployStep requires PipelineExecution context");
    }

    @Override
    public void execute(PipelineExecution execution, PipelineStep step) throws Exception {
        log.info("[Real] Deploy 시작: {}", step.getStepName());

        String stepName = step.getStepName();

        // 데모: [SLOW] 마커 - SSE 실시간 데모를 위한 느린 배포 시뮬레이션
        if (stepName != null && stepName.contains("[SLOW]")) {
            log.info("[Real] SLOW 모드 감지 - 10초 대기");
            Thread.sleep(10000);
        }

        // 데모: [FAIL] 마커 - DLQ 데모를 위한 배포 실패 시뮬레이션
        if (stepName != null && stepName.contains("[FAIL]")) {
            log.error("[Real] FAIL 마커 감지 - 의도적 실패 발생");
            throw new RuntimeException("Deployment failed: connection refused to target server (demo failure)");
        }

        if (!jenkinsAdapter.isAvailable()) {
            throw new RuntimeException("Jenkins 연결 불가: " + step.getStepName());
        }

        String jobName = DEFAULT_JOB;

        // 스텝 이름에서 배포 대상 정보 추출: "Deploy: egov-sample (main), my-image:latest"
        String deployTarget = "";
        if (stepName != null && stepName.contains("Deploy:")) {
            deployTarget = stepName.substring(stepName.indexOf("Deploy:") + 7).trim();
            deployTarget = deployTarget.replace("[FAIL]", "").replace("[SLOW]", "").trim();
        }

        Map<String, String> params = new HashMap<>();
        params.put("EXECUTION_ID", step.getExecutionId().toString());
        params.put("STEP_ORDER", String.valueOf(step.getStepOrder()));
        if (!deployTarget.isEmpty()) {
            params.put("DEPLOY_TARGET", deployTarget);
            log.info("[Real] Deploy 대상: {}", deployTarget);
        }

        commandProducer.publishJenkinsBuildCommand(execution, step, jobName, params);
        log.info("[Real] Jenkins 배포 커맨드 발행 완료 (via Kafka): job={}", jobName);
        step.setWaitingForWebhook(true);
    }

    @Override
    public void compensate(PipelineExecution execution, PipelineStep step) throws Exception {
        log.info("[SAGA COMPENSATE] Rolling back {}: stepName={}, executionId={}",
                "deployment",
                step.getStepName(), execution.getId());
        // 프로덕션: 외부 시스템을 호출하여 배포 롤백
        // 예: 롤백 Jenkins 작업 트리거 또는 undeploy API 호출
    }

}
