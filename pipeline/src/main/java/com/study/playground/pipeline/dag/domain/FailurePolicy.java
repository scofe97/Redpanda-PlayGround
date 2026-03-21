package com.study.playground.pipeline.dag.domain;

/**
 * 파이프라인 실행 중 Job 실패 시 적용할 정책.
 *
 * <p>파이프라인 정의(pipeline_definition) 수준에서 설정하며,
 * DagExecutionCoordinator가 Job 실패 시 이 정책에 따라 분기 처리한다.</p>
 */
public enum FailurePolicy {

    /**
     * 기본값. 실패 발생 시 새 Job 디스패치를 중단하고,
     * 이미 실행 중인 Job의 완료를 기다린 뒤 SAGA 보상을 실행한다.
     */
    STOP_ALL,

    /**
     * 실패한 Job의 전이적 하위(successor graph BFS)만 SKIP하고,
     * 다른 독립 브랜치는 계속 실행한다. diamond DAG에서 유용하다.
     */
    SKIP_DOWNSTREAM,

    /**
     * 실패 즉시 모든 PENDING Job을 SKIP 처리한다.
     * 이미 RUNNING 중인 Job은 완료를 기다린 뒤 최종 판정한다.
     */
    FAIL_FAST
}
