package com.study.playground.executor.runner.domain.port.out;

/**
 * op에 Job 완료를 토픽으로 통지하는 out-port.
 * executor의 모든 처리(상태 전이, 로그 적재)가 끝난 후 op에 결과를 알린다.
 *
 * @see com.study.playground.executor.runner.infrastructure.messaging.JobCompletedNotifyPublisher
 */
public interface NotifyJobCompletedPort {

    /**
     * op에 Job 완료 결과를 토픽으로 발행한다.
     *
     * @param jobExcnId      Job 실행 건 식별자
     * @param pipelineExcnId 파이프라인 실행 건 식별자 (null 가능)
     * @param success        빌드 성공 여부
     * @param result         Jenkins 결과값 (SUCCESS, FAILURE, ABORTED 등)
     * @param logFilePath    로그 파일 경로 (적재 실패 시 null)
     * @param logFileYn      로그 파일 적재 성공 여부 ("Y"/"N")
     * @param errorMessage   실패 시 예외 메시지 (성공 시 null)
     */
    void notify(
            String jobExcnId
            , String pipelineExcnId
            , boolean success
            , String result
            , String logFilePath
            , String logFileYn
            , String errorMessage
    );
}
