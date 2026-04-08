package com.study.playground.executor.execution.domain.port.in;

/**
 * 디스패치 평가 유스케이스 (in-port).
 *
 * PENDING 상태 Job을 우선순위 순으로 조회하고,
 * Jenkins 슬롯 가용 여부를 판단하여 실행 가능한 Job을
 * QUEUED로 전환하고 CMD_JOB_EXECUTE 토픽에 발행한다.
 *
 * 스케줄러 없이 3가지 이벤트에서만 호출된다:
 *   1. Job 수신 (CMD_JOB_DISPATCH 컨슘 시)
 *   2. Job 시작 (EVT_JOB_STARTED 컨슘 시)
 *   3. Job 종료 (EVT_JOB_COMPLETED 컨슘 시)
 */
public interface EvaluateDispatchUseCase {

    /**
     * PENDING Job을 평가하여 실행 가능한 것을 디스패치한다.
     * FOR UPDATE SKIP LOCKED로 멀티 executor 인스턴스 환경에서 중복 디스패치를 방지한다.
     */
    void tryDispatch();
}
