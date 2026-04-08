package com.study.playground.operator.pipeline.pipeline.domain.port.out;

import com.study.playground.operator.pipeline.pipeline.domain.model.Pipeline;
import com.study.playground.operator.pipeline.pipeline.domain.model.PipelineVersion;

import java.util.List;
import java.util.Optional;

public interface LoadPipelinePort {

    Optional<Pipeline> findById(String pipelineId);

    List<Pipeline> findByProjectId(String projectId);

    List<Pipeline> findAll();

    /** 해당 파이프라인의 최신 버전(스텝 포함)을 조회한다. */
    Optional<PipelineVersion> findLatestVersion(String pipelineId);

    /** 해당 파이프라인의 다음 버전 번호를 반환한다. */
    int getNextVersionNumber(String pipelineId);
}
