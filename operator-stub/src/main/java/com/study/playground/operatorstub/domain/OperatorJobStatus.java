package com.study.playground.operatorstub.domain;

/**
 * Operator 측 Job 상태.
 * WAIT → QUEUING → RUNNING → SUCCESS/FAILED
 */
public enum OperatorJobStatus {
    WAIT,
    QUEUING,
    RUNNING,
    SUCCESS,
    FAILED
}
