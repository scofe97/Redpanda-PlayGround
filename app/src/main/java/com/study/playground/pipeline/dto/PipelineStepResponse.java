package com.study.playground.pipeline.dto;

import com.study.playground.pipeline.domain.PipelineStep;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class PipelineStepResponse {
    private Long id;
    private Integer stepOrder;
    private String stepType;
    private String stepName;
    private String status;
    private String log;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;

    public static PipelineStepResponse from(PipelineStep step) {
        return PipelineStepResponse.builder()
                .id(step.getId())
                .stepOrder(step.getStepOrder())
                .stepType(step.getStepType().name())
                .stepName(step.getStepName())
                .status(step.getStatus().name())
                .log(step.getLog())
                .startedAt(step.getStartedAt())
                .completedAt(step.getCompletedAt())
                .build();
    }
}
