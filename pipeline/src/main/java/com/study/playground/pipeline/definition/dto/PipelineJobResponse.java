package com.study.playground.pipeline.dag.dto;

import com.study.playground.pipeline.dag.domain.ParameterSchema;
import com.study.playground.pipeline.dag.domain.PipelineJob;
import com.study.playground.pipeline.domain.PipelineJobType;

import java.util.List;

public record PipelineJobResponse(
        Long id,
        String jobName,
        PipelineJobType jobType,
        Integer executionOrder,
        Long presetId,
        String presetName,
        String configJson,
        List<ParameterSchema> parameterSchemas,
        List<Long> dependsOnJobIds
) {
    public static PipelineJobResponse from(PipelineJob job) {
        return new PipelineJobResponse(
                job.getId()
                , job.getJobName()
                , job.getJobType()
                , job.getExecutionOrder()
                , job.getPresetId()
                , job.getPresetName()
                , job.getConfigJson()
                , job.parameterSchemas()
                , job.getDependsOnJobIds() != null ? job.getDependsOnJobIds() : List.of()
        );
    }
}
