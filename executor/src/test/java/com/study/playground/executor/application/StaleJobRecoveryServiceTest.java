package com.study.playground.executor.application;

import com.study.playground.executor.config.ExecutorProperties;
import com.study.playground.executor.execution.application.StaleJobRecoveryService;
import com.study.playground.executor.execution.domain.model.BuildStatusResult;
import com.study.playground.executor.execution.domain.model.ExecutionJob;
import com.study.playground.executor.execution.domain.model.ExecutionJobStatus;
import com.study.playground.executor.execution.domain.model.JobDefinitionInfo;
import com.study.playground.executor.execution.domain.port.out.ExecutionJobPort;
import com.study.playground.executor.execution.domain.port.out.JenkinsQueryPort;
import com.study.playground.executor.execution.domain.port.out.JobDefinitionQueryPort;
import com.study.playground.executor.execution.domain.port.out.NotifyJobCompletedPort;
import com.study.playground.executor.execution.domain.port.out.NotifyJobStartedPort;
import com.study.playground.executor.execution.domain.service.DispatchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("StaleJobRecoveryService 단위 테스트")
class StaleJobRecoveryServiceTest {

    @Mock ExecutionJobPort jobPort;
    @Mock JenkinsQueryPort jenkinsQueryPort;
    @Mock JobDefinitionQueryPort jobDefinitionQueryPort;
    @Mock NotifyJobCompletedPort notifyCompletedPort;
    @Mock NotifyJobStartedPort notifyStartedPort;

    DispatchService dispatchService = new DispatchService();
    ExecutorProperties properties = new ExecutorProperties();

    StaleJobRecoveryService service;

    private static final int BUILD_NO = 7;
    // jenkinsJobPath() = projectId + "/" + presetId + "/" + jobId = "my-folder/my-job/job-001"
    private static final JobDefinitionInfo DEF_INFO =
            new JobDefinitionInfo("job-001", "my-folder", "my-job", 1L);

    @BeforeEach
    void setUp() {
        properties.setSubmittedStaleSeconds(30);
        properties.setRunningStaleMinutes(10);
        properties.setJobMaxRetries(2);
        service = new StaleJobRecoveryService(
                jobPort, jenkinsQueryPort, jobDefinitionQueryPort
                , dispatchService, notifyCompletedPort
                , notifyStartedPort, properties
        );
    }

    private ExecutionJob submittedJob(String jobExcnId) {
        ExecutionJob job = ExecutionJob.create(
                jobExcnId, "pipe-001", "job-001"
                , 1, LocalDateTime.now().minusMinutes(2), "user-01"
        );
        job.transitionTo(ExecutionJobStatus.QUEUED);
        job.recordBuildNo(BUILD_NO);
        job.transitionTo(ExecutionJobStatus.SUBMITTED);
        return job;
    }

    private ExecutionJob runningJob(String jobExcnId) {
        ExecutionJob job = submittedJob(jobExcnId);
        job.transitionTo(ExecutionJobStatus.RUNNING);
        return job;
    }

    @Nested
    @DisplayName("recoverStaleSubmitted")
    class RecoverStaleSubmitted {

        @Test
        @DisplayName("BUILDING 상태 → RUNNING 전이 + 시작 알림")
        void building_shouldTransitionToRunningAndNotify() {
            // given
            ExecutionJob job = submittedJob("excn-001");
            given(jobPort.findByStatusAndMdfcnDtBefore(eq(ExecutionJobStatus.SUBMITTED), any()))
                    .willReturn(List.of(job));
            given(jobDefinitionQueryPort.load("job-001")).willReturn(DEF_INFO);
            given(jenkinsQueryPort.queryBuildStatus(1L, "my-folder/my-job/job-001", BUILD_NO))
                    .willReturn(BuildStatusResult.building());

            // when
            service.recoverStaleSubmitted();

            // then
            verify(jobPort).save(any());
            verify(notifyStartedPort).notify(
                    eq("excn-001"), eq("pipe-001"), eq("job-001"), eq(BUILD_NO));
        }

        @Test
        @DisplayName("COMPLETED 상태 → 터미널 전이 + 완료 알림")
        void completed_shouldTransitionToTerminalAndNotify() {
            // given
            ExecutionJob job = submittedJob("excn-001");
            given(jobPort.findByStatusAndMdfcnDtBefore(eq(ExecutionJobStatus.SUBMITTED), any()))
                    .willReturn(List.of(job));
            given(jobDefinitionQueryPort.load("job-001")).willReturn(DEF_INFO);
            given(jenkinsQueryPort.queryBuildStatus(1L, "my-folder/my-job/job-001", BUILD_NO))
                    .willReturn(BuildStatusResult.completed("SUCCESS"));

            // when
            service.recoverStaleSubmitted();

            // then
            verify(jobPort).save(any());
            verify(notifyCompletedPort).notify(
                    eq("excn-001"), eq("pipe-001"), eq(true)
                    , eq("SUCCESS"), isNull(), eq("N"), isNull());
        }

