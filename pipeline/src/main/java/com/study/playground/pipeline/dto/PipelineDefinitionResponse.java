package com.study.playground.pipeline.dto;

import com.study.playground.pipeline.domain.PipelineDefinition;

import java.time.LocalDateTime;
import java.util.List;

public record PipelineDefinitionResponse(
        Long id,
        String name,
        String description,
        String status,
        LocalDateTime createdAt,
        List<PipelineJobResponse> jobs
) {
    public static PipelineDefinitionResponse from(PipelineDefinition def) {
        List<PipelineJobResponse> jobResponses = def.getJobs() != null
                ? def.getJobs().stream().map(PipelineJobResponse::from).toList()
                : List.of();
        return new PipelineDefinitionResponse(
                def.getId()
                , def.getName()
                , def.getDescription()
                , def.getStatus()
                , def.getCreatedAt()
                , jobResponses
        );
    }
}
