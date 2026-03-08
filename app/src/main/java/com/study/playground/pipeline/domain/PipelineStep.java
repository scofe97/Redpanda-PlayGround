package com.study.playground.pipeline.domain;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
public class PipelineStep {
    private Long id;
    private UUID executionId;
    private Integer stepOrder;
    private PipelineStepType stepType;
    private String stepName;
    private StepStatus status;
    private String log;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;

    private transient boolean waitingForWebhook = false;
}
