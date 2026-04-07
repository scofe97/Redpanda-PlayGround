package com.study.playground.executor.dispatch.domain.port.in;

/**
 * Jenkins 빌드 트리거 유스케이스.
 */
public interface ExecuteJobUseCase {

    void execute(String jobExcnId);
}
