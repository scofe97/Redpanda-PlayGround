package com.study.playground.pipeline.engine.step;

import com.study.playground.pipeline.domain.PipelineExecution;
import com.study.playground.pipeline.domain.PipelineJobExecution;
import com.study.playground.pipeline.engine.PipelineJobExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 컨테이너 레지스트리에서 이미지 존재를 확인하는 스텝 (미구현 스텁).
 *
 * <p>TODO: RegistryAdapter를 통한 실제 이미지 존재 확인 구현.
 * 레지스트리 API 조회는 즉시 응답하므로 동기 방식이 적합하다.
 */
@Slf4j
@Component
public class RegistryImagePullStep implements PipelineJobExecutor {

    @Override
    public void execute(PipelineExecution execution, PipelineJobExecution jobExecution) throws Exception {
        log.info("[Stub] RegistryImagePull: {}", jobExecution.getJobName());
        jobExecution.setLog("RegistryImagePull stub — not implemented yet");
    }

    @Override
    public void compensate(PipelineExecution execution, PipelineJobExecution jobExecution) throws Exception {
        log.info("[SAGA COMPENSATE] Rolling back image pull: jobName={}, executionId={}",
                jobExecution.getJobName(), execution.getId());
    }
}
