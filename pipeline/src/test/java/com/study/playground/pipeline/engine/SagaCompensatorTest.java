package com.study.playground.pipeline.engine;

import com.study.playground.pipeline.domain.*;
import com.study.playground.pipeline.event.PipelineEventProducer;
import com.study.playground.pipeline.mapper.PipelineJobExecutionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SagaCompensatorTest {

    @Mock
    private PipelineJobExecutionMapper jobExecutionMapper;

    @Mock
    private PipelineEventProducer eventProducer;

    @InjectMocks
    private SagaCompensator sagaCompensator;

    @Mock
    private PipelineJobExecutor mockExecutor;

    private Map<PipelineJobType, PipelineJobExecutor> jobExecutors;
    private PipelineExecution execution;

    @BeforeEach
    void setUp() {
        jobExecutors = Map.of(
                PipelineJobType.ARTIFACT_DOWNLOAD, mockExecutor,
                PipelineJobType.BUILD, mockExecutor,
                PipelineJobType.DEPLOY, mockExecutor
        );

        execution = new PipelineExecution();
        execution.setId(UUID.randomUUID());
        execution.setTicketId(1L);
    }

    private PipelineJobExecution createJobExecution(int order, PipelineJobType type, JobExecutionStatus status) {
        PipelineJobExecution je = new PipelineJobExecution();
        je.setId((long) order);
        je.setJobOrder(order);
        je.setJobType(type);
        je.setJobName("Job " + order);
        je.setStatus(status);
        return je;
    }

    @Test
    @DisplayName("3번째 스텝 실패 시 1,2번 스텝을 역순으로 보상한다")
    void 스텝실패시_역순보상() throws Exception {
        // Given
        PipelineJobExecution je1 = createJobExecution(1, PipelineJobType.ARTIFACT_DOWNLOAD, JobExecutionStatus.SUCCESS);
        PipelineJobExecution je2 = createJobExecution(2, PipelineJobType.BUILD, JobExecutionStatus.SUCCESS);
        PipelineJobExecution je3 = createJobExecution(3, PipelineJobType.DEPLOY, JobExecutionStatus.FAILED);
        execution.setJobExecutions(List.of(je1, je2, je3));

        // When
        sagaCompensator.compensate(execution, 3, jobExecutors);

        // Then - je2 compensated first (reverse order), then je1
        var inOrder = inOrder(mockExecutor, jobExecutionMapper, eventProducer);
        inOrder.verify(mockExecutor).compensate(execution, je2);
        inOrder.verify(jobExecutionMapper).updateStatus(eq(2L), eq(JobExecutionStatus.COMPENSATED.name()), anyString(), any());
        inOrder.verify(eventProducer).publishJobExecutionChanged(execution, je2, JobExecutionStatus.COMPENSATED);
        inOrder.verify(mockExecutor).compensate(execution, je1);
        inOrder.verify(jobExecutionMapper).updateStatus(eq(1L), eq(JobExecutionStatus.COMPENSATED.name()), anyString(), any());
        inOrder.verify(eventProducer).publishJobExecutionChanged(execution, je1, JobExecutionStatus.COMPENSATED);
    }

    @Test
    @DisplayName("첫 번째 스텝 실패 시 보상할 스텝이 없다")
    void 첫스텝실패시_보상없음() throws Exception {
        // Given
        PipelineJobExecution je1 = createJobExecution(1, PipelineJobType.ARTIFACT_DOWNLOAD, JobExecutionStatus.FAILED);
        execution.setJobExecutions(List.of(je1));

        // When
        sagaCompensator.compensate(execution, 1, jobExecutors);

        // Then
        verify(mockExecutor, never()).compensate(any(), any());
        verify(jobExecutionMapper, never()).updateStatus(anyLong(), anyString(), anyString(), any());
        verify(eventProducer, never()).publishJobExecutionChanged(any(), any(), any());
    }

    @Test
    @DisplayName("보상 중 예외 발생 시 해당 스텝은 FAILED 처리하고 나머지 계속 보상")
    void 보상실패시_계속진행() throws Exception {
        // Given
        PipelineJobExecution je1 = createJobExecution(1, PipelineJobType.ARTIFACT_DOWNLOAD, JobExecutionStatus.SUCCESS);
        PipelineJobExecution je2 = createJobExecution(2, PipelineJobType.BUILD, JobExecutionStatus.SUCCESS);
        PipelineJobExecution je3 = createJobExecution(3, PipelineJobType.DEPLOY, JobExecutionStatus.FAILED);
        execution.setJobExecutions(List.of(je1, je2, je3));

        // je2 보상 시 예외 발생
        doThrow(new RuntimeException("Compensation failed")).when(mockExecutor).compensate(execution, je2);

        // When
        sagaCompensator.compensate(execution, 3, jobExecutors);

        // Then - je2 compensation failed → FAILED status (no event), je1 still compensated → SKIPPED
        verify(mockExecutor).compensate(execution, je2);
        verify(jobExecutionMapper).updateStatus(eq(2L), eq(JobExecutionStatus.FAILED.name()), contains("COMPENSATION_FAILED"), any());
        verify(eventProducer, never()).publishJobExecutionChanged(eq(execution), eq(je2), any());

        verify(mockExecutor).compensate(execution, je1);
        verify(jobExecutionMapper).updateStatus(eq(1L), eq(JobExecutionStatus.COMPENSATED.name()), anyString(), any());
        verify(eventProducer).publishJobExecutionChanged(execution, je1, JobExecutionStatus.COMPENSATED);
    }

    @Test
    @DisplayName("PENDING 상태 스텝은 보상하지 않는다")
    void PENDING_스텝은_보상하지_않음() throws Exception {
        // Given
        PipelineJobExecution je1 = createJobExecution(1, PipelineJobType.ARTIFACT_DOWNLOAD, JobExecutionStatus.SUCCESS);
        PipelineJobExecution je2 = createJobExecution(2, PipelineJobType.BUILD, JobExecutionStatus.PENDING);
        PipelineJobExecution je3 = createJobExecution(3, PipelineJobType.DEPLOY, JobExecutionStatus.FAILED);
        execution.setJobExecutions(List.of(je1, je2, je3));

        // When
        sagaCompensator.compensate(execution, 3, jobExecutors);

        // Then - only je1 compensated (je2 was PENDING, skipped by SUCCESS check)
        verify(mockExecutor, never()).compensate(execution, je2);
        verify(jobExecutionMapper, never()).updateStatus(eq(2L), anyString(), anyString(), any());

        verify(mockExecutor).compensate(execution, je1);
        verify(jobExecutionMapper).updateStatus(eq(1L), eq(JobExecutionStatus.COMPENSATED.name()), anyString(), any());
        verify(eventProducer).publishJobExecutionChanged(execution, je1, JobExecutionStatus.COMPENSATED);
    }
}
