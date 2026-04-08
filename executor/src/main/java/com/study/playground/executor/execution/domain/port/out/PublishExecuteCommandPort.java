package com.study.playground.executor.execution.domain.port.out;

import com.study.playground.executor.execution.domain.model.ExecutionJob;

/**
 * CMD_JOB_EXECUTE 발행 out-port.
 * 디스패치가 확정된 Job을 실행 토픽에 발행하여 runner가 Jenkins를 트리거하도록 한다.
 * infrastructure의 ExecuteCommandPublisher가 Outbox 패턴으로 구현한다.
 */
public interface PublishExecuteCommandPort {

    /**
     * QUEUED 전환된 Job의 실행 명령을 CMD_JOB_EXECUTE 토픽에 발행한다.
     * Outbox 패턴으로 DB 트랜잭션과 메시지 발행의 원자성을 보장한다.
     *
     * @param job 실행 명령을 발행할 Job (QUEUED 상태)
     */
    void publishExecuteCommand(ExecutionJob job);
}
