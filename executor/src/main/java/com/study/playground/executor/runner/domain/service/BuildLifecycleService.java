package com.study.playground.executor.runner.domain.service;

import com.study.playground.executor.dispatch.domain.model.ExecutionJob;
import com.study.playground.executor.dispatch.domain.model.ExecutionJobStatus;
import com.study.playground.executor.runner.domain.model.BuildCallback;

/**
 * 빌드 라이프사이클 도메인 로직.
 * 매칭 결과에 대한 상태 전이와 로그 적재 판단을 수행한다.
 */
public class BuildLifecycleService {

    /**
     * 빌드 시작 시 상태 전이.
     */
    public void applyStarted(ExecutionJob job, BuildCallback callback) {
        job.recordBuildNo(callback.buildNo());
        job.transitionTo(ExecutionJobStatus.RUNNING);
    }

    /**
     * 빌드 종료 시 상태 전이.
     */
    public void applyCompleted(ExecutionJob job, BuildCallback callback) {
        var newStatus = ExecutionJobStatus.fromJenkinsResult(callback.result());
        job.transitionTo(newStatus);
    }

    /**
     * 로그 적재 성공/실패를 기록한다.
     */
    public void recordLogResult(ExecutionJob job, boolean success) {
        if (success) {
            job.markLogFileUploaded();
        }
    }
}