        @Test
        @DisplayName("NOT_FOUND + 체류 시간 짧음 → 스킵 (아직 큐 대기)")
        void notFound_shortDuration_shouldSkip() {
            // given — mdfcnDt가 방금 전 (submittedStaleSeconds*3 미만)
            ExecutionJob job = submittedJob("excn-001");
            given(jobPort.findByStatusAndMdfcnDtBefore(eq(ExecutionJobStatus.SUBMITTED), any()))
                    .willReturn(List.of(job));
            given(jobDefinitionQueryPort.load("job-001")).willReturn(DEF_INFO);
            given(jenkinsQueryPort.queryBuildStatus(1L, "my-folder/my-job/job-001", BUILD_NO))
                    .willReturn(BuildStatusResult.notFound());

            // when
            service.recoverStaleSubmitted();

            // then — save 호출하지 않음
            verify(jobPort, never()).save(any());
        }

        @Test
        @DisplayName("stale Job이 없으면 아무 작업도 하지 않는다")
        void noStaleJobs_shouldDoNothing() {
            // given
            given(jobPort.findByStatusAndMdfcnDtBefore(eq(ExecutionJobStatus.SUBMITTED), any()))
                    .willReturn(List.of());

            // when
            service.recoverStaleSubmitted();

            // then
            verify(jenkinsQueryPort, never()).queryBuildStatus(anyLong(), anyString(), anyInt());
        }
    }

    @Nested
    @DisplayName("recoverStaleRunning")
    class RecoverStaleRunning {

        @Test
        @DisplayName("BUILDING → 여전히 실행 중이면 스킵")
        void building_shouldSkip() {
            // given
            ExecutionJob job = runningJob("excn-001");
            given(jobPort.findByStatusAndBgngDtBefore(eq(ExecutionJobStatus.RUNNING), any()))
                    .willReturn(List.of(job));
            given(jobDefinitionQueryPort.load("job-001")).willReturn(DEF_INFO);
            given(jenkinsQueryPort.queryBuildStatus(1L, "my-folder/my-job/job-001", BUILD_NO))
                    .willReturn(BuildStatusResult.building());

            // when
            service.recoverStaleRunning();

            // then
            verify(jobPort, never()).save(any());
            verify(notifyCompletedPort, never()).notify(any(), any(), anyBoolean()
                    , any(), any(), any(), any());
        }

        @Test
        @DisplayName("COMPLETED → 터미널 전이 + 완료 알림")
        void completed_shouldTransitionAndNotify() {
            // given
            ExecutionJob job = runningJob("excn-001");
            given(jobPort.findByStatusAndBgngDtBefore(eq(ExecutionJobStatus.RUNNING), any()))
                    .willReturn(List.of(job));
            given(jobDefinitionQueryPort.load("job-001")).willReturn(DEF_INFO);
            given(jenkinsQueryPort.queryBuildStatus(1L, "my-folder/my-job/job-001", BUILD_NO))
                    .willReturn(BuildStatusResult.completed("FAILURE"));

            // when
            service.recoverStaleRunning();

            // then
            verify(jobPort).save(any());
            verify(notifyCompletedPort).notify(
                    eq("excn-001"), eq("pipe-001"), eq(false)
                    , eq("FAILURE"), isNull(), eq("N"), eq("FAILURE"));
        }

        @Test
        @DisplayName("NOT_FOUND → retryOrFail")
        void notFound_shouldRetryOrFail() {
            // given
            ExecutionJob job = runningJob("excn-001");
            given(jobPort.findByStatusAndBgngDtBefore(eq(ExecutionJobStatus.RUNNING), any()))
                    .willReturn(List.of(job));
            given(jobDefinitionQueryPort.load("job-001")).willReturn(DEF_INFO);
            given(jenkinsQueryPort.queryBuildStatus(1L, "my-folder/my-job/job-001", BUILD_NO))
                    .willReturn(BuildStatusResult.notFound());

            // when
            service.recoverStaleRunning();

            // then — retryOrFail 호출되어 PENDING으로 전환 (retryCnt=0 < maxRetries=2)
            verify(jobPort).save(any());
        }
    }
}
