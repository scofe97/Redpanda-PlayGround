package com.study.playground.executor.application;

import com.study.playground.executor.execution.domain.model.ExecutionJob;
import com.study.playground.executor.execution.domain.model.ExecutionJobStatus;
import com.study.playground.executor.execution.domain.port.out.ExecutionJobPort;
import com.study.playground.executor.execution.domain.service.DispatchService;
import com.study.playground.executor.execution.application.BuildStartedService;
import com.study.playground.executor.execution.domain.model.BuildCallback;
import com.study.playground.executor.execution.domain.port.out.NotifyJobStartedPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("BuildStartedService 단위 테스트")
class BuildStartedServiceTest {

    @Mock
    ExecutionJobPort jobPort;

    @Mock
    NotifyJobStartedPort notifyStartedPort;

    DispatchService dispatchService = new DispatchService();

    BuildStartedService service;

    @BeforeEach
    void setUp() {
        service = new BuildStartedService(jobPort, notifyStartedPort, dispatchService);
    }

    private static final int BUILD_NO = 7;

    private ExecutionJob submittedJob(String jobExcnId) {
        ExecutionJob job = ExecutionJob.create(
                jobExcnId
                , "pipe-001"
                , "job-001"
                , 1
                , LocalDateTime.now()
                , "user-01"
        );
        job.transitionTo(ExecutionJobStatus.QUEUED);     // PENDING → QUEUED
        job.recordBuildNo(BUILD_NO);
        job.transitionTo(ExecutionJobStatus.SUBMITTED);  // QUEUED → SUBMITTED
        return job;
    }

    @Test
    @DisplayName("유효한 콜백 수신 시 RUNNING으로 전환하고 알림을 호출해야 한다")
    void handle_validCallback_shouldTransitionToRunningAndNotify() {
        // given
        ExecutionJob job = submittedJob("excn-001");
        given(jobPort.findByJobIdAndBuildNo("job-001", BUILD_NO)).willReturn(Optional.of(job));
        BuildCallback callback = BuildCallback.started("job-001", BUILD_NO);

        // when
        service.handle(callback);

        // then
        ArgumentCaptor<ExecutionJob> captor = ArgumentCaptor.forClass(ExecutionJob.class);
        verify(jobPort).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(ExecutionJobStatus.RUNNING);
        verify(notifyStartedPort).notify("excn-001", "pipe-001", "job-001", 7);
    }

    @Test
    @DisplayName("Job을 찾지 못하면 notify를 호출하지 않아야 한다")
    void handle_jobNotFound_shouldWarn() {
        // given
        given(jobPort.findByJobIdAndBuildNo("unknown-job", 1)).willReturn(Optional.empty());
        BuildCallback callback = BuildCallback.started("unknown-job", 1);

        // when
        service.handle(callback);

        // then
        verify(notifyStartedPort, never()).notify(any(), any(), any(), any(Integer.class));
    }

    @Test
    @DisplayName("터미널 상태 Job은 notify를 호출하지 않아야 한다")
    void handle_terminalJob_shouldIgnore() {
        // given — SUCCESS(터미널) 상태 Job
        ExecutionJob job = submittedJob("excn-001");
        job.transitionTo(ExecutionJobStatus.RUNNING);  // SUBMITTED → RUNNING
        job.transitionTo(ExecutionJobStatus.SUCCESS);  // RUNNING → SUCCESS
        given(jobPort.findByJobIdAndBuildNo("job-001", BUILD_NO)).willReturn(Optional.of(job));
        BuildCallback callback = BuildCallback.started("job-001", BUILD_NO);

        // when
        service.handle(callback);

        // then
        verify(notifyStartedPort, never()).notify(any(), any(), any(), any(Integer.class));
    }

    @Test
    @DisplayName("이미 RUNNING 상태인 Job은 중복 전이하지 않아야 한다")
    void handle_alreadyRunning_shouldSkip() {
        // given — SUBMITTED → RUNNING 상태
        ExecutionJob job = submittedJob("excn-001");
        job.transitionTo(ExecutionJobStatus.RUNNING);  // SUBMITTED → RUNNING
        given(jobPort.findByJobIdAndBuildNo("job-001", BUILD_NO)).willReturn(Optional.of(job));
        BuildCallback callback = BuildCallback.started("job-001", BUILD_NO);

        // when
        service.handle(callback);

        // then — save, notify 모두 호출하지 않아야 함
        verify(jobPort, never()).save(any());
        verify(notifyStartedPort, never()).notify(any(), any(), any(), any(Integer.class));
    }
}
