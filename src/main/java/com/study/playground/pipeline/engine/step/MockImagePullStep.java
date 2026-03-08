package com.study.playground.pipeline.engine.step;

import com.study.playground.pipeline.domain.PipelineStep;
import com.study.playground.pipeline.domain.PipelineStepType;
import com.study.playground.pipeline.engine.PipelineStepExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class MockImagePullStep implements PipelineStepExecutor {

    @Override
    public void execute(PipelineStep step) throws Exception {
        log.info("[Mock] Image Pull 시작: {}", step.getStepName());
        Thread.sleep(2500);
        step.setLog("Pulling docker image... done (mock)");
        log.info("[Mock] Image Pull 완료");
    }

    public PipelineStepType getType() {
        return PipelineStepType.IMAGE_PULL;
    }
}
