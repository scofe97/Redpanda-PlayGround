package com.study.playground.executor.dispatch.domain.model;

/**
 * pipeline_job + purpose + support_tool 조인 결과.
 * Executor가 jobId로 조회하여 Jenkins 접속 정보와 경로를 확인한다.
 */
public record JobDefinitionInfo(
        String jobId
        , long projectId
        , long purposeId
        , long jenkinsInstanceId
        , String jobName
) {
    /**
     * Jenkins 파이프라인 경로: projectId/purposeId/jobId
     */
    public String jenkinsJobPath() {
        return projectId + "/" + purposeId + "/" + jobId;
    }
}
