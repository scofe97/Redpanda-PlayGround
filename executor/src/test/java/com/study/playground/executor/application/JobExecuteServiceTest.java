package com.study.playground.executor.application;

import com.study.playground.executor.config.ExecutorProperties;
import com.study.playground.executor.execution.domain.model.ExecutionJob;
import com.study.playground.executor.execution.domain.model.ExecutionJobStatus;
import com.study.playground.executor.execution.domain.model.JobDefinitionInfo;
import com.study.playground.executor.execution.domain.port.out.ExecutionJobPort;
import com.study.playground.executor.execution.domain.port.out.JenkinsQueryPort;
import com.study.playground.executor.execution.domain.port.out.JenkinsTriggerPort;
import com.study.playground.executor.execution.domain.port.out.JobDefinitionQueryPort;
import com.study.playground.executor.execution.domain.service.DispatchService;
import com.study.playground.executor.execution.application.JobExecuteService;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("JobExecuteService 단위 테스트")
class JobExecuteServiceTest {

    @Mock
    ExecutionJobPort jobPort;

    @Mock
    JenkinsTriggerPort jenkinsTriggerPort;

    @Mock
    JenkinsQueryPort jenkinsQueryPort;

    @Mock
    JobDefinitionQueryPort jobDefinitionQueryPort;

    DispatchService dispatchService = new DispatchService();

    ExecutorProperties properties = new ExecutorProperties();

    JobExecuteService service;

    @BeforeEach
    void setUp() {
        properties.setJobMaxRetries(2);
        service = new JobExecuteService(
                jobPort, jenkinsTriggerPort, jenkinsQueryPort
                , jobDefinitionQueryPort, dispatchService, properties
        );
    }

    private static final JobDefinitionInfo DEF_INFO =
            new JobDefinitionInfo("job-001", "10", "20", 1L);

    private ExecutionJob queuedJob(String jobExcnId) {
        ExecutionJob job = ExecutionJob.create(
                jobExcnId
                , "pipe-001"
                , "job-001"
                , 1
                , LocalDateTime.now()
                , "user-01"
        );
        job.transitionTo(ExecutionJobStatus.QUEUED); // PENDING → QUEUED
        return job;
    }

    @Test
    @DisplayName("QUEUED 상태 Job은 빌드를 트리거하고 SUBMITTED로 전환 + buildNo 기록해야 한다")
    void execute_queuedJob_shouldTriggerBuildAndSubmit() {
        // given
        ExecutionJob job = queuedJob("excn-001");
        given(jobPort.findById("excn-001")).willReturn(Optional.of(job));
        given(jobDefinitionQueryPort.load("job-001")).willReturn(DEF_INFO);
        given(jenkinsQueryPort.queryNextBuildNumber(1L, "10/20/job-001")).willReturn(42);
        willDoNothing().given(jenkinsTriggerPort).triggerBuild(1L, "10/20/job-001", "job-001");

        // when
        service.execute("excn-001");

        // then
        verify(jenkinsTriggerPort).triggerBuild(1L, "10/20/job-001", "job-001");
        ArgumentCaptor<ExecutionJob> captor = ArgumentCaptor.forClass(ExecutionJob.class);
        verify(jobPort).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(ExecutionJobStatus.SUBMITTED);
        assertThat(captor.getValue().getBuildNo()).isEqualTo(42);
    }

    @Test
    @DisplayName("QUEUED가 아닌 Job은 triggerBuild를 호출하지 않아야 한다")
    void execute_nonQueuedJob_shouldIgnore() {
        // given — PENDING 상태 Job
        ExecutionJob job = ExecutionJob.create(
                "excn-001", "pipe-001", "job-001"
                , 1, LocalDateTime.now(), "user-01"
        );
        given(jobPort.findById("excn-001")).willReturn(Optional.of(job));

        // when
        service.execute("excn-001");

        // then
        verify(jenkinsTriggerPort, never()).triggerBuild(any(Long.class), anyString(), anyString());
    }

    @Test
    @DisplayName("존재하지 않는 Job ID는 예외를 던져 Kafka 재시도를 유도해야 한다")
    void execute_unknownJob_shouldThrow() {
        // given
        given(jobPort.findById("excn-999")).willReturn(Optional.empty());

        // when
        assertThatThrownBy(() -> service.execute("excn-999"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unknown jobExcnId=excn-999");

        // then
        verify(jenkinsTriggerPort, never()).triggerBuild(any(Long.class), anyString(), anyString());
        verify(jobPort, never()).save(any());
    }

    @Test
    @DisplayName("triggerBuild 실패 시 재시도 가능하면 PENDING으로 전환하고 retryCnt를 증가해야 한다")
    void execute_triggerFails_shouldRetry() {
        // given
        ExecutionJob job = queuedJob("excn-001");
        given(jobPort.findById("excn-001")).willReturn(Optional.of(job));
        given(jobDefinitionQueryPort.load("job-001")).willReturn(DEF_INFO);
        willThrow(new RuntimeException("Jenkins 연결 실패"))
                .given(jenkinsTriggerPort).triggerBuild(eq(1L), eq("10/20/job-001"), eq("job-001"));

        // when
        service.execute("excn-001");

        // then
        ArgumentCaptor<ExecutionJob> captor = ArgumentCaptor.forClass(ExecutionJob.class);
        verify(jobPort).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(ExecutionJobStatus.PENDING);
        assertThat(captor.getValue().getRetryCnt()).isEqualTo(1);
    }

    @Test
    @DisplayName("triggerBuild 실패 시 재시도 횟수 초과하면 FAILURE로 전환해야 한다")
    void execute_triggerFails_retryExceeded_shouldFail() {
        // given — retryCnt=2 이미 소진
        ExecutionJob job = queuedJob("excn-001");
        job.incrementRetry(); // retryCnt=1
        job.incrementRetry(); // retryCnt=2
        given(jobPort.findById("excn-001")).willReturn(Optional.of(job));
        given(jobDefinitionQueryPort.load("job-001")).willReturn(DEF_INFO);
        willThrow(new RuntimeException("Jenkins 연결 실패"))
                .given(jenkinsTriggerPort).triggerBuild(eq(1L), eq("10/20/job-001"), eq("job-001"));

        // when
        service.execute("excn-001");

        // then
        ArgumentCaptor<ExecutionJob> captor = ArgumentCaptor.forClass(ExecutionJob.class);
        verify(jobPort).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(ExecutionJobStatus.FAILURE);
    }
}
