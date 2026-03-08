package com.study.playground.pipeline.dto;

import com.study.playground.pipeline.domain.PipelineExecution;
import com.study.playground.pipeline.domain.PipelineStep;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Getter
@Builder
public class PipelineExecutionResponse {
    private UUID executionId;
    private Long ticketId;
    private String status;
    private List<PipelineStepResponse> steps;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private String errorMessage;
    private String trackingUrl;

    public static PipelineExecutionResponse from(PipelineExecution execution, List<PipelineStep> steps) {
        return PipelineExecutionResponse.builder()
                .executionId(execution.getId())
                .ticketId(execution.getTicketId())
                .status(execution.getStatus().name())
                .steps(steps != null ? steps.stream().map(PipelineStepResponse::from).toList() : List.of())
                .startedAt(execution.getStartedAt())
                .completedAt(execution.getCompletedAt())
                .errorMessage(execution.getErrorMessage())
                .trackingUrl("/api/tickets/" + execution.getTicketId() + "/pipeline/events")
                .build();
    }

    public static PipelineExecutionResponse accepted(PipelineExecution execution) {
        return PipelineExecutionResponse.builder()
                .executionId(execution.getId())
                .ticketId(execution.getTicketId())
                .status(execution.getStatus().name())
                .trackingUrl("/api/tickets/" + execution.getTicketId() + "/pipeline/events")
                .build();
    }
}
