package com.study.playground.operator.pipeline.job.domain.port.in;

public interface DeleteJobUseCase {

    /**
     * Job을 논리 삭제한다. 잠금 상태이면 삭제 불가.
     */
    void delete(String jobId);
}
