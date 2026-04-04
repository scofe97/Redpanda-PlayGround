package com.study.playground.executor.runner.domain.port.out;

/**
 * Operator DB의 파이프라인 실행 상태를 직접 갱신하는 out-port.
 * executor가 op의 DB를 cross-schema UPDATE한다.
 *
 * @see com.study.playground.executor.runner.infrastructure.persistence.OperatorStatusUpdateAdapter
 */
public interface UpdateOperatorStatusPort {

    /**
     * op DB의 파이프라인 Job 실행 상태를 갱신한다.
     *
     * @param jobExcnId op의 job_execution 식별자 (executor의 JOB_EXCN_ID와 1:1)
     * @param status    갱신할 상태값 (예: "RUNNING", "SUCCESS")
     */
    void updateJobExecutionStatus(String jobExcnId, String status);
}
