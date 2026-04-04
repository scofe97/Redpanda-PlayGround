package com.study.playground.pipeline.job.domain.service;

import com.study.playground.pipeline.job.domain.model.JenkinsJobSpec;

public class JenkinsPathBuilder {

    public JenkinsJobSpec buildSpec(
            String projectId
            , String presetId
            , String jobId
            , String jenkinsUrl
            , String username
            , String credential
            , String jenkinsScript
    ) {
        return new JenkinsJobSpec(
                projectId, presetId, jobId
                , jenkinsUrl, username, credential, jenkinsScript
        );
    }
}
