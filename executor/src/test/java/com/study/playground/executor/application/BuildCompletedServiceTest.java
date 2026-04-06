package com.study.playground.executor.application;

import com.study.playground.executor.dispatch.domain.model.ExecutionJob;
import com.study.playground.executor.dispatch.domain.model.ExecutionJobStatus;
import com.study.playground.executor.dispatch.domain.port.in.EvaluateDispatchUseCase;
import com.study.playground.executor.dispatch.domain.port.out.ExecutionJobPort;
import com.study.playground.executor.dispatch.domain.service.DispatchService;
import com.study.playground.executor.runner.application.BuildCompletedService;
import com.study.playground.executor.runner.domain.model.BuildCallback;
import com.study.playground.executor.runner.domain.port.out.NotifyJobCompletedPort;
import com.study.playground.executor.runner.domain.port.out.SaveBuildLogPort;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("BuildCompletedService 단위 테스트")
class BuildCompletedServiceTest {

    @Mock
    ExecutionJobPort jobPort;

    @Mock
    SaveBuildLogPort logPort;

    @Mock
    NotifyJobCompletedPort notifyPort;

    @Mock
    EvaluateDispatchUseCase dispatchUseCase;

    DispatchService dispatchService = new DispatchService();

    BuildCompletedService service;

    @BeforeEach
    void setUp() {
        service = new BuildCompletedService(jobPort, logPort, notifyPort, dispatchService, dispatchUseCase);
    }

    private static final int BUILD_NO = 7;

    private ExecutionJob runningJob(String jobExcnId) {
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
        job.recordBuildNo(BUILD_NO);
        job.transitionTo(ExecutionJobStatus.QUEUED);  // PENDING → QUEUED
        job.transitionTo(ExecutionJobStatus.RUNNING); // QUEUED → RUNNING
        return job;
    }

    @Test
    @DisplayName("로그가 있는 성공 완료 시 로그를 저장하고 success=true로 notify해야 한다")
    void handle_successWithLog_shouldSaveLogAndNotifySuccess() {
        // given
        ExecutionJob job = runningJob("excn-001");
        given(jobPort.findById("excn-001")).willReturn(Optional.of(job));
        given(logPort.save(eq("test-job"), eq("excn-001"), eq("log content"))).willReturn(true);
        BuildCallback callback = BuildCallback.completed("excn-001", BUILD_NO, "SUCCESS", "log content");

        // when
        service.handle(callback);

        // then
        verify(logPort).save("test-job", "excn-001", "log content");
        ArgumentCaptor<String> logFileYnCaptor = ArgumentCaptor.forClass(String.class);
        verify(notifyPort).notify(
                eq("excn-001")
                , eq("pipe-001")
                , eq(true)
                , eq("SUCCESS")
                , anyString()
                , logFileYnCaptor.capture()
                , eq(null)
        );
        assertThat(logFileYnCaptor.getValue()).isEqualTo("Y");
    }

    @Test
    @DisplayName("FAILURE 결과 수신 시 success=false로 notify해야 한다")
    void handle_failureWithLog_shouldSaveLogAndNotifyFailure() {
        // given
        ExecutionJob job = runningJob("excn-001");
        given(jobPort.findById("excn-001")).willReturn(Optional.of(job));
        given(logPort.save(eq("test-job"), eq("excn-001"), eq("error log"))).willReturn(true);
        BuildCallback callback = BuildCallback.completed("excn-001", BUILD_NO, "FAILURE", "error log");

        // when
        service.handle(callback);

        // then
        ArgumentCaptor<Boolean> successCaptor = ArgumentCaptor.forClass(Boolean.class);
        verify(notifyPort).notify(
                eq("excn-001")
                , eq("pipe-001")
                , successCaptor.capture()
                , eq("FAILURE")
                , anyString()
                , anyString()
                , anyString()
        );
        assertThat(successCaptor.getValue()).isFalse();
    }

    @Test
    @DisplayName("로그 내용이 없으면 logPort.save를 호출하지 않고 logFileYn='N'으로 notify해야 한다")
    void handle_noLogContent_shouldSkipLogSave() {
        // given
        ExecutionJob job = runningJob("excn-001");
        given(jobPort.findById("excn-001")).willReturn(Optional.of(job));
        BuildCallback callback = BuildCallback.completed("excn-001", BUILD_NO, "SUCCESS", null);

        // when
        service.handle(callback);

        // then
        verify(logPort, never()).save(anyString(), anyString(), anyString());
        ArgumentCaptor<String> logFileYnCaptor = ArgumentCaptor.forClass(String.class);
        verify(notifyPort).notify(
                any(), any(), any(Boolean.class), any()
                , eq(null)
                , logFileYnCaptor.capture()
                , any()
        );
        assertThat(logFileYnCaptor.getValue()).isEqualTo("N");
    }

    @Test
    @DisplayName("로그 저장 실패 시에도 상태 전환과 notify는 수행해야 한다")
    void handle_logSaveFails_shouldStillComplete() {
        // given
        ExecutionJob job = runningJob("excn-001");
        given(jobPort.findById("excn-001")).willReturn(Optional.of(job));
        given(logPort.save(eq("test-job"), eq("excn-001"), eq("log content"))).willReturn(false);
        BuildCallback callback = BuildCallback.completed("excn-001", BUILD_NO, "SUCCESS", "log content");

        // when
        service.handle(callback);

        // then
        ArgumentCaptor<ExecutionJob> jobCaptor = ArgumentCaptor.forClass(ExecutionJob.class);
        verify(jobPort).save(jobCaptor.capture());
        assertThat(jobCaptor.getValue().getStatus()).isEqualTo(ExecutionJobStatus.SUCCESS);
        assertThat(jobCaptor.getValue().getLogFileYn()).isEqualTo("N");

        ArgumentCaptor<String> logFileYnCaptor = ArgumentCaptor.forClass(String.class);
        verify(notifyPort).notify(
                any(), any(), any(Boolean.class), any()
                , eq(null)
                , logFileYnCaptor.capture()
                , any()
        );
        assertThat(logFileYnCaptor.getValue()).isEqualTo("N");
    }

    @Test
    @DisplayName("Job을 찾지 못하면 아무 처리도 하지 않아야 한다")
    void handle_jobNotFound_shouldWarn() {
        // given
        given(jobPort.findById("excn-999")).willReturn(Optional.empty());
        BuildCallback callback = BuildCallback.completed("excn-999", 1, "SUCCESS", null);

        // when
        service.handle(callback);

        // then
        verify(logPort, never()).save(anyString(), anyString(), anyString());
        verify(notifyPort, never()).notify(any(), any(), any(Boolean.class), any(), any(), any(), any());
    }

    @Test
    @DisplayName("터미널 상태 Job은 처리를 건너뛰어야 한다")
    void handle_terminalJob_shouldIgnore() {
        // given — 이미 SUCCESS 상태
        ExecutionJob job = runningJob("excn-001");
        job.transitionTo(ExecutionJobStatus.SUCCESS); // RUNNING → SUCCESS
        given(jobPort.findById("excn-001")).willReturn(Optional.of(job));
        BuildCallback callback = BuildCallback.completed("excn-001", BUILD_NO, "SUCCESS", "log");

        // when
        service.handle(callback);

        // then
        verify(logPort, never()).save(anyString(), anyString(), anyString());
        verify(notifyPort, never()).notify(any(), any(), any(Boolean.class), any(), any(), any(), any());
    }
}
