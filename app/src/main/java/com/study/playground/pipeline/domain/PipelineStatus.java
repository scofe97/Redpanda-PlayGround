package com.study.playground.pipeline.domain;

/**
 * 파이프라인 전체 실행 상태.
 *
 * <p>상태 전이: PENDING → RUNNING → SUCCESS | FAILED</p>
 * <p>FAILED 상태가 되면 SAGA 보상 트랜잭션이 트리거된다.
 * 이미 완료된 스텝은 COMPENSATED 처리되어 부수 효과를 되돌린다.</p>
 */
public enum PipelineStatus {

    /** 실행 요청이 접수됐으나 아직 첫 스텝이 시작되지 않은 초기 상태. */
    PENDING,

    /** 하나 이상의 스텝이 진행 중인 상태. */
    RUNNING,

    /** 모든 스텝이 정상 완료된 상태. */
    SUCCESS,

    /**
     * 하나 이상의 스텝이 실패하여 파이프라인이 중단된 상태.
     * 이 상태로 전이되면 보상 로직이 실행된다.
     */
    FAILED
}
