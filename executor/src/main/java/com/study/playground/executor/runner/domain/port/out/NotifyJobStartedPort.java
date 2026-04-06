package com.study.playground.executor.runner.domain.port.out;

/**
 * op에 Job 시작을 토픽으로 통지하는 out-port.
 * executor는 op DB를 직접 수정하지 않고, 토픽으로 알린다.
 */
public interface NotifyJobStartedPort {

    /**
     * op에 Job 시작을 토픽으로 발행한다.
     *
     * @param jobExcnId      Job 실행 건 식별자
     * @param pipelineExcnId 파이프라인 실행 건 식별자 (null 가능)
     * @param jobId          Job 정의 식별자
     * @param buildNo        Jenkins 빌드 번호
     */
    void notify(String jobExcnId, String pipelineExcnId, String jobId, int buildNo);
}
