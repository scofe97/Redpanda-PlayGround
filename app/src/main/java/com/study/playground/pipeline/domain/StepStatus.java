package com.study.playground.pipeline.domain;

public enum StepStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    FAILED,
    SKIPPED,
    COMPENSATED,
    WAITING_WEBHOOK
}
