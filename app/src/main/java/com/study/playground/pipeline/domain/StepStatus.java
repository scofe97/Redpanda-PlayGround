package com.study.playground.pipeline.domain;

/**
 * 개별 파이프라인 스텝의 실행 상태.
 *
 * <p>정상 전이: PENDING → RUNNING → SUCCESS</p>
 * <p>실패 전이: RUNNING → FAILED → (보상 후) COMPENSATED</p>
 * <p>WAITING_WEBHOOK은 외부 시스템의 완료 신호를 기다리는 중간 상태로,
 * 타임아웃이 지나면 FAILED로 전이된다.</p>
 *
 * <p>{@link PipelineStatus}와 별도로 존재하는 이유:
 * 파이프라인 전체 상태와 개별 스텝 상태는 의미와 전이 규칙이 다르기 때문이다.
 * 예를 들어 SKIPPED, COMPENSATED는 스텝 수준에서만 발생한다.</p>
 */
public enum StepStatus {

    /** 아직 시작되지 않은 스텝. 이전 스텝이 완료될 때까지 이 상태를 유지한다. */
    PENDING,

    /** 현재 실행 중인 스텝. */
    RUNNING,

    /** 정상 완료된 스텝. */
    SUCCESS,

    /** 실행 중 오류가 발생하여 중단된 스텝. 파이프라인 전체 FAILED를 유발한다. */
    FAILED,

    /**
     * 선행 스텝 실패로 인해 실행 기회를 갖지 못하고 건너뛴 스텝.
     * 보상 대상이 아니다(아직 아무 작업도 수행하지 않았으므로).
     */
    SKIPPED,

    /**
     * SAGA 보상 트랜잭션에 의해 부수 효과가 되돌려진 스텝.
     * SUCCESS 상태였던 스텝이 FAILED 파이프라인의 롤백 과정에서 이 상태로 전이된다.
     */
    COMPENSATED,

    /**
     * 외부 시스템(예: 배포 완료 웹훅)의 응답을 기다리는 중간 상태.
     * 폴링 스케줄러가 주기적으로 이 상태인 스텝의 타임아웃을 확인한다.
     */
    WAITING_WEBHOOK
}
