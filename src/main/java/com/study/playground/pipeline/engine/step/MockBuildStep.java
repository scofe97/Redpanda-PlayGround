package com.study.playground.pipeline.engine.step;

import com.study.playground.pipeline.domain.PipelineStep;
import com.study.playground.pipeline.domain.PipelineStepType;
import com.study.playground.pipeline.engine.PipelineStepExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class MockBuildStep implements PipelineStepExecutor {

    @Override
    public void execute(PipelineStep step) throws Exception {
        log.info("[Mock] Build 시작: {}", step.getStepName());
        Thread.sleep(3000);
        step.setLog("Building project... compiled 42 classes (mock)");
        log.info("[Mock] Build 완료");
    }

    public PipelineStepType getType() {
        return PipelineStepType.BUILD;
    }
}
