package com.study.playground.executor.runner.domain.model;

/**
 * Jenkins webhook-listener.groovy가 rpk produce로 전달하는 콜백 데이터.
 * jobId + buildNumber로 ExecutionJob을 조회한다.
 */
public record BuildCallback(
        String jobId
        , int buildNumber
        , String result
        , String logContent
) {
    public static BuildCallback started(String jobId, int buildNumber) {
        return new BuildCallback(jobId, buildNumber, "STARTED", null);
    }

    public static BuildCallback completed(String jobId, int buildNumber, String result, String logContent) {
        return new BuildCallback(jobId, buildNumber, result, logContent);
    }
}
