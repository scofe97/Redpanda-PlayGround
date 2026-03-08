package com.study.playground.pipeline.engine;

import com.study.playground.pipeline.domain.PipelineExecution;
import com.study.playground.pipeline.domain.PipelineStep;

public interface PipelineStepExecutor {
    void execute(PipelineStep step) throws Exception;

    default void execute(PipelineExecution execution, PipelineStep step) throws Exception {
        execute(step);
    }

    /**
     * 이전에 성공한 스텝의 효과를 보상(되돌리기)한다.
     * SAGA 롤백 시 역순으로 호출된다.
     * 기본 구현은 no-op (읽기 전용이거나 멱등한 스텝에 안전).
     */
    default void compensate(PipelineExecution execution, PipelineStep step) throws Exception {
        // 기본값은 no-op — 부수효과를 되돌려야 하는 스텝에서 오버라이드
    }
}
