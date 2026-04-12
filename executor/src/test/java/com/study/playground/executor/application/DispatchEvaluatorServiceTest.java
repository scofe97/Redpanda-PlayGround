package com.study.playground.executor.application;

import com.study.playground.executor.config.ExecutorProperties;
import com.study.playground.executor.execution.application.DispatchEvaluatorService;
import com.study.playground.executor.execution.domain.model.ExecutionJob;
import com.study.playground.executor.execution.domain.model.ExecutionJobStatus;
import com.study.playground.executor.execution.domain.model.JobDefinitionInfo;
import com.study.playground.executor.execution.domain.port.out.ExecutionJobPort;
import com.study.playground.executor.execution.domain.port.out.JenkinsQueryPort;
import com.study.playground.executor.execution.domain.port.out.JobDefinitionQueryPort;
import com.study.playground.executor.execution.domain.port.out.PublishExecuteCommandPort;
import com.study.playground.executor.execution.domain.service.DispatchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("DispatchEvaluatorService 단위 테스트")
class DispatchEvaluatorServiceTest {

    @Mock
    PublishExecuteCommandPort publishPort;

    @Mock
    ExecutionJobPort jobPort;

    @Mock
    JenkinsQueryPort jenkinsQueryPort;

    @Mock
    JobDefinitionQueryPort jobDefinitionQueryPort;

    DispatchService dispatchService = new DispatchService();

    ExecutorProperties properties = new ExecutorProperties();

    DispatchEvaluatorService service;

    @BeforeEach
    void setUp() {
        properties.setMaxBatchSize(5);
        service = new DispatchEvaluatorService(
                publishPort
                , jobPort
                , jenkinsQueryPort
                , jobDefinitionQueryPort
                , dispatchService
                , properties
        );
    }

    private ExecutionJob pendingJob(String jobExcnId, String jobId) {
        return ExecutionJob.create(
                jobExcnId
                , "pipe-001"
                , jobId
                , 1
                , LocalDateTime.now()
                , "user-01"
        );
    }

    private JobDefinitionInfo defInfo(String jobId) {
        return new JobDefinitionInfo(jobId, "10", "20", 1L);
    }

    @Test
    @DisplayName("PENDING Job이 있으면 QUEUED로 전환하고 publishExecuteCommand를 호출해야 한다")
    void tryDispatch_withPendingJob_shouldQueueAndPublish() {
        ExecutionJob job = pendingJob("excn-001", "job-001");
        given(jobPort.findDispatchableJobs(5)).willReturn(List.of(job));
        given(jobDefinitionQueryPort.load("job-001")).willReturn(defInfo("job-001"));
        given(jenkinsQueryPort.isHealthy(1L)).willReturn(true);
        given(jobPort.countActiveJobsByJenkinsInstanceId(1L, List.of(
                ExecutionJobStatus.QUEUED, ExecutionJobStatus.SUBMITTED, ExecutionJobStatus.RUNNING))).willReturn(0);
        given(jenkinsQueryPort.getMaxExecutors(1L)).willReturn(2);
        given(jobPort.existsByJobIdAndStatusIn(eq("job-001"), any())).willReturn(false);

        service.tryDispatch();

        ArgumentCaptor<ExecutionJob> captor = ArgumentCaptor.forClass(ExecutionJob.class);
        verify(jobPort).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(ExecutionJobStatus.QUEUED);
        assertThat(captor.getValue().getBuildNo()).isNull();
        verify(publishPort).publishExecuteCommand(any());
        verify(jenkinsQueryPort, never()).queryNextBuildNumber(any(Long.class), any());
    }

    @Test
    @DisplayName("PENDING Job이 없으면 아무 작업도 하지 않아야 한다")
    void tryDispatch_noPendingJobs_shouldDoNothing() {
        given(jobPort.findDispatchableJobs(5)).willReturn(List.of());

        service.tryDispatch();

        verify(jobPort, never()).save(any());
        verify(publishPort, never()).publishExecuteCommand(any());
    }

