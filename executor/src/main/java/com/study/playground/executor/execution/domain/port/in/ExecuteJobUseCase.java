package com.study.playground.executor.execution.domain.port.in;

/**
 * Jenkins 빌드 트리거 유스케이스.
 */
public interface ExecuteJobUseCase {

    void execute(String jobExcnId);
}
