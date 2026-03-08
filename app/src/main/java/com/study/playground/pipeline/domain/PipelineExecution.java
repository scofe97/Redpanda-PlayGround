package com.study.playground.pipeline.domain;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
public class PipelineExecution {
    private UUID id;
    private Long ticketId;
    private PipelineStatus status;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private String errorMessage;
    private LocalDateTime createdAt;
    private List<PipelineStep> steps;
}
