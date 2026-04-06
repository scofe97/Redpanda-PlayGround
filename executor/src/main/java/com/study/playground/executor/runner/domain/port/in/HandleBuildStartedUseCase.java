package com.study.playground.executor.runner.domain.port.in;

import com.study.playground.executor.runner.domain.model.BuildCallback;

/**
 * 빌드 시작 처리 유스케이스 (in-port).
 *
 * Jenkins JobListener가 webhook 토픽으로 전달한 시작 이벤트를 처리한다.
 * jobExcnId로 executor DB에서 직접 조회한 뒤:
 *   1. executor DB: QUEUED → RUNNING + BGNG_DT 기록
 *   2. op DB: PENDING → RUNNING (cross-schema UPDATE)
 *   3. tryDispatch() 호출 (슬롯 변동 반영)
 */
public interface HandleBuildStartedUseCase {

    /**
     * @param callback Jenkins 시작 콜백 (경로, buildNo)
     */
    void handle(BuildCallback callback);
}
