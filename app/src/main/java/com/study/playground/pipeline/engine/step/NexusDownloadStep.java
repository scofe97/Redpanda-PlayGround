package com.study.playground.pipeline.engine.step;

import com.study.playground.pipeline.domain.PipelineExecution;
import com.study.playground.pipeline.domain.PipelineStep;
import com.study.playground.pipeline.engine.PipelineStepExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Nexus Repository에서 Maven 아티팩트를 다운로드하는 스텝 (미구현 스텁).
 *
 * <p>TODO: NexusAdapter를 통한 실제 아티팩트 검색/다운로드 구현.
 * 대용량 아티팩트는 수 분이 걸릴 수 있으므로, 구현 시 동기 방식 대신
 * Jenkins처럼 비동기(Break-and-Resume) 전환을 검토해야 한다.
 */
@Slf4j
@Component
public class NexusDownloadStep implements PipelineStepExecutor {

    @Override
    public void execute(PipelineExecution execution, PipelineStep step) throws Exception {
        log.info("[Stub] NexusDownload: {}", step.getStepName());

        if (step.getStepName() != null && step.getStepName().contains("[FAIL]")) {
            throw new RuntimeException("Artifact download failed (demo failure)");
        }

        step.setLog("NexusDownload stub — not implemented yet");
    }

    @Override
    public void compensate(PipelineExecution execution, PipelineStep step) throws Exception {
        log.info("[SAGA COMPENSATE] Rolling back artifact download: stepName={}, executionId={}",
                step.getStepName(), execution.getId());
    }
}
