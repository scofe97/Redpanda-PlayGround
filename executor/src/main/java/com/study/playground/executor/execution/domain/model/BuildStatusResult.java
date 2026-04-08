package com.study.playground.executor.execution.domain.model;

/**
 * Jenkins 빌드 상태 조회 결과.
 * NOT_FOUND: 빌드번호에 해당하는 빌드 없음 (아직 큐 대기 중)
 * BUILDING: 빌드 실행 중
 * COMPLETED: 빌드 완료 (result에 Jenkins result 문자열)
 */
public record BuildStatusResult(BuildPhase phase, String result) {

    public enum BuildPhase {
        NOT_FOUND,
        BUILDING,
        COMPLETED
    }

    public static BuildStatusResult notFound() {
        return new BuildStatusResult(BuildPhase.NOT_FOUND, null);
    }

    public static BuildStatusResult building() {
        return new BuildStatusResult(BuildPhase.BUILDING, null);
    }

    public static BuildStatusResult completed(String result) {
        return new BuildStatusResult(BuildPhase.COMPLETED, result);
    }
}
