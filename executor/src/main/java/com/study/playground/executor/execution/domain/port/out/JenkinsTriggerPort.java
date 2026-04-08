package com.study.playground.executor.execution.domain.port.out;

/**
 * Jenkins 빌드 트리거 아웃바운드 포트.
 */
public interface JenkinsTriggerPort {

    void triggerBuild(long jenkinsInstanceId, String jenkinsJobPath, String jobId);
}
