package com.study.playground.pipeline.service;

import com.study.playground.common.exception.BusinessException;
import com.study.playground.common.outbox.EventPublisher;
import com.study.playground.pipeline.domain.PipelineStatus;
import com.study.playground.pipeline.dto.PipelineExecutionResponse;
import com.study.playground.pipeline.mapper.PipelineExecutionMapper;
import com.study.playground.pipeline.mapper.PipelineStepMapper;
import com.study.playground.ticket.domain.Ticket;
import com.study.playground.ticket.domain.TicketSource;
import com.study.playground.ticket.domain.TicketStatus;
import com.study.playground.ticket.domain.SourceType;
import com.study.playground.ticket.mapper.TicketMapper;
import com.study.playground.ticket.mapper.TicketSourceMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PipelineServiceTest {

    @Mock private TicketMapper ticketMapper;
    @Mock private TicketSourceMapper ticketSourceMapper;
    @Mock private PipelineExecutionMapper executionMapper;
    @Mock private PipelineStepMapper stepMapper;
    @Mock private EventPublisher eventPublisher;

    @InjectMocks
    private PipelineService pipelineService;

    private Ticket ticket;

    @BeforeEach
    void setUp() {
        ticket = new Ticket();
        ticket.setId(1L);
        ticket.setName("Test Ticket");
        ticket.setStatus(TicketStatus.DRAFT);
    }

    private TicketSource createSource(SourceType type) {
        TicketSource source = new TicketSource();
        source.setSourceType(type);
        source.setRepoUrl("https://github.com/test/repo");
        source.setBranch("main");
        source.setArtifactCoordinate("com.test:app:1.0");
        source.setImageName("test/image:latest");
        return source;
    }

    @Test
    @DisplayName("파이프라인 시작 시 PENDING 상태의 Execution 생성")
    void 파이프라인_시작_성공() {
        // Given
        when(ticketMapper.findById(1L)).thenReturn(ticket);
        when(ticketSourceMapper.findByTicketId(1L)).thenReturn(List.of(createSource(SourceType.GIT)));

        // When
        PipelineExecutionResponse response = pipelineService.startPipeline(1L);

        // Then
        assertThat(response.getExecutionId()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(PipelineStatus.PENDING.name());
        verify(ticketMapper).update(argThat(t -> t.getStatus() == TicketStatus.DEPLOYING));
        verify(executionMapper).insert(any());
        verify(stepMapper).insertBatch(any(), anyList());
        verify(eventPublisher).publish(eq("PIPELINE"), any(), eq("PIPELINE_EXECUTION_STARTED"), any(), eq("playground.pipeline.commands"));
    }

    @Test
    @DisplayName("존재하지 않는 티켓으로 파이프라인 시작 시 예외")
    void 존재하지않는_티켓_예외() {
        // Given
        when(ticketMapper.findById(999L)).thenReturn(null);

        // When/Then
        assertThatThrownBy(() -> pipelineService.startPipeline(999L))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("소스가 없는 티켓으로 파이프라인 시작 시 예외")
    void 소스없는_티켓_예외() {
        // Given
        when(ticketMapper.findById(1L)).thenReturn(ticket);
        when(ticketSourceMapper.findByTicketId(1L)).thenReturn(List.of());

        // When/Then
        assertThatThrownBy(() -> pipelineService.startPipeline(1L))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("GIT 소스일 때 GIT_CLONE + BUILD + DEPLOY 3개 스텝 생성")
    void GIT소스_스텝3개_생성() {
        // Given
        when(ticketMapper.findById(1L)).thenReturn(ticket);
        when(ticketSourceMapper.findByTicketId(1L)).thenReturn(List.of(createSource(SourceType.GIT)));

        // When
        pipelineService.startPipeline(1L);

        // Then - GIT_CLONE + BUILD + DEPLOY = 3 steps
        verify(stepMapper).insertBatch(any(), argThat(steps -> steps.size() == 3));
    }

    @Test
    @DisplayName("NEXUS 소스일 때 ARTIFACT_DOWNLOAD + DEPLOY 2개 스텝 생성")
    void NEXUS소스_스텝2개_생성() {
        // Given
        when(ticketMapper.findById(1L)).thenReturn(ticket);
        when(ticketSourceMapper.findByTicketId(1L)).thenReturn(List.of(createSource(SourceType.NEXUS)));

        // When
        pipelineService.startPipeline(1L);

        // Then - ARTIFACT_DOWNLOAD + DEPLOY = 2 steps
        verify(stepMapper).insertBatch(any(), argThat(steps -> steps.size() == 2));
    }
}
