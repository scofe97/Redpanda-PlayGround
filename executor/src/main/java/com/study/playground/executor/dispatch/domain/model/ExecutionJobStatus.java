package com.study.playground.executor.dispatch.domain.model;

import java.util.Map;
import java.util.Set;

/**
 * Executor 내부 Job 실행 상태.
 *
 * PENDING ~ RUNNING: executor 내부 상태
 * 터미널 상태: Jenkins 실제 상태와 1:1 매핑
 *
 * PENDING  → QUEUED  → RUNNING → SUCCESS / FAILURE / UNSTABLE / ABORTED / NOT_BUILT / NOT_EXECUTED
 * QUEUED   → PENDING (Jenkins 트리거 실패 → 재시도)
 * RUNNING  → PENDING (타임아웃 → 재시도)
 */
public enum ExecutionJobStatus {

    // executor 내부 상태
    PENDING,
    QUEUED,
    RUNNING,

    // Jenkins 실제 상태 (터미널)
    SUCCESS,
    FAILURE,
    UNSTABLE,
    ABORTED,
    NOT_BUILT,
    NOT_EXECUTED;

    private static final Map<ExecutionJobStatus, Set<ExecutionJobStatus>> ALLOWED_TRANSITIONS = Map.of(
            PENDING, Set.of(QUEUED)
            , QUEUED, Set.of(RUNNING, PENDING, FAILURE)
            , RUNNING, Set.of(SUCCESS, FAILURE, UNSTABLE, ABORTED, NOT_BUILT, NOT_EXECUTED, PENDING)
    );

    public boolean canTransitionTo(ExecutionJobStatus target) {
        return ALLOWED_TRANSITIONS.getOrDefault(this, Set.of()).contains(target);
    }

    public static void validateTransition(ExecutionJobStatus from, ExecutionJobStatus to) {
        if (!from.canTransitionTo(to)) {
            throw new IllegalStateException(
                    "Invalid status transition: " + from + " → " + to
            );
        }
    }

    public boolean isTerminal() {
        return this == SUCCESS || this == FAILURE || this == UNSTABLE
                || this == ABORTED || this == NOT_BUILT || this == NOT_EXECUTED;
    }

    /**
     * Jenkins result 문자열을 ExecutionJobStatus로 매핑한다.
     */
    public static ExecutionJobStatus fromJenkinsResult(String result) {
        if (result == null) {
            return FAILURE;
        }
        return switch (result.toUpperCase()) {
            case "SUCCESS" -> SUCCESS;
            case "FAILURE" -> FAILURE;
            case "UNSTABLE" -> UNSTABLE;
            case "ABORTED" -> ABORTED;
            case "NOT_BUILT" -> NOT_BUILT;
            case "NOT_EXECUTED" -> NOT_EXECUTED;
            default -> FAILURE;
        };
    }
}
