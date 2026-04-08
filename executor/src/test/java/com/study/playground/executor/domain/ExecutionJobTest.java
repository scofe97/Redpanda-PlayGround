package com.study.playground.executor.domain;

import com.study.playground.executor.execution.domain.model.ExecutionJob;
import com.study.playground.executor.execution.domain.model.ExecutionJobStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ExecutionJob 도메인 단위 테스트")
class ExecutionJobTest {

    private ExecutionJob createSampleJob() {
        return ExecutionJob.create(
                "job-excn-001"
                , "pipeline-excn-001"
                , "job-001"
                , 10
                , LocalDateTime.now()
                , "user-01"
        );
    }

    @Test
    @DisplayName("create()는 PENDING 상태와 초기값으로 생성되어야 한다")
    void create_shouldInitializeWithPendingStatus() {
        // when
        ExecutionJob job = createSampleJob();

        // then
        assertThat(job.getStatus()).isEqualTo(ExecutionJobStatus.PENDING);
        assertThat(job.getRetryCnt()).isZero();
        assertThat(job.getLogFileYn()).isEqualTo("N");
    }

    @Test
    @DisplayName("create()는 전달된 모든 필드를 올바르게 설정해야 한다")
    void create_shouldSetAllFields() {
        // given
        LocalDateTime priorityDt = LocalDateTime.of(2026, 4, 6, 10, 0, 0);

        // when
        ExecutionJob job = ExecutionJob.create(
                "job-excn-002"
                , "pipeline-excn-002"
                , "job-002"
                , 5
                , priorityDt
                , "user-02"
        );

        // then
        assertThat(job.getJobExcnId()).isEqualTo("job-excn-002");
        assertThat(job.getPipelineExcnId()).isEqualTo("pipeline-excn-002");
        assertThat(job.getJobId()).isEqualTo("job-002");
        assertThat(job.getPriority()).isEqualTo(5);
        assertThat(job.getPriorityDt()).isEqualTo(priorityDt);
        assertThat(job.getRgtrId()).isEqualTo("user-02");
        assertThat(job.getMdfrId()).isEqualTo("user-02");
        assertThat(job.getRegDt()).isNotNull();
        assertThat(job.getMdfcnDt()).isNotNull();
    }

    @Test
    @DisplayName("유효한 전이는 상태를 변경해야 한다 (PENDING → QUEUED)")
    void transitionTo_validTransition_shouldChangeStatus() {
        // given
        ExecutionJob job = createSampleJob();

        // when
        job.transitionTo(ExecutionJobStatus.QUEUED);

        // then
        assertThat(job.getStatus()).isEqualTo(ExecutionJobStatus.QUEUED);
    }

    @Test
    @DisplayName("RUNNING 전이 시 bgngDt가 설정되어야 한다")
    void transitionTo_toRunning_shouldSetBgngDt() {
        // given
        ExecutionJob job = createSampleJob();
        job.transitionTo(ExecutionJobStatus.QUEUED);
        job.transitionTo(ExecutionJobStatus.SUBMITTED);

        // when
        job.transitionTo(ExecutionJobStatus.RUNNING);

        // then
        assertThat(job.getBgngDt()).isNotNull();
    }

    @Test
    @DisplayName("터미널 상태 전이 시 endDt가 설정되어야 한다")
    void transitionTo_toTerminal_shouldSetEndDt() {
        // given — SUCCESS 케이스
        ExecutionJob jobSuccess = createSampleJob();
        jobSuccess.transitionTo(ExecutionJobStatus.QUEUED);
        jobSuccess.transitionTo(ExecutionJobStatus.SUBMITTED);
        jobSuccess.transitionTo(ExecutionJobStatus.RUNNING);

        // when
        jobSuccess.transitionTo(ExecutionJobStatus.SUCCESS);

        // then
        assertThat(jobSuccess.getEndDt()).isNotNull();

        // given — FAILURE 케이스
        ExecutionJob jobFailure = createSampleJob();
        jobFailure.transitionTo(ExecutionJobStatus.QUEUED);
        jobFailure.transitionTo(ExecutionJobStatus.SUBMITTED);
        jobFailure.transitionTo(ExecutionJobStatus.RUNNING);

        // when
        jobFailure.transitionTo(ExecutionJobStatus.FAILURE);

        // then
        assertThat(jobFailure.getEndDt()).isNotNull();
    }

    @Test
    @DisplayName("잘못된 전이는 IllegalStateException을 던져야 한다 (PENDING → RUNNING)")
    void transitionTo_invalidTransition_shouldThrow() {
        // given
        ExecutionJob job = createSampleJob();

        // when / then
        assertThatThrownBy(() -> job.transitionTo(ExecutionJobStatus.RUNNING))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("recordBuildNo()는 buildNo를 설정하고 mdfcnDt를 갱신해야 한다")
    void recordBuildNo_shouldSetBuildNoAndUpdateMdfcnDt() {
        // given
        ExecutionJob job = createSampleJob();
        LocalDateTime before = job.getMdfcnDt();

        // when
        job.recordBuildNo(42);

        // then
        assertThat(job.getBuildNo()).isEqualTo(42);
        assertThat(job.getMdfcnDt()).isNotNull();
    }

    @Test
    @DisplayName("incrementRetry()는 retryCnt를 1 증가시켜야 한다")
    void incrementRetry_shouldIncrementRetryCnt() {
        // given
        ExecutionJob job = createSampleJob();

        // when
        job.incrementRetry();

        // then
        assertThat(job.getRetryCnt()).isEqualTo(1);

        // when
        job.incrementRetry();

        // then
        assertThat(job.getRetryCnt()).isEqualTo(2);
    }

    @Test
    @DisplayName("canRetry()는 retryCnt가 maxRetries 미만이면 true를 반환해야 한다")
    void canRetry_underLimit_shouldReturnTrue() {
        // given
        ExecutionJob job = createSampleJob();
        // retryCnt = 0

        // when / then
        assertThat(job.canRetry(2)).isTrue();
    }

    @Test
    @DisplayName("canRetry()는 retryCnt가 maxRetries와 같으면 false를 반환해야 한다")
    void canRetry_atLimit_shouldReturnFalse() {
        // given
        ExecutionJob job = createSampleJob();
        job.incrementRetry(); // retryCnt = 1
        job.incrementRetry(); // retryCnt = 2

        // when / then
        assertThat(job.canRetry(2)).isFalse();
    }

    @Test
    @DisplayName("markLogFileUploaded()는 logFileYn을 'Y'로 설정해야 한다")
    void markLogFileUploaded_shouldSetLogFileYnToY() {
        // given
        ExecutionJob job = createSampleJob();
        assertThat(job.getLogFileYn()).isEqualTo("N");

        // when
        job.markLogFileUploaded();

        // then
        assertThat(job.getLogFileYn()).isEqualTo("Y");
    }
}
