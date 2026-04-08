package com.study.playground.operator.pipeline.pipeline.domain.port.in;

public interface DeletePipelineUseCase {

    /** 파이프라인을 논리 삭제한다. */
    void delete(String pipelineId);
}
