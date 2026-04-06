package com.study.playground.executor.runner.domain.port.in;

import com.study.playground.executor.runner.domain.model.BuildCallback;

/**
 * 빌드 종료 처리 유스케이스 (in-port).
 *
 * Jenkins JobListener가 webhook 토픽으로 전달한 종료 이벤트를 처리한다.
 * jobExcnId로 executor DB에서 직접 조회한 뒤:
 *   1. 로그 파일 적재 (실패해도 계속 진행, LOG_FILE_YN에 성공여부 기록)
 *   2. executor DB: RUNNING → SUCCESS/FAILURE/... + END_DT
 *   3. op DB: 상태 UPDATE (cross-schema)
 *   4. op에 notify 토픽 발행 (성공여부, 로그경로, 로그적재여부, 예외메시지)
 *   5. tryDispatch() 호출 (슬롯 반납 → 대기 Job 실행)
 */
public interface HandleBuildCompletedUseCase {

    /**
     * @param callback Jenkins 종료 콜백 (경로, buildNo, result, logContent)
     */
    void handle(BuildCallback callback);
}
