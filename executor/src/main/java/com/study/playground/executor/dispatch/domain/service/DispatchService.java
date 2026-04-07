package com.study.playground.executor.dispatch.domain.service;

import com.study.playground.executor.dispatch.domain.model.ExecutionJob;
import com.study.playground.executor.dispatch.domain.model.ExecutionJobStatus;

/**
 * 순수 도메인 로직.
 * 외부 의존 없이 도메인 모델의 비즈니스 규칙만 담당한다.
 */
public class DispatchService {

    /**
     * 디스패치 준비: QUEUED 전환.
     * 슬롯 확인이 완료된 후 호출한다.
     */
    public void prepareForDispatch(ExecutionJob job) {
        job.transitionTo(ExecutionJobStatus.QUEUED);
    }

    /**
     * Jenkins 빌드 트리거 성공: SUBMITTED 전환 + 빌드번호 기록.
     * Jenkins 큐에 적재되었으나 아직 실행되지 않은 상태.
     */
    public void markAsSubmitted(ExecutionJob job, int buildNo) {
        job.recordBuildNo(buildNo);
        job.transitionTo(ExecutionJobStatus.SUBMITTED);
    }

    /**
     * Job을 RUNNING 상태로 전환하고 빌드번호를 기록한다.
     */
    public void markAsRunning(ExecutionJob job, int buildNo) {
        job.recordBuildNo(buildNo);
        job.transitionTo(ExecutionJobStatus.RUNNING);
    }

    /**
     * Jenkins 결과를 받아 터미널 상태로 전환한다.
     */
    public void markAsCompleted(ExecutionJob job, String jenkinsResult) {
        ExecutionJobStatus newStatus = ExecutionJobStatus.fromJenkinsResult(jenkinsResult);
        job.transitionTo(newStatus);
    }

    /**
     * 재시도 가능하면 PENDING으로 리셋, 불가하면 FAILURE로 전환한다.
     */
    /**
     * 로그 적재 성공/실패를 기록한다.
     */
    public void recordLogResult(ExecutionJob job, boolean success) {
        if (success) {
            job.markLogFileUploaded();
        }
    }

    /**
     * 재시도 가능하면 PENDING으로 리셋, 불가하면 FAILURE로 전환한다.
     */
    public boolean retryOrFail(ExecutionJob job, int maxRetries) {
        if (job.canRetry(maxRetries)) {
            job.incrementRetry();
            job.transitionTo(ExecutionJobStatus.PENDING);
            return true;
        }
        job.transitionTo(ExecutionJobStatus.FAILURE);
        return false;
    }
}
