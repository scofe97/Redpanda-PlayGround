package com.study.playground.operator.pipeline.job.domain.port.out;

import com.study.playground.operator.pipeline.job.domain.model.JenkinsJobSpec;

public interface JenkinsApiPort {

    /** Jenkins에 해당 경로의 아이템이 존재하는지 확인한다. */
    boolean exists(JenkinsJobSpec spec, String apiPath);

    /** Jenkins에 폴더를 생성한다. */
    void createFolder(JenkinsJobSpec spec, String parentPath, String folderName);

    /** Jenkins에 파이프라인 잡을 생성한다. */
    void createPipelineJob(JenkinsJobSpec spec, String parentPath, String jobName);
}
