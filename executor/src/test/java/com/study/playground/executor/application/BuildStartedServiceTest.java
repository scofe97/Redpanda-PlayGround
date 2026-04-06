package com.study.playground.executor.application;

import com.study.playground.executor.dispatch.domain.model.ExecutionJob;
import com.study.playground.executor.dispatch.domain.model.ExecutionJobStatus;
import com.study.playground.executor.dispatch.domain.port.in.EvaluateDispatchUseCase;
import com.study.playground.executor.dispatch.domain.port.out.ExecutionJobPort;
import com.study.playground.executor.dispatch.domain.service.DispatchService;
import com.study.playground.executor.runner.application.BuildStartedService;
import com.study.playground.executor.runner.domain.model.BuildCallback;
import com.study.playground.executor.runner.domain.port.out.NotifyJobStartedPort;
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

    @Mock
    EvaluateDispatchUseCase dispatchUseCase;

    DispatchService dispatchService = new DispatchService();

    BuildStartedService service;

    @BeforeEach
    void setUp() {
        service = new BuildStartedService(jobPort, notifyStartedPort, dispatchService, dispatchUseCase);
    }

    private static final int BUILD_NO = 7;

    private ExecutionJob queuedJob(String jobExcnId) {
        ExecutionJob job = ExecutionJob.create(
                jobExcnId
                , "pipe-001"
                , "job-001"
                , 1L
                , "test-job"
                , 1
                , LocalDateTime.now()
                , "user-01"
        );
        job.transitionTo(ExecutionJobStatus.QUEUED); // PENDING → QUEUED
        return job;
    }

    @Test
    @DisplayName("유효한 콜백 수신 시 RUNNING으로 전환하고 알림 및 tryDispatch를 호출해야 한다")
    void handle_validCallback_shouldTransitionToRunningAndNotify() {
        // given
        ExecutionJob job = queuedJob("excn-001");
        given(jobPort.findActiveByJobId("job-001")).willReturn(Optional.of(job));
        BuildCallback callback = BuildCallback.started("job-001", BUILD_NO);

        // when
        service.handle(callback);

        // then
        ArgumentCaptor<ExecutionJob> captor = ArgumentCaptor.forClass(ExecutionJob.class);
        verify(jobPort).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(ExecutionJobStatus.RUNNING);
        verify(notifyStartedPort).notify("excn-001", "pipe-001", "job-001", 7);
        verify(dispatchUseCase).tryDispatch();
    }

    @Test
    @DisplayName("Job을 찾지 못하면 notify를 호출하지 않아야 한다")
    void handle_jobNotFound_shouldWarn() {
        // given
        given(jobPort.findActiveByJobId("unknown-job")).willReturn(Optional.empty());
        BuildCallback callback = BuildCallback.started("unknown-job", 1);

        // when
        service.handle(callback);

        // then
        verify(notifyStartedPort, never()).notify(any(), any(), any(), any(Integer.class));
        verify(dispatchUseCase, never()).tryDispatch();
    }

    @Test
    @DisplayName("터미널 상태 Job은 notify를 호출하지 않아야 한다")
    void handle_terminalJob_shouldIgnore() {
        // given — SUCCESS(터미널) 상태 Job
        ExecutionJob job = queuedJob("excn-001");
        job.transitionTo(ExecutionJobStatus.RUNNING); // QUEUED → RUNNING
        job.transitionTo(ExecutionJobStatus.SUCCESS); // RUNNING → SUCCESS
        given(jobPort.findActiveByJobId("job-001")).willReturn(Optional.of(job));
        BuildCallback callback = BuildCallback.started("job-001", BUILD_NO);

        // when
        service.handle(callback);

        // then
        verify(notifyStartedPort, never()).notify(any(), any(), any(), any(Integer.class));
        verify(dispatchUseCase, never()).tryDispatch();
    }
}
