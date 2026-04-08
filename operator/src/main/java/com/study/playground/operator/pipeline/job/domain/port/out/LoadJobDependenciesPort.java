package com.study.playground.operator.pipeline.job.domain.port.out;

public interface LoadJobDependenciesPort {

    record JobDependencies(
            String projectId
            , String presetId
            , String jobId
            , String jenkinsUrl
            , String username
            , String credential
            , String jenkinsScript
    ) {}

    /**
     * Job ID와 Preset ID로 Jenkins 잡 생성에 필요한 정보를 조회한다.
     */
    JobDependencies load(String jobId, String presetId);
}
