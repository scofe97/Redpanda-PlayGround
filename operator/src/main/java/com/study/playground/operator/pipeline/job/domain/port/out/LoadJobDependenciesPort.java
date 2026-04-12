package com.study.playground.operator.pipeline.job.domain.port.out;

/**
 * Jenkins job 생성에 필요한 project/purpose/tool 정보를 한 번에 읽는 out-port.
 */
public interface LoadJobDependenciesPort {

    record JobDependencies(
            String projectId
            , String presetId
            , String jobId
            , String jenkinsUrl
            , String username
            , String apiToken
            , String jenkinsScript
    ) {}

    JobDependencies load(String jobId, String presetId);
}
