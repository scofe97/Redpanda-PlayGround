package com.study.playground.executor.execution.domain.port.in;

import java.time.LocalDateTime;

/**
 * Job 수신 유스케이스 (in-port).
 *
 * Operator가 CMD_JOB_DISPATCH 토픽으로 전달한 Job을 수신하여
 * executor 내부 테이블(EX_003)에 PENDING 상태로 저장한다.
 * 저장 직후 tryDispatch()를 호출하여 즉시 실행 가능 여부를 평가한다.
 */
public interface ReceiveJobUseCase {

    /**
     * Job을 수신하여 PENDING 상태로 저장한다.
     * 동일 jobExcnId가 이미 존재하면 멱등하게 무시한다.
     * priority는 기본값 1이 적용된다 (op에서 제공하지 않음).
     * Jenkins 접속 정보(jenkinsInstanceId, jobName)는 jobId로 DB에서 조회한다.
     *
     * @param jobExcnId      Job 실행 건 식별자 (EX_002와 1:1)
     * @param pipelineExcnId 파이프라인 실행 건 식별자. 단건 실행 시 null
     * @param jobId              실행 대상 Job 정의 식별자 (통합작업관리)
     * @param priorityDt         우선순위 기준 시각 (파이프라인 실행 시간, op가 전달)
     * @param rgtrId             등록자 ID
     */
    void receive(
            String jobExcnId
            , String pipelineExcnId
            , String jobId
            , LocalDateTime priorityDt
            , String rgtrId
    );
}