    @Test
    @DisplayName("동일 jobId가 이미 QUEUED/RUNNING이면 publish를 호출하지 않아야 한다")
    void tryDispatch_duplicateJobId_shouldSkip() {
        ExecutionJob job = pendingJob("excn-001", "job-001");
        given(jobPort.findDispatchableJobs(5)).willReturn(List.of(job));
        given(jobDefinitionQueryPort.load("job-001")).willReturn(defInfo("job-001"));
        given(jenkinsQueryPort.isHealthy(1L)).willReturn(true);
        given(jobPort.countActiveJobsByJenkinsInstanceId(1L, List.of(
                ExecutionJobStatus.QUEUED, ExecutionJobStatus.SUBMITTED, ExecutionJobStatus.RUNNING))).willReturn(0);
        given(jenkinsQueryPort.getMaxExecutors(1L)).willReturn(1);
        given(jobPort.existsByJobIdAndStatusIn(eq("job-001"), any())).willReturn(true);

        service.tryDispatch();

        verify(publishPort, never()).publishExecuteCommand(any());
    }

    @Test
    @DisplayName("Jenkins 인스턴스가 unhealthy이면 publish를 호출하지 않아야 한다")
    void tryDispatch_unhealthyJenkins_shouldSkip() {
        ExecutionJob job = pendingJob("excn-001", "job-001");
        given(jobPort.findDispatchableJobs(5)).willReturn(List.of(job));
        given(jobDefinitionQueryPort.load("job-001")).willReturn(defInfo("job-001"));
        given(jenkinsQueryPort.isHealthy(1L)).willReturn(false);

        service.tryDispatch();

        verify(publishPort, never()).publishExecuteCommand(any());
        verify(jobPort, never()).save(any());
    }

    @Test
    @DisplayName("첫 번째 Job 처리 실패 시 두 번째 Job은 계속 처리되어야 한다")
    void tryDispatch_oneFailsOthersContinue() {
        ExecutionJob job1 = pendingJob("excn-001", "job-001");
        ExecutionJob job2 = pendingJob("excn-002", "job-002");
        given(jobPort.findDispatchableJobs(5)).willReturn(List.of(job1, job2));
        given(jobDefinitionQueryPort.load("job-001")).willReturn(defInfo("job-001"));
        given(jobDefinitionQueryPort.load("job-002")).willReturn(new JobDefinitionInfo("job-002", "10", "20", 2L));
        given(jenkinsQueryPort.isHealthy(1L)).willThrow(new RuntimeException("down"));
        given(jenkinsQueryPort.isHealthy(2L)).willReturn(true);
        given(jobPort.countActiveJobsByJenkinsInstanceId(2L, List.of(
                ExecutionJobStatus.QUEUED, ExecutionJobStatus.SUBMITTED, ExecutionJobStatus.RUNNING))).willReturn(0);
        given(jenkinsQueryPort.getMaxExecutors(2L)).willReturn(1);
        given(jobPort.existsByJobIdAndStatusIn(eq("job-002"), any())).willReturn(false);

        service.tryDispatch();

        verify(publishPort, times(1)).publishExecuteCommand(any());
    }

    @Test
    @DisplayName("job definition 조회 실패 시 retryOrFail 처리하고 다른 Job은 계속 처리해야 한다")
    void tryDispatch_missingDefinition_shouldRetryAndContinue() {
        ExecutionJob missingJob = pendingJob("excn-001", "job-001");
        ExecutionJob healthyJob = pendingJob("excn-002", "job-002");
        given(jobPort.findDispatchableJobs(5)).willReturn(List.of(missingJob, healthyJob));
        given(jobDefinitionQueryPort.load("job-001")).willThrow(new RuntimeException("missing"));
        given(jobDefinitionQueryPort.load("job-002")).willReturn(new JobDefinitionInfo("job-002", "10", "20", 2L));
        given(jenkinsQueryPort.isHealthy(2L)).willReturn(true);
        given(jobPort.countActiveJobsByJenkinsInstanceId(2L, List.of(
                ExecutionJobStatus.QUEUED, ExecutionJobStatus.SUBMITTED, ExecutionJobStatus.RUNNING))).willReturn(0);
        given(jenkinsQueryPort.getMaxExecutors(2L)).willReturn(1);
        given(jobPort.existsByJobIdAndStatusIn(eq("job-002"), any())).willReturn(false);

        service.tryDispatch();

        verify(jobPort, times(2)).save(any());
        assertThat(missingJob.getStatus()).isEqualTo(ExecutionJobStatus.PENDING);
        assertThat(missingJob.getRetryCnt()).isEqualTo(1);
        verify(publishPort, times(1)).publishExecuteCommand(any());
    }
}
