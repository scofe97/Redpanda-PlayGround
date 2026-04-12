package com.study.playground.operator.pipeline.job.domain.service;

import com.study.playground.operator.pipeline.job.domain.model.JenkinsJobSpec;

public class JenkinsPathBuilder {

    public JenkinsJobSpec buildSpec(
            String projectId
            , String presetId
            , String jobId
            , String jenkinsUrl
            , String username
            , String apiToken
            , String jenkinsScript
    ) {
        return new JenkinsJobSpec(
                projectId, presetId, jobId
                , jenkinsUrl, username, apiToken, jenkinsScript
        );
    }
}
