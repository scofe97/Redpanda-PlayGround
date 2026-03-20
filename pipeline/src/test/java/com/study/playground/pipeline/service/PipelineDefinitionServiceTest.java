package com.study.playground.pipeline.service;

import com.study.playground.common.dto.CommonErrorCode;
import com.study.playground.common.exception.BusinessException;
import com.study.playground.kafka.outbox.EventPublisher;
import com.study.playground.kafka.serialization.AvroSerializer;
import com.study.playground.pipeline.domain.*;
import com.study.playground.pipeline.dto.PipelineDefinitionRequest;
import com.study.playground.pipeline.dto.PipelineDefinitionResponse;
import com.study.playground.pipeline.dto.PipelineJobMappingRequest;
import com.study.playground.pipeline.engine.DagValidator;
import com.study.playground.pipeline.mapper.PipelineDefinitionMapper;
import com.study.playground.pipeline.mapper.PipelineExecutionMapper;
import com.study.playground.pipeline.mapper.PipelineJobMapper;
import com.study.playground.pipeline.mapper.PipelineJobMappingMapper;
import com.study.playground.pipeline.mapper.PipelineJobExecutionMapper;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PipelineDefinitionServiceTest {

    @Mock private PipelineDefinitionMapper definitionMapper;
    @Mock private PipelineJobMapper jobMapper;
    @Mock private PipelineJobMappingMapper mappingMapper;
    @Mock private PipelineExecutionMapper executionMapper;
    @Mock private PipelineJobExecutionMapper jobExecutionMapper;
    @Mock private EventPublisher eventPublisher;
    @Mock private AvroSerializer avroSerializer;
    @Mock private DagValidator dagValidator;

    private PipelineDefinitionService service;

    @BeforeEach
    void setUp() {
        service = new PipelineDefinitionService(
                definitionMapper, jobMapper, mappingMapper, executionMapper,
                jobExecutionMapper, eventPublisher, avroSerializer, dagValidator);
    }

    private PipelineDefinition createDefinition(Long id, String name) {
        var def = new PipelineDefinition();
        def.setId(id);
        def.setName(name);
        def.setStatus("ACTIVE");
        def.setCreatedAt(LocalDateTime.now());
        def.setJobs(List.of());
        return def;
    }

    // ── CRUD ──────────────────────────────────────────────────────

    @Test
    @DisplayName("파이프라인 정의 생성: ACTIVE 상태로 저장")
    void 생성_ACTIVE_상태() {
        // Given
        var request = new PipelineDefinitionRequest();
        request.setName("Test Pipeline");
        request.setDescription("Test description");

        var saved = createDefinition(1L, "Test Pipeline");
        when(definitionMapper.findById(any())).thenReturn(saved);

        // When
        var response = service.create(request);

        // Then
        ArgumentCaptor<PipelineDefinition> captor = ArgumentCaptor.forClass(PipelineDefinition.class);
        verify(definitionMapper).insert(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("ACTIVE");
        assertThat(response.name()).isEqualTo("Test Pipeline");
    }

    @Test
    @DisplayName("존재하지 않는 ID 조회 시 BusinessException")
    void 미존재_ID_조회_예외() {
        when(definitionMapper.findById(999L)).thenReturn(null);

        assertThatThrownBy(() -> service.findById(999L))
                .isInstanceOf(BusinessException.class);
    }

    // ── 매핑 업데이트 ──────────────────────────────────────────────

    @Test
    @DisplayName("매핑 업데이트: 기존 매핑 삭제 후 새로 삽입")
    void 매핑_업데이트_재생성() {
        // Given
        var def = createDefinition(1L, "Pipeline");

        // 두 번째 findById 호출 시 Job이 포함된 정의 반환 (DAG 검증 트리거)
        var defWithJobs = createDefinition(1L, "Pipeline");
        var job = new PipelineJob();
        job.setId(10L);
        job.setJobName("Build");
        job.setJobType(PipelineJobType.BUILD);
        job.setDependsOnJobIds(List.of());
        defWithJobs.setJobs(List.of(job));

        when(definitionMapper.findById(1L)).thenReturn(def, defWithJobs);
        when(jobMapper.findDependsOnJobIds(1L, 10L)).thenReturn(List.of());

        var mappingReq1 = new PipelineJobMappingRequest();
        mappingReq1.setJobId(10L);
        mappingReq1.setExecutionOrder(1);

        var mappingReq2 = new PipelineJobMappingRequest();
        mappingReq2.setJobId(20L);
        mappingReq2.setExecutionOrder(2);
        mappingReq2.setDependsOnJobIds(List.of(10L)); // Build에 의존

        // When
        service.updateMappings(1L, List.of(mappingReq1, mappingReq2));

        // Then
        verify(jobMapper).deleteDependenciesByDefinitionId(1L);
        verify(mappingMapper).deleteByDefinitionId(1L);
        verify(mappingMapper).insertBatch(eq(1L), anyList());
        verify(jobMapper).insertDependency(1L, 20L, 10L);
        verify(dagValidator).validate(anyList());
    }

    // ── 삭제 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("실행 중 파이프라인 삭제 시 BusinessException")
    void 실행중_삭제_거부() {
        // Given
        var def = createDefinition(1L, "Pipeline");
        when(definitionMapper.findById(1L)).thenReturn(def);

        var runningExec = new PipelineExecution();
        runningExec.setStatus(PipelineStatus.RUNNING);
        when(executionMapper.findByPipelineDefinitionId(1L)).thenReturn(List.of(runningExec));

        // When/Then
        assertThatThrownBy(() -> service.delete(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("실행 중");
    }

    @Test
    @DisplayName("실행 없는 파이프라인 정상 삭제")
    void 정상_삭제() {
        // Given
        var def = createDefinition(1L, "Pipeline");
        when(definitionMapper.findById(1L)).thenReturn(def);
        when(executionMapper.findByPipelineDefinitionId(1L)).thenReturn(List.of());

        // When
        service.delete(1L);

        // Then
        verify(jobMapper).deleteDependenciesByDefinitionId(1L);
        verify(mappingMapper).deleteByDefinitionId(1L);
        verify(definitionMapper).delete(1L);
    }

    // ── 실행 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("Job 없는 파이프라인 실행 시 BusinessException")
    void Job없는_실행_거부() {
        // Given
        var def = createDefinition(1L, "Empty Pipeline");
        when(definitionMapper.findById(1L)).thenReturn(def);

        assertThatThrownBy(() -> service.execute(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Job이 없습니다");
    }

    @Test
    @DisplayName("정상 실행: PipelineExecution 생성 + Outbox 이벤트 발행")
    void 정상실행_Execution생성_이벤트발행() {
        // Given
        var job = new PipelineJob();
        job.setId(10L);
        job.setJobName("Build");
        job.setJobType(PipelineJobType.BUILD);
        job.setExecutionOrder(1);
        job.setDependsOnJobIds(List.of());

        var def = createDefinition(1L, "Pipeline");
        def.setJobs(List.of(job));
        when(definitionMapper.findById(1L)).thenReturn(def);
        when(jobMapper.findDependsOnJobIds(1L, 10L)).thenReturn(List.of());
        when(avroSerializer.serialize(any())).thenReturn(new byte[]{1, 2, 3});

        // When
        var response = service.execute(1L);

        // Then
        assertThat(response).isNotNull();
        verify(executionMapper).insert(any(PipelineExecution.class));
        verify(jobExecutionMapper).insertBatch(any(), anyList());
        verify(eventPublisher).publish(
                eq("PIPELINE"), any(), eq("PIPELINE_EXECUTION_STARTED"),
                any(byte[].class), any(), any());
    }
}
