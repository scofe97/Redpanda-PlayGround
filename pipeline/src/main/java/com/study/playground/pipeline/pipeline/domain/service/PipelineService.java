package com.study.playground.pipeline.pipeline.domain.service;

import com.study.playground.pipeline.pipeline.domain.model.Pipeline;
import com.study.playground.pipeline.pipeline.domain.model.PipelineVersion;

import java.util.List;

/**
 * 파이프라인 도메인 로직.
 */
public class PipelineService {

    /**
     * 첫 번째 버전(v1)을 생성하고 스텝을 순서대로 추가한다.
     */
    public PipelineVersion createInitialVersion(
            String pipelineId
            , List<String> jobIds
            , String createdBy
    ) {
        var version = PipelineVersion.create(pipelineId, 1, "Initial version", createdBy);
        for (int i = 0; i < jobIds.size(); i++) {
            version.addStep(jobIds.get(i), i + 1, createdBy);
        }
        return version;
    }

    /**
     * 새 버전을 생성하고 스텝을 순서대로 추가한다.
     */
    public PipelineVersion createNewVersion(
            String pipelineId
            , int nextVersion
            , List<String> jobIds
            , String versionDesc
            , String createdBy
    ) {
        var version = PipelineVersion.create(pipelineId, nextVersion, versionDesc, createdBy);
        for (int i = 0; i < jobIds.size(); i++) {
            version.addStep(jobIds.get(i), i + 1, createdBy);
        }
        return version;
    }

    public void validateNotDeleted(Pipeline pipeline) {
        if (pipeline.isDeleted()) {
            throw new IllegalStateException("Pipeline already deleted: " + pipeline.getPipelineId());
        }
    }
}
