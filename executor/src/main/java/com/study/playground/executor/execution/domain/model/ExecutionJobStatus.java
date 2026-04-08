package com.study.playground.executor.execution.domain.model;

import java.util.Map;
import java.util.Set;

/**
 * Executor 내부 Job 실행 상태.
 *
 * PENDING ~ RUNNING: executor 내부 상태
 * 터미널 상태: Jenkins 실제 상태와 1:1 매핑
 *
 * PENDING  → QUEUED  → SUBMITTED → RUNNING → SUCCESS / FAILURE / UNSTABLE / ABORTED / NOT_BUILT / NOT_EXECUTED
 * PENDING  → FAILURE (정의 누락 등 디스패치 전 단계의 복구 불가 오류)
 * QUEUED   → PENDING (Jenkins 트리거 실패 → 재시도)
 * SUBMITTED → PENDING (스케줄러 방어 → 재시도)
 * SUBMITTED → 터미널 (시작 웹훅 유실 시 완료 웹훅 직접 수신 or 스케줄러 방어)
 * RUNNING  → PENDING (타임아웃 → 재시도)
 */
public enum ExecutionJobStatus {

    // executor 내부 상태
    PENDING,
    QUEUED,
    SUBMITTED,
    RUNNING,

    // Jenkins 실제 상태 (터미널)
    SUCCESS,
    FAILURE,
    UNSTABLE,
    ABORTED,
    NOT_BUILT,
    NOT_EXECUTED;

    private static final Map<ExecutionJobStatus, Set<ExecutionJobStatus>> ALLOWED_TRANSITIONS = Map.of(
            PENDING, Set.of(QUEUED, FAILURE)
            , QUEUED, Set.of(SUBMITTED, PENDING, FAILURE)
            , SUBMITTED, Set.of(RUNNING, PENDING, FAILURE
                    , SUCCESS, UNSTABLE, ABORTED, NOT_BUILT, NOT_EXECUTED)
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
        return this == SUCCESS
                || this == FAILURE
                || this == UNSTABLE
                || this == ABORTED
                || this == NOT_BUILT
                || this == NOT_EXECUTED;
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
