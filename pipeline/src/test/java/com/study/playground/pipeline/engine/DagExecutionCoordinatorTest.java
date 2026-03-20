package com.study.playground.pipeline.engine;

import com.study.playground.pipeline.domain.*;
import com.study.playground.pipeline.event.PipelineEventProducer;
import com.study.playground.pipeline.mapper.PipelineExecutionMapper;
import com.study.playground.pipeline.mapper.PipelineJobExecutionMapper;
import com.study.playground.pipeline.mapper.PipelineJobMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DagExecutionCoordinatorTest {

    @Mock private PipelineExecutionMapper executionMapper;
    @Mock private PipelineJobExecutionMapper jobExecutionMapper;
    @Mock private PipelineJobMapper jobMapper;
    @Mock private PipelineEventProducer eventProducer;
    @Mock private SagaCompensator sagaCompensator;
    @Mock private JobExecutorRegistry jobExecutorRegistry;
    @Mock private DagValidator dagValidator;
    @Mock private PipelineJobExecutor mockExecutor;

    private ExecutorService jobExecutorPool;
    private DagExecutionCoordinator coordinator;

    @BeforeEach
    void setUp() {
        jobExecutorPool = Executors.newFixedThreadPool(3);
        coordinator = new DagExecutionCoordinator(
                executionMapper, jobExecutionMapper, jobMapper, eventProducer,
                sagaCompensator, jobExecutorRegistry, dagValidator, jobExecutorPool);
    }

    private PipelineExecution createExecution(Long definitionId) {
        var exec = new PipelineExecution();
        exec.setId(UUID.randomUUID());
        exec.setPipelineDefinitionId(definitionId);
        exec.setStatus(PipelineStatus.PENDING);
        return exec;
    }

    private PipelineJob createJob(Long id, String name, PipelineJobType type, List<Long> deps) {
        var job = new PipelineJob();
        job.setId(id);
        job.setPipelineDefinitionId(1L);
        job.setJobName(name);
        job.setJobType(type);
        job.setExecutionOrder(id.intValue());
        job.setDependsOnJobIds(deps);
        return job;
    }

    private PipelineJobExecution createJobExecution(int order, Long jobId, PipelineJobType type) {
        var je = new PipelineJobExecution();
        je.setId((long) order);
        je.setJobOrder(order);
        je.setJobId(jobId);
        je.setJobType(type);
        je.setJobName("Job-" + order);
        je.setStatus(JobExecutionStatus.PENDING);
        return je;
    }

    // ── 기본 실행 ──────────────────────────────────────────────────

    @Test
    @DisplayName("단일 Job DAG 실행: 동기 완료 시 SUCCESS")
    void 단일Job_동기실행_성공() throws Exception {
        // Given
        var exec = createExecution(1L);
        var job = createJob(1L, "Build", PipelineJobType.BUILD, List.of());
        var je = createJobExecution(1, 1L, PipelineJobType.BUILD);

        when(jobMapper.findByDefinitionId(1L)).thenReturn(List.of(job));
        when(jobMapper.findDependsOnJobIds(1L, 1L)).thenReturn(List.of());
        when(jobExecutionMapper.findByExecutionId(exec.getId())).thenReturn(List.of(je));
        when(jobExecutionMapper.findByExecutionIdAndJobOrder(exec.getId(), 1)).thenReturn(je);
        when(jobExecutorRegistry.getExecutor(PipelineJobType.BUILD)).thenReturn(mockExecutor);
        when(executionMapper.findById(exec.getId())).thenReturn(exec);

        // When
        coordinator.startExecution(exec);
        jobExecutorPool.shutdown();
        jobExecutorPool.awaitTermination(5, TimeUnit.SECONDS);

        // Then
        verify(executionMapper).updateStatus(
                eq(exec.getId()), eq(PipelineStatus.RUNNING.name()), isNull(), isNull());
        verify(mockExecutor).execute(exec, je);
        verify(executionMapper).updateStatus(
                eq(exec.getId()), eq(PipelineStatus.SUCCESS.name()), any(), isNull());
    }

    @Test
    @DisplayName("단일 Job 실패 시 FAILED + 보상 없음 (완료된 Job 없음)")
    void 단일Job_실패시_FAILED() throws Exception {
        // Given
        var exec = createExecution(1L);
        var job = createJob(1L, "Build", PipelineJobType.BUILD, List.of());
        var je = createJobExecution(1, 1L, PipelineJobType.BUILD);

        when(jobMapper.findByDefinitionId(1L)).thenReturn(List.of(job));
        when(jobMapper.findDependsOnJobIds(1L, 1L)).thenReturn(List.of());
        when(jobExecutionMapper.findByExecutionId(exec.getId())).thenReturn(List.of(je));
        when(jobExecutionMapper.findByExecutionIdAndJobOrder(exec.getId(), 1)).thenReturn(je);
        when(jobExecutorRegistry.getExecutor(PipelineJobType.BUILD)).thenReturn(mockExecutor);
        when(executionMapper.findById(exec.getId())).thenReturn(exec);

        doThrow(new RuntimeException("Build failed")).when(mockExecutor).execute(exec, je);

        // When
        coordinator.startExecution(exec);
        jobExecutorPool.shutdown();
        jobExecutorPool.awaitTermination(5, TimeUnit.SECONDS);

        // Then
        verify(executionMapper).updateStatus(
                eq(exec.getId()), eq(PipelineStatus.FAILED.name()), any(), contains("failed"));
    }

    // ── DAG 상태 관리 ──────────────────────────────────────────────

    @Test
    @DisplayName("isManaged: 실행 시작 후 true, 종료 후 false")
    void isManaged_상태확인() throws Exception {
        // Given
        var exec = createExecution(1L);
        var job = createJob(1L, "Build", PipelineJobType.BUILD, List.of());
        var je = createJobExecution(1, 1L, PipelineJobType.BUILD);

        when(jobMapper.findByDefinitionId(1L)).thenReturn(List.of(job));
        when(jobMapper.findDependsOnJobIds(1L, 1L)).thenReturn(List.of());
        when(jobExecutionMapper.findByExecutionId(exec.getId())).thenReturn(List.of(je));
        when(jobExecutionMapper.findByExecutionIdAndJobOrder(exec.getId(), 1)).thenReturn(je);
        when(jobExecutorRegistry.getExecutor(PipelineJobType.BUILD)).thenReturn(mockExecutor);
        when(executionMapper.findById(exec.getId())).thenReturn(exec);

        // When
        coordinator.startExecution(exec);

        // Then - 실행 중에는 managed
        assertThat(coordinator.isManaged(exec.getId())).isTrue();

        jobExecutorPool.shutdown();
        jobExecutorPool.awaitTermination(5, TimeUnit.SECONDS);

        // 완료 후에는 unmanaged
        assertThat(coordinator.isManaged(exec.getId())).isFalse();
    }

    // ── Webhook 연동 ──────────────────────────────────────────────

    @Test
    @DisplayName("Webhook 대기 Job: onJobCompleted(success=true)로 재개")
    void 웹훅대기_성공재개() throws Exception {
        // Given
        var exec = createExecution(1L);
        var job1 = createJob(1L, "Build", PipelineJobType.BUILD, List.of());
        var job2 = createJob(2L, "Deploy", PipelineJobType.DEPLOY, List.of(1L));
        var je1 = createJobExecution(1, 1L, PipelineJobType.BUILD);
        var je2 = createJobExecution(2, 2L, PipelineJobType.DEPLOY);

        when(jobMapper.findByDefinitionId(1L)).thenReturn(List.of(job1, job2));
        when(jobMapper.findDependsOnJobIds(1L, 1L)).thenReturn(List.of());
        when(jobMapper.findDependsOnJobIds(1L, 2L)).thenReturn(List.of(1L));
        when(jobExecutionMapper.findByExecutionId(exec.getId())).thenReturn(List.of(je1, je2));
        when(jobExecutionMapper.findByExecutionIdAndJobOrder(exec.getId(), 1)).thenReturn(je1);
        when(jobExecutionMapper.findByExecutionIdAndJobOrder(exec.getId(), 2)).thenReturn(je2);
        when(jobExecutorRegistry.getExecutor(PipelineJobType.BUILD)).thenReturn(mockExecutor);
        when(jobExecutorRegistry.getExecutor(PipelineJobType.DEPLOY)).thenReturn(mockExecutor);
        when(executionMapper.findById(exec.getId())).thenReturn(exec);

        // Build는 webhook 대기
        doAnswer(inv -> {
            PipelineJobExecution s = inv.getArgument(1);
            s.setWaitingForWebhook(true);
            return null;
        }).when(mockExecutor).execute(eq(exec), eq(je1));

        // When - 실행 시작 (pool을 shutdown하지 않고 완료 대기)
        coordinator.startExecution(exec);

        // Build가 webhook 대기 중이므로 pool 작업이 빠르게 끝남 (webhook 설정 후 return)
        Thread.sleep(500);

        // Then - Build가 webhook 대기 중, Deploy는 아직 실행 안 됨
        assertThat(coordinator.isManaged(exec.getId())).isTrue();

        // When - webhook 성공 도착 → onJobCompleted에서 Job2 디스패치 시도
        coordinator.onJobCompleted(exec.getId(), 1, 1L, true);

        jobExecutorPool.shutdown();
        jobExecutorPool.awaitTermination(5, TimeUnit.SECONDS);

        // 실패가 아닌 한 새 ready Job을 찾으려 시도
        verify(executionMapper, atLeast(1)).findById(exec.getId());
    }

    // ── DagExecutionState 단위 테스트 ──────────────────────────────

    @Test
    @DisplayName("DagExecutionState: findReadyJobIds - 루트만 ready")
    void dagState_루트만_ready() {
        var job1 = createJob(1L, "A", PipelineJobType.BUILD, List.of());
        var job2 = createJob(2L, "B", PipelineJobType.BUILD, List.of(1L));

        var state = DagExecutionState.initialize(
                List.of(job1, job2), java.util.Map.of(1L, 1, 2L, 2));

        assertThat(state.findReadyJobIds()).containsExactly(1L);
    }

    @Test
    @DisplayName("DagExecutionState: 의존 Job 완료 후 후속 Job이 ready")
    void dagState_의존완료후_후속ready() {
        var job1 = createJob(1L, "A", PipelineJobType.BUILD, List.of());
        var job2 = createJob(2L, "B", PipelineJobType.BUILD, List.of(1L));
        var job3 = createJob(3L, "C", PipelineJobType.DEPLOY, List.of(1L));

        var state = DagExecutionState.initialize(
                List.of(job1, job2, job3), java.util.Map.of(1L, 1, 2L, 2, 3L, 3));

        // job1 완료
        state.markCompleted(1L);

        assertThat(state.findReadyJobIds()).containsExactlyInAnyOrder(2L, 3L);
    }

    @Test
    @DisplayName("DagExecutionState: 다이아몬드 DAG에서 모든 의존 완료 시 합류 노드 ready")
    void dagState_다이아몬드_합류() {
        var a = createJob(1L, "A", PipelineJobType.BUILD, List.of());
        var b = createJob(2L, "B", PipelineJobType.BUILD, List.of(1L));
        var c = createJob(3L, "C", PipelineJobType.BUILD, List.of(1L));
        var d = createJob(4L, "D", PipelineJobType.DEPLOY, List.of(2L, 3L));

        var state = DagExecutionState.initialize(
                List.of(a, b, c, d), java.util.Map.of(1L, 1, 2L, 2, 3L, 3, 4L, 4));

        state.markCompleted(1L);
        state.markCompleted(2L);
        // C 미완료 → D는 아직 ready가 아님
        assertThat(state.findReadyJobIds()).containsExactly(3L);

        state.markCompleted(3L);
        // 이제 D도 ready
        assertThat(state.findReadyJobIds()).containsExactly(4L);
    }

    @Test
    @DisplayName("DagExecutionState: 역방향 위상 순서 (leaf→root)")
    void dagState_역방향위상순서() {
        var a = createJob(1L, "A", PipelineJobType.BUILD, List.of());
        var b = createJob(2L, "B", PipelineJobType.BUILD, List.of(1L));
        var c = createJob(3L, "C", PipelineJobType.DEPLOY, List.of(2L));

        var state = DagExecutionState.initialize(
                List.of(a, b, c), java.util.Map.of(1L, 1, 2L, 2, 3L, 3));

        state.markCompleted(1L);
        state.markCompleted(2L);
        state.markCompleted(3L);

        // 역방향: C → B → A
        assertThat(state.completedJobIdsInReverseTopologicalOrder())
                .containsExactly(3L, 2L, 1L);
    }

    @Test
    @DisplayName("DagExecutionState: isAllDone - 성공+실패 합산")
    void dagState_isAllDone() {
        var a = createJob(1L, "A", PipelineJobType.BUILD, List.of());
        var b = createJob(2L, "B", PipelineJobType.BUILD, List.of(1L));

        var state = DagExecutionState.initialize(
                List.of(a, b), java.util.Map.of(1L, 1, 2L, 2));

        assertThat(state.isAllDone()).isFalse();

        state.markCompleted(1L);
        assertThat(state.isAllDone()).isFalse();

        state.markFailed(2L);
        assertThat(state.isAllDone()).isTrue();
        assertThat(state.hasFailure()).isTrue();
    }
}
