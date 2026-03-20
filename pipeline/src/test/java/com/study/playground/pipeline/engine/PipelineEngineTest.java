package com.study.playground.pipeline.engine;

import com.study.playground.pipeline.domain.*;
import com.study.playground.pipeline.engine.step.*;
import com.study.playground.pipeline.event.PipelineEventProducer;
import com.study.playground.pipeline.mapper.PipelineExecutionMapper;
import com.study.playground.pipeline.mapper.PipelineJobExecutionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PipelineEngineTest {

    @Mock private JenkinsCloneAndBuildStep gitCloneAndBuild;
    @Mock private NexusDownloadStep nexusDownload;
    @Mock private RegistryImagePullStep imagePull;
    @Mock private JenkinsDeployStep deploy;
    @Mock private PipelineExecutionMapper executionMapper;
    @Mock private PipelineJobExecutionMapper jobExecutionMapper;
    @Mock private PipelineEventProducer eventProducer;
    @Mock private SagaCompensator sagaCompensator;
    @Mock private DagExecutionCoordinator dagCoordinator;

    private PipelineEngine pipelineEngine;
    private PipelineExecution execution;

    @BeforeEach
    void setUp() {
        pipelineEngine = new PipelineEngine(
                gitCloneAndBuild, nexusDownload, imagePull, deploy,
                executionMapper, jobExecutionMapper, eventProducer, sagaCompensator, dagCoordinator);

        execution = new PipelineExecution();
        execution.setId(UUID.randomUUID());
        execution.setTicketId(1L);
        execution.setStatus(PipelineStatus.PENDING);
    }

    private PipelineJobExecution createJobExecution(int order, PipelineJobType type) {
        PipelineJobExecution je = new PipelineJobExecution();
        je.setId((long) order);
        je.setJobOrder(order);
        je.setJobType(type);
        je.setJobName("Job " + order);
        je.setStatus(JobExecutionStatus.PENDING);
        return je;
    }

    // ---------------------------------------------------------------------------
    // 정상 실행
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("모든 스텝 성공 시 RUNNING → SUCCESS 상태 전이")
    void 모든스텝_성공시_SUCCESS_상태() throws Exception {
        // Given
        PipelineJobExecution je1 = createJobExecution(1, PipelineJobType.BUILD);
        PipelineJobExecution je2 = createJobExecution(2, PipelineJobType.DEPLOY);
        execution.setJobExecutions(List.of(je1, je2));
        when(jobExecutionMapper.findByExecutionId(execution.getId())).thenReturn(List.of(je1, je2));

        // When
        pipelineEngine.execute(execution);

        // Then - RUNNING 전환이 먼저, SUCCESS 전환이 나중
        verify(executionMapper).updateStatus(
                eq(execution.getId()), eq(PipelineStatus.RUNNING.name()), isNull(), isNull());
        verify(gitCloneAndBuild).execute(execution, je1);
        verify(deploy).execute(execution, je2);
        verify(executionMapper).updateStatus(
                eq(execution.getId()), eq(PipelineStatus.SUCCESS.name()),
                any(LocalDateTime.class), isNull());
        verify(sagaCompensator, never()).compensate(any(), anyInt(), any());
    }

    @Test
    @DisplayName("모든 스텝 성공 시 각 스텝 RUNNING → SUCCESS 상태 기록")
    void 모든스텝_성공시_각스텝_상태기록() throws Exception {
        // Given
        PipelineJobExecution je1 = createJobExecution(1, PipelineJobType.BUILD);
        execution.setJobExecutions(List.of(je1));
        when(jobExecutionMapper.findByExecutionId(execution.getId())).thenReturn(List.of(je1));

        // When
        pipelineEngine.execute(execution);

        // Then - RUNNING 기록 후 SUCCESS 기록
        verify(jobExecutionMapper).updateStatus(eq(1L), eq(JobExecutionStatus.RUNNING.name()), isNull(), any(LocalDateTime.class));
        verify(jobExecutionMapper).updateStatus(
                eq(1L), eq(JobExecutionStatus.SUCCESS.name()), any(), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("모든 스텝 성공 시 실행 완료 이벤트 발행")
    void 모든스텝_성공시_완료이벤트_발행() throws Exception {
        // Given
        PipelineJobExecution je1 = createJobExecution(1, PipelineJobType.BUILD);
        execution.setJobExecutions(List.of(je1));
        when(jobExecutionMapper.findByExecutionId(execution.getId())).thenReturn(List.of(je1));
        // BUILD 타입은 gitCloneAndBuild executor에 매핑됨

        // When
        pipelineEngine.execute(execution);

        // Then
        verify(eventProducer).publishExecutionCompleted(
                eq(execution),
                eq(com.study.playground.avro.common.PipelineStatus.SUCCESS),
                anyLong(),
                isNull());
    }

    // ---------------------------------------------------------------------------
    // SAGA 보상 트랜잭션
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("첫 번째 스텝 실패 시 SagaCompensator 호출")
    void 첫번째스텝_실패시_SAGA_보상호출() throws Exception {
        // Given
        PipelineJobExecution je1 = createJobExecution(1, PipelineJobType.BUILD);
        execution.setJobExecutions(List.of(je1));
        when(jobExecutionMapper.findByExecutionId(execution.getId())).thenReturn(List.of(je1));

        doThrow(new RuntimeException("Git clone failed")).when(gitCloneAndBuild).execute(execution, je1);

        // When
        pipelineEngine.execute(execution);

        // Then
        verify(sagaCompensator).compensate(eq(execution), eq(1), any());
        verify(executionMapper).updateStatus(
                eq(execution.getId()), eq(PipelineStatus.FAILED.name()),
                any(LocalDateTime.class), eq("Git clone failed"));
    }

    @Test
    @DisplayName("두 번째 스텝 실패 시 SagaCompensator 호출 (job order=2)")
    void 두번째스텝_실패시_SAGA_보상호출() throws Exception {
        // Given
        PipelineJobExecution je1 = createJobExecution(1, PipelineJobType.BUILD);
        PipelineJobExecution je2 = createJobExecution(2, PipelineJobType.DEPLOY);
        execution.setJobExecutions(List.of(je1, je2));
        when(jobExecutionMapper.findByExecutionId(execution.getId())).thenReturn(List.of(je1, je2));

        doThrow(new RuntimeException("Deploy failed")).when(deploy).execute(execution, je2);

        // When
        pipelineEngine.execute(execution);

        // Then - failedJobOrder는 je.getJobOrder() = 2
        verify(sagaCompensator).compensate(eq(execution), eq(2), any());
        verify(executionMapper).updateStatus(
                eq(execution.getId()), eq(PipelineStatus.FAILED.name()),
                any(LocalDateTime.class), eq("Deploy failed"));
    }

    @Test
    @DisplayName("스텝 실패 시 FAILED 이벤트 발행")
    void 스텝_실패시_FAILED_이벤트_발행() throws Exception {
        // Given
        PipelineJobExecution je1 = createJobExecution(1, PipelineJobType.DEPLOY);
        execution.setJobExecutions(List.of(je1));
        when(jobExecutionMapper.findByExecutionId(execution.getId())).thenReturn(List.of(je1));

        doThrow(new RuntimeException("Deploy error")).when(deploy).execute(execution, je1);

        // When
        pipelineEngine.execute(execution);

        // Then
        verify(jobExecutionMapper).updateStatus(
                eq(1L), eq(JobExecutionStatus.FAILED.name()), eq("Deploy error"), any(LocalDateTime.class));
        verify(eventProducer).publishJobExecutionChanged(execution, je1, JobExecutionStatus.FAILED);
        verify(eventProducer).publishExecutionCompleted(
                eq(execution),
                eq(com.study.playground.avro.common.PipelineStatus.FAILED),
                anyLong(),
                eq("Deploy error"));
    }

    @Test
    @DisplayName("등록되지 않은 JobType 실행 시 IllegalStateException 발생 후 SAGA 호출")
    void 미등록_JobType_예외후_SAGA_호출() throws Exception {
        // Given - ARTIFACT_DOWNLOAD는 nexusDownload에 매핑되어 있음
        // IMAGE_PULL도 imagePull에 매핑되어 있음
        // 여기서는 직접 스텝을 목킹할 수 없으므로 등록된 executor가 예외를 던지도록 구성
        PipelineJobExecution je1 = createJobExecution(1, PipelineJobType.ARTIFACT_DOWNLOAD);
        execution.setJobExecutions(List.of(je1));
        when(jobExecutionMapper.findByExecutionId(execution.getId())).thenReturn(List.of(je1));

        doThrow(new RuntimeException("Nexus unreachable")).when(nexusDownload).execute(execution, je1);

        // When
        pipelineEngine.execute(execution);

        // Then
        verify(sagaCompensator).compensate(eq(execution), eq(1), any());
        verify(executionMapper).updateStatus(
                eq(execution.getId()), eq(PipelineStatus.FAILED.name()),
                any(LocalDateTime.class), eq("Nexus unreachable"));
    }

    // ---------------------------------------------------------------------------
    // Break-and-Resume (Webhook 대기)
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("Webhook 대기 시 스레드 해제 - SUCCESS/FAILED 상태 전이 없음")
    void 웹훅대기시_스레드해제_완료상태없음() throws Exception {
        // Given
        PipelineJobExecution je1 = createJobExecution(1, PipelineJobType.BUILD);
        execution.setJobExecutions(List.of(je1));
        when(jobExecutionMapper.findByExecutionId(execution.getId())).thenReturn(List.of(je1));

        doAnswer(invocation -> {
            PipelineJobExecution s = invocation.getArgument(1);
            s.setWaitingForWebhook(true);
            return null;
        }).when(gitCloneAndBuild).execute(any(PipelineExecution.class), any(PipelineJobExecution.class));

        // When
        pipelineEngine.execute(execution);

        // Then - 보상 없음, 완료 상태 전이 없음
        verify(sagaCompensator, never()).compensate(any(), anyInt(), any());
        verify(executionMapper, never()).updateStatus(
                eq(execution.getId()), eq(PipelineStatus.SUCCESS.name()), any(), any());
        verify(executionMapper, never()).updateStatus(
                eq(execution.getId()), eq(PipelineStatus.FAILED.name()), any(), any());
    }

    @Test
    @DisplayName("Webhook 대기 시 WAITING_WEBHOOK 상태 기록")
    void 웹훅대기시_WAITING_WEBHOOK_상태기록() throws Exception {
        // Given
        PipelineJobExecution je1 = createJobExecution(1, PipelineJobType.BUILD);
        execution.setJobExecutions(List.of(je1));
        when(jobExecutionMapper.findByExecutionId(execution.getId())).thenReturn(List.of(je1));

        doAnswer(invocation -> {
            PipelineJobExecution s = invocation.getArgument(1);
            s.setWaitingForWebhook(true);
            return null;
        }).when(gitCloneAndBuild).execute(any(PipelineExecution.class), any(PipelineJobExecution.class));

        // When
        pipelineEngine.execute(execution);

        // Then
        verify(jobExecutionMapper).updateStatus(
                eq(1L), eq(JobExecutionStatus.WAITING_WEBHOOK.name()),
                eq("Waiting for Jenkins webhook callback..."),
                any(LocalDateTime.class));
        verify(eventProducer).publishJobExecutionChanged(execution, je1, JobExecutionStatus.WAITING_WEBHOOK);
    }

    @Test
    @DisplayName("Webhook 대기 시 뒤에 남은 스텝은 실행되지 않음")
    void 웹훅대기시_후속스텝_실행안됨() throws Exception {
        // Given - je1이 webhook 대기, je2는 실행되면 안 됨
        PipelineJobExecution je1 = createJobExecution(1, PipelineJobType.BUILD);
        PipelineJobExecution je2 = createJobExecution(2, PipelineJobType.DEPLOY);
        execution.setJobExecutions(List.of(je1, je2));
        when(jobExecutionMapper.findByExecutionId(execution.getId())).thenReturn(List.of(je1, je2));

        doAnswer(invocation -> {
            PipelineJobExecution s = invocation.getArgument(1);
            s.setWaitingForWebhook(true);
            return null;
        }).when(gitCloneAndBuild).execute(any(PipelineExecution.class), any(PipelineJobExecution.class));

        // When
        pipelineEngine.execute(execution);

        // Then - deploy는 호출되지 않음
        verify(deploy, never()).execute(any(PipelineExecution.class), any(PipelineJobExecution.class));
    }

    // ---------------------------------------------------------------------------
    // resumeAfterWebhook
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("resumeAfterWebhook - 존재하지 않는 execution은 무시")
    void resumeAfterWebhook_미존재_execution_무시() {
        // Given
        UUID unknownId = UUID.randomUUID();
        when(executionMapper.findById(unknownId)).thenReturn(null);

        // When
        pipelineEngine.resumeAfterWebhook(unknownId, 1, "SUCCESS", "build log");

        // Then - 아무 작업도 수행하지 않음
        verify(jobExecutionMapper, never()).findByExecutionIdAndJobOrder(any(), anyInt());
    }

    @Test
    @DisplayName("resumeAfterWebhook - 존재하지 않는 jobExecution은 무시")
    void resumeAfterWebhook_미존재_jobExecution_무시() {
        // Given
        UUID execId = execution.getId();
        when(executionMapper.findById(execId)).thenReturn(execution);
        when(jobExecutionMapper.findByExecutionIdAndJobOrder(execId, 1)).thenReturn(null);

        // When
        pipelineEngine.resumeAfterWebhook(execId, 1, "SUCCESS", "build log");

        // Then
        verify(jobExecutionMapper, never()).updateStatusIfCurrent(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("resumeAfterWebhook - CAS 실패 시 파이프라인 재개 없음")
    void resumeAfterWebhook_CAS_실패시_재개없음() {
        // Given
        UUID execId = execution.getId();
        PipelineJobExecution waitingJe = createJobExecution(1, PipelineJobType.BUILD);
        waitingJe.setStatus(JobExecutionStatus.WAITING_WEBHOOK);

        when(executionMapper.findById(execId)).thenReturn(execution);
        when(jobExecutionMapper.findByExecutionIdAndJobOrder(execId, 1)).thenReturn(waitingJe);
        when(jobExecutionMapper.updateStatusIfCurrent(
                eq(1L), eq(JobExecutionStatus.WAITING_WEBHOOK.name()), eq(JobExecutionStatus.SUCCESS.name()),
                any(), any(LocalDateTime.class))).thenReturn(0); // CAS 실패

        // When
        pipelineEngine.resumeAfterWebhook(execId, 1, "SUCCESS", "build log");

        // Then - jobExecutions 재로드 없음, 파이프라인 재개 없음
        verify(jobExecutionMapper, never()).findByExecutionId(any());
    }

    @Test
    @DisplayName("resumeAfterWebhook - Jenkins 실패 시 SAGA 보상 및 FAILED 상태")
    void resumeAfterWebhook_Jenkins_실패시_SAGA_보상() {
        // Given
        UUID execId = execution.getId();
        PipelineJobExecution waitingJe = createJobExecution(1, PipelineJobType.BUILD);
        waitingJe.setStatus(JobExecutionStatus.WAITING_WEBHOOK);
        execution.setJobExecutions(List.of(waitingJe));

        when(executionMapper.findById(execId)).thenReturn(execution);
        when(jobExecutionMapper.findByExecutionIdAndJobOrder(execId, 1)).thenReturn(waitingJe);
        when(jobExecutionMapper.updateStatusIfCurrent(
                eq(1L), eq(JobExecutionStatus.WAITING_WEBHOOK.name()), eq(JobExecutionStatus.FAILED.name()),
                any(), any(LocalDateTime.class))).thenReturn(1);
        when(jobExecutionMapper.findByExecutionId(execId)).thenReturn(List.of(waitingJe));

        // When
        pipelineEngine.resumeAfterWebhook(execId, 1, "FAILED", "Build #42 failed");

        // Then
        verify(sagaCompensator).compensate(eq(execution), eq(1), any());
        verify(executionMapper).updateStatus(
                eq(execId), eq(PipelineStatus.FAILED.name()),
                any(LocalDateTime.class), eq("Build #42 failed"));
        verify(eventProducer).publishExecutionCompleted(
                eq(execution),
                eq(com.study.playground.avro.common.PipelineStatus.FAILED),
                anyLong(),
                eq("Build #42 failed"));
    }

    @Test
    @DisplayName("resumeAfterWebhook - Jenkins 성공 시 다음 스텝 재개")
    void resumeAfterWebhook_Jenkins_성공시_다음스텝_재개() throws Exception {
        // Given
        UUID execId = execution.getId();
        PipelineJobExecution waitingJe = createJobExecution(1, PipelineJobType.BUILD);
        waitingJe.setStatus(JobExecutionStatus.WAITING_WEBHOOK);
        PipelineJobExecution nextJe = createJobExecution(2, PipelineJobType.DEPLOY);
        execution.setJobExecutions(List.of(waitingJe, nextJe));

        when(executionMapper.findById(execId)).thenReturn(execution);
        when(jobExecutionMapper.findByExecutionIdAndJobOrder(execId, 1)).thenReturn(waitingJe);
        when(jobExecutionMapper.updateStatusIfCurrent(
                eq(1L), eq(JobExecutionStatus.WAITING_WEBHOOK.name()), eq(JobExecutionStatus.SUCCESS.name()),
                any(), any(LocalDateTime.class))).thenReturn(1);
        when(jobExecutionMapper.findByExecutionId(execId)).thenReturn(List.of(waitingJe, nextJe));

        // When
        pipelineEngine.resumeAfterWebhook(execId, 1, "SUCCESS", "Build log output");

        // Then - deploy (je2) 실행됨, SAGA 없음
        verify(deploy).execute(any(PipelineExecution.class), eq(nextJe));
        verify(sagaCompensator, never()).compensate(any(), anyInt(), any());
        verify(executionMapper).updateStatus(
                eq(execId), eq(PipelineStatus.SUCCESS.name()),
                any(LocalDateTime.class), isNull());
    }
}
