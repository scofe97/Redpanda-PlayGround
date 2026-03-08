package com.study.playground.pipeline.engine.step;

import com.study.playground.pipeline.domain.PipelineStep;
import com.study.playground.pipeline.domain.PipelineStepType;
import com.study.playground.pipeline.engine.PipelineStepExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class MockDeployStep implements PipelineStepExecutor {

    @Override
    public void execute(PipelineStep step) throws Exception {
        log.info("[Mock] Deploy 시작: {}", step.getStepName());

        String stepName = step.getStepName();

        // Demo: [SLOW] marker - simulate slow deployment for SSE real-time demo
        if (stepName != null && stepName.contains("[SLOW]")) {
            log.info("[Mock] SLOW 모드 감지 - 10초 대기");
            Thread.sleep(10000);
        } else {
            Thread.sleep(3000);
        }

        // Demo: [FAIL] marker - simulate deployment failure for DLQ demo
        if (stepName != null && stepName.contains("[FAIL]")) {
            log.error("[Mock] FAIL 마커 감지 - 의도적 실패 발생");
            throw new RuntimeException("Deployment failed: connection refused to target server (demo failure)");
        }

        step.setLog("Deploying to target server... done (mock)");
        log.info("[Mock] Deploy 완료");
    }

    public PipelineStepType getType() {
        return PipelineStepType.DEPLOY;
    }
}
