package com.study.playground.pipeline.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class PipelineJobMappingRequest {
    @NotNull
    private Long jobId;
    @NotNull
    private Integer executionOrder;
    private List<Long> dependsOnJobIds;
}
