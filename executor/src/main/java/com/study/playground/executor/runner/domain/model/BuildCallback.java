package com.study.playground.executor.runner.domain.model;

/**
 * Jenkins webhook-listener.groovy가 rpk produce로 전달하는 콜백 데이터.
 * executionJobId로 ExecutionJob을 직접 조회한다.
 */
public record BuildCallback(
        String jobExcnId
        , int buildNumber
        , String result
        , String logContent
) {
    public static BuildCallback started(String jobExcnId, int buildNumber) {
        return new BuildCallback(jobExcnId, buildNumber, "STARTED", null);
    }

    public static BuildCallback completed(String jobExcnId, int buildNumber, String result) {
        return new BuildCallback(jobExcnId, buildNumber, result, null);
    }
}
