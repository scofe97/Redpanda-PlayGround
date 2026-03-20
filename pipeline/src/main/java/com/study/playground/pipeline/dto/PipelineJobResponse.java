package com.study.playground.pipeline.dto;

import com.study.playground.pipeline.domain.PipelineJob;
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
                , job.getDependsOnJobIds() != null ? job.getDependsOnJobIds() : List.of()
        );
    }
}
