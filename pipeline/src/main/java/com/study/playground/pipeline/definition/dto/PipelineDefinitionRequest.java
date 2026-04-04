package com.study.playground.pipeline.dag.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PipelineDefinitionRequest {
    @NotBlank
    @Size(max = 200)
    private String name;
    private String description;
}
