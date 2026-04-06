package com.study.playground.executor.domain;

import com.study.playground.executor.dispatch.domain.model.ExecutionJob;
import com.study.playground.executor.dispatch.domain.model.ExecutionJobStatus;
import com.study.playground.executor.dispatch.domain.service.DispatchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DispatchService 도메인 단위 테스트")
class DispatchServiceTest {

    private DispatchService dispatchService;

    @BeforeEach
    void setUp() {
        dispatchService = new DispatchService();
    }

    private ExecutionJob createPendingJob() {
        return ExecutionJob.create(
                "job-excn-001"
                , "pipeline-excn-001"
                , "job-001"
                , 1L
                , "build-job"
                , 10
                , LocalDateTime.now()
                , "user-01"
        );
    }

    @Test
    @DisplayName("prepareForDispatch()는 QUEUED로 전이해야 한다")
    void prepareForDispatch_shouldTransitionToQueued() {
        // given
        ExecutionJob job = createPendingJob();

        // when
        dispatchService.prepareForDispatch(job);

        // then
        assertThat(job.getStatus()).isEqualTo(ExecutionJobStatus.QUEUED);
    }

    @Test
    @DisplayName("markAsRunning()은 Job을 RUNNING 상태로 전이해야 한다")
    void markAsRunning_shouldTransitionToRunning() {
        // given
        ExecutionJob job = createPendingJob();
        dispatchService.prepareForDispatch(job); // PENDING → QUEUED

        // when
        dispatchService.markAsRunning(job, 1);

        // then
        assertThat(job.getStatus()).isEqualTo(ExecutionJobStatus.RUNNING);
        assertThat(job.getBgngDt()).isNotNull();
    }

    @Test
    @DisplayName("markAsCompleted()는 SUCCESS 결과를 받아 SUCCESS 상태로 전이해야 한다")
    void markAsCompleted_successResult_shouldTransitionToSuccess() {
        // given
        ExecutionJob job = createPendingJob();
        dispatchService.prepareForDispatch(job); // QUEUED
        dispatchService.markAsRunning(job, 1);      // RUNNING

        // when
        dispatchService.markAsCompleted(job, "SUCCESS");

        // then
        assertThat(job.getStatus()).isEqualTo(ExecutionJobStatus.SUCCESS);
        assertThat(job.getEndDt()).isNotNull();
    }

    @Test
    @DisplayName("markAsCompleted()는 FAILURE 결과를 받아 FAILURE 상태로 전이해야 한다")
    void markAsCompleted_failureResult_shouldTransitionToFailure() {
        // given
        ExecutionJob job = createPendingJob();
        dispatchService.prepareForDispatch(job); // QUEUED
        dispatchService.markAsRunning(job, 1);      // RUNNING

        // when
        dispatchService.markAsCompleted(job, "FAILURE");

        // then
        assertThat(job.getStatus()).isEqualTo(ExecutionJobStatus.FAILURE);
        assertThat(job.getEndDt()).isNotNull();
    }

    @Test
    @DisplayName("retryOrFail()은 재시도 가능한 경우 PENDING으로 리셋하고 true를 반환해야 한다")
    void retryOrFail_canRetry_shouldResetToPendingAndReturnTrue() {
        // given — QUEUED 상태에서 재시도 (retryCnt=0, maxRetries=2)
        ExecutionJob job = createPendingJob();
        dispatchService.prepareForDispatch(job); // QUEUED

        // when
        boolean result = dispatchService.retryOrFail(job, 2);

        // then
        assertThat(result).isTrue();
        assertThat(job.getStatus()).isEqualTo(ExecutionJobStatus.PENDING);
        assertThat(job.getRetryCnt()).isEqualTo(1);
    }

    @Test
    @DisplayName("retryOrFail()은 재시도 불가능한 경우 FAILURE로 전이하고 false를 반환해야 한다")
    void retryOrFail_cannotRetry_shouldTransitionToFailureAndReturnFalse() {
        // given — retryCnt=2, maxRetries=2 → canRetry() = false
        ExecutionJob job = createPendingJob();
        job.incrementRetry(); // retryCnt=1
        job.incrementRetry(); // retryCnt=2
        dispatchService.prepareForDispatch(job); // QUEUED

        // when
        boolean result = dispatchService.retryOrFail(job, 2);

        // then
        assertThat(result).isFalse();
        assertThat(job.getStatus()).isEqualTo(ExecutionJobStatus.FAILURE);
    }

    @Test
    @DisplayName("recordLogResult()는 success=true일 때 logFileYn을 'Y'로 설정해야 한다")
    void recordLogResult_success_shouldMarkLogUploaded() {
        // given
        ExecutionJob job = createPendingJob();

        // when
        dispatchService.recordLogResult(job, true);

        // then
        assertThat(job.getLogFileYn()).isEqualTo("Y");
    }

    @Test
    @DisplayName("recordLogResult()는 success=false일 때 logFileYn을 변경하지 않아야 한다")
    void recordLogResult_failure_shouldNotChangeLogFileYn() {
        // given
        ExecutionJob job = createPendingJob();
        assertThat(job.getLogFileYn()).isEqualTo("N");

        // when
        dispatchService.recordLogResult(job, false);

        // then
        assertThat(job.getLogFileYn()).isEqualTo("N");
    }
}
