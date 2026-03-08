package com.study.playground.pipeline.engine;

import com.study.playground.pipeline.domain.*;
import com.study.playground.pipeline.event.PipelineEventProducer;
import com.study.playground.pipeline.mapper.PipelineStepMapper;
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
    private PipelineStepMapper stepMapper;

    @Mock
    private PipelineEventProducer eventProducer;

    @InjectMocks
    private SagaCompensator sagaCompensator;

    @Mock
    private PipelineStepExecutor mockExecutor;

    private Map<PipelineStepType, PipelineStepExecutor> stepExecutors;
    private PipelineExecution execution;

    @BeforeEach
    void setUp() {
        stepExecutors = Map.of(
                PipelineStepType.GIT_CLONE, mockExecutor,
                PipelineStepType.BUILD, mockExecutor,
                PipelineStepType.DEPLOY, mockExecutor
        );

        execution = new PipelineExecution();
        execution.setId(UUID.randomUUID());
        execution.setTicketId(1L);
    }

    private PipelineStep createStep(int order, PipelineStepType type, StepStatus status) {
        PipelineStep step = new PipelineStep();
        step.setId((long) order);
        step.setStepOrder(order);
        step.setStepType(type);
        step.setStepName("Step " + order);
        step.setStatus(status);
        return step;
    }

    @Test
    @DisplayName("3번째 스텝 실패 시 1,2번 스텝을 역순으로 보상한다")
    void 스텝실패시_역순보상() throws Exception {
        // Given
        PipelineStep step1 = createStep(1, PipelineStepType.GIT_CLONE, StepStatus.SUCCESS);
        PipelineStep step2 = createStep(2, PipelineStepType.BUILD, StepStatus.SUCCESS);
        PipelineStep step3 = createStep(3, PipelineStepType.DEPLOY, StepStatus.FAILED);
        execution.setSteps(List.of(step1, step2, step3));

        // When
        sagaCompensator.compensate(execution, 3, stepExecutors);

        // Then - step2 compensated first (reverse order), then step1
        var inOrder = inOrder(mockExecutor, stepMapper, eventProducer);
        inOrder.verify(mockExecutor).compensate(execution, step2);
        inOrder.verify(stepMapper).updateStatus(eq(2L), eq(StepStatus.SKIPPED.name()), anyString(), any());
        inOrder.verify(eventProducer).publishStepChanged(execution, step2, StepStatus.SKIPPED);
        inOrder.verify(mockExecutor).compensate(execution, step1);
        inOrder.verify(stepMapper).updateStatus(eq(1L), eq(StepStatus.SKIPPED.name()), anyString(), any());
        inOrder.verify(eventProducer).publishStepChanged(execution, step1, StepStatus.SKIPPED);
    }

    @Test
    @DisplayName("첫 번째 스텝 실패 시 보상할 스텝이 없다")
    void 첫스텝실패시_보상없음() throws Exception {
        // Given
        PipelineStep step1 = createStep(1, PipelineStepType.GIT_CLONE, StepStatus.FAILED);
        execution.setSteps(List.of(step1));

        // When
        sagaCompensator.compensate(execution, 1, stepExecutors);

        // Then
        verify(mockExecutor, never()).compensate(any(), any());
        verify(stepMapper, never()).updateStatus(anyLong(), anyString(), anyString(), any());
        verify(eventProducer, never()).publishStepChanged(any(), any(), any());
    }

    @Test
    @DisplayName("보상 중 예외 발생 시 해당 스텝은 FAILED 처리하고 나머지 계속 보상")
    void 보상실패시_계속진행() throws Exception {
        // Given
        PipelineStep step1 = createStep(1, PipelineStepType.GIT_CLONE, StepStatus.SUCCESS);
        PipelineStep step2 = createStep(2, PipelineStepType.BUILD, StepStatus.SUCCESS);
        PipelineStep step3 = createStep(3, PipelineStepType.DEPLOY, StepStatus.FAILED);
        execution.setSteps(List.of(step1, step2, step3));

        // step2 보상 시 예외 발생
        doThrow(new RuntimeException("Compensation failed")).when(mockExecutor).compensate(execution, step2);

        // When
        sagaCompensator.compensate(execution, 3, stepExecutors);

        // Then - step2 compensation failed → FAILED status (no event), step1 still compensated → SKIPPED
        verify(mockExecutor).compensate(execution, step2);
        verify(stepMapper).updateStatus(eq(2L), eq(StepStatus.FAILED.name()), contains("COMPENSATION_FAILED"), any());
        verify(eventProducer, never()).publishStepChanged(eq(execution), eq(step2), any());

        verify(mockExecutor).compensate(execution, step1);
        verify(stepMapper).updateStatus(eq(1L), eq(StepStatus.SKIPPED.name()), anyString(), any());
        verify(eventProducer).publishStepChanged(execution, step1, StepStatus.SKIPPED);
    }

    @Test
    @DisplayName("PENDING 상태 스텝은 보상하지 않는다")
    void PENDING_스텝은_보상하지_않음() throws Exception {
        // Given
        PipelineStep step1 = createStep(1, PipelineStepType.GIT_CLONE, StepStatus.SUCCESS);
        PipelineStep step2 = createStep(2, PipelineStepType.BUILD, StepStatus.PENDING);
        PipelineStep step3 = createStep(3, PipelineStepType.DEPLOY, StepStatus.FAILED);
        execution.setSteps(List.of(step1, step2, step3));

        // When
        sagaCompensator.compensate(execution, 3, stepExecutors);

        // Then - only step1 compensated (step2 was PENDING, skipped by SUCCESS check)
        verify(mockExecutor, never()).compensate(execution, step2);
        verify(stepMapper, never()).updateStatus(eq(2L), anyString(), anyString(), any());

        verify(mockExecutor).compensate(execution, step1);
        verify(stepMapper).updateStatus(eq(1L), eq(StepStatus.SKIPPED.name()), anyString(), any());
        verify(eventProducer).publishStepChanged(execution, step1, StepStatus.SKIPPED);
    }
}
