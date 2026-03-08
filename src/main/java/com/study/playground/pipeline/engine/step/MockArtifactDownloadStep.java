package com.study.playground.pipeline.engine.step;

import com.study.playground.pipeline.domain.PipelineStep;
import com.study.playground.pipeline.domain.PipelineStepType;
import com.study.playground.pipeline.engine.PipelineStepExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class MockArtifactDownloadStep implements PipelineStepExecutor {

    @Override
    public void execute(PipelineStep step) throws Exception {
        log.info("[Mock] Artifact Download 시작: {}", step.getStepName());
        Thread.sleep(2000);
        step.setLog("Downloading artifact from Nexus... done (mock)");
        log.info("[Mock] Artifact Download 완료");
    }

    public PipelineStepType getType() {
        return PipelineStepType.ARTIFACT_DOWNLOAD;
    }
}
