package com.study.playground.operatorstub.domain;

/**
 * Operator 측 Job 상태.
 * PENDING → QUEUING → RUNNING → SUCCESS/FAILURE/ABORTED
 */
public enum OperatorJobStatus {
    PENDING,
    QUEUING,
    RUNNING,
    SUCCESS,
    FAILURE,
    ABORTED
}
