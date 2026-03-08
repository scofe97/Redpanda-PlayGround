package com.study.playground.pipeline.engine.step;

import com.study.playground.pipeline.domain.PipelineStep;
import com.study.playground.pipeline.domain.PipelineStepType;
import com.study.playground.pipeline.engine.PipelineStepExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class MockGitCloneStep implements PipelineStepExecutor {

    @Override
    public void execute(PipelineStep step) throws Exception {
        log.info("[Mock] Git Clone 시작: {}", step.getStepName());
        Thread.sleep(2000);
        step.setLog("Cloning repository... done (mock)");
        log.info("[Mock] Git Clone 완료");
    }

    public PipelineStepType getType() {
        return PipelineStepType.GIT_CLONE;
    }
}
