package com.study.playground.pipeline.service;

import com.study.playground.avro.common.EventMetadata;
import com.study.playground.avro.pipeline.PipelineExecutionStartedEvent;
import com.study.playground.common.dto.CommonErrorCode;
import com.study.playground.common.exception.BusinessException;
import com.study.playground.common.outbox.EventPublisher;
import com.study.playground.pipeline.domain.*;
import com.study.playground.pipeline.dto.PipelineExecutionResponse;
import com.study.playground.pipeline.mapper.PipelineExecutionMapper;
import com.study.playground.pipeline.mapper.PipelineStepMapper;
import com.study.playground.ticket.domain.Ticket;
import com.study.playground.ticket.domain.TicketSource;
import com.study.playground.ticket.domain.TicketStatus;
import com.study.playground.ticket.mapper.TicketMapper;
import com.study.playground.ticket.mapper.TicketSourceMapper;
import com.study.playground.common.util.AvroSerializer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PipelineService {

    private final TicketMapper ticketMapper;
    private final TicketSourceMapper ticketSourceMapper;
    private final PipelineExecutionMapper executionMapper;
    private final PipelineStepMapper stepMapper;
    private final EventPublisher eventPublisher;

    @Transactional
    public PipelineExecutionResponse startPipeline(Long ticketId) {
        Ticket ticket = ticketMapper.findById(ticketId);
        if (ticket == null) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND, "티켓을 찾을 수 없습니다: " + ticketId);
        }

        List<TicketSource> sources = ticketSourceMapper.findByTicketId(ticketId);
        if (sources.isEmpty()) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT, "소스가 없는 티켓은 배포할 수 없습니다");
        }

        // 티켓 상태 변경
        ticket.setStatus(TicketStatus.DEPLOYING);
        ticketMapper.update(ticket);

        // PipelineExecution 생성
        PipelineExecution execution = new PipelineExecution();
        execution.setId(UUID.randomUUID());
        execution.setTicketId(ticketId);
        execution.setStatus(PipelineStatus.PENDING);
        execution.setStartedAt(LocalDateTime.now());
        executionMapper.insert(execution);

        // 소스 기반 Step 생성
        List<PipelineStep> steps = buildSteps(sources);
        stepMapper.insertBatch(execution.getId(), steps);

        // Outbox INSERT → Kafka 발행은 OutboxPoller가 수행
        EventMetadata metadata = EventMetadata.newBuilder()
                .setEventId(UUID.randomUUID().toString())
                .setCorrelationId(execution.getId().toString())
                .setEventType("PIPELINE_EXECUTION_STARTED")
                .setTimestamp(java.time.Instant.now())
                .setSource("pipeline-service")
                .build();

        List<String> stepNames = steps.stream().map(PipelineStep::getStepName).toList();

        PipelineExecutionStartedEvent event = PipelineExecutionStartedEvent.newBuilder()
                .setMetadata(metadata)
                .setExecutionId(execution.getId().toString())
                .setTicketId(ticketId)
                .setSteps(stepNames)
                .build();

        eventPublisher.publish("PIPELINE", execution.getId().toString(),
                "PIPELINE_EXECUTION_STARTED", AvroSerializer.serialize(event),
                "playground.pipeline.commands");

        return PipelineExecutionResponse.accepted(execution);
    }

    @Transactional(readOnly = true)
    public PipelineExecutionResponse getLatestExecution(Long ticketId) {
        PipelineExecution execution = executionMapper.findLatestByTicketId(ticketId);
        if (execution == null) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND, "파이프라인 실행 이력이 없습니다");
        }
        List<PipelineStep> steps = stepMapper.findByExecutionId(execution.getId());
        return PipelineExecutionResponse.from(execution, steps);
    }

    @Transactional(readOnly = true)
    public List<PipelineExecutionResponse> getHistory(Long ticketId) {
        List<PipelineExecution> executions = executionMapper.findByTicketId(ticketId);
        return executions.stream()
                .map(e -> {
                    List<PipelineStep> steps = stepMapper.findByExecutionId(e.getId());
                    return PipelineExecutionResponse.from(e, steps);
                })
                .toList();
    }

    /**
     * 실패 시뮬레이션: 랜덤 스텝에 [FAIL] 마커를 삽입하여 SAGA 보상 트랜잭션을 검증한다.
     * 첫 번째 스텝은 보상할 대상이 없으므로 제외하고, 2번째 이후 스텝 중 하나를 랜덤 선택한다.
     */
    @Transactional
    public PipelineExecutionResponse startPipelineWithFailure(Long ticketId) {
        Ticket ticket = ticketMapper.findById(ticketId);
        if (ticket == null) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND, "티켓을 찾을 수 없습니다: " + ticketId);
        }

        List<TicketSource> sources = ticketSourceMapper.findByTicketId(ticketId);
        if (sources.isEmpty()) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT, "소스가 없는 티켓은 배포할 수 없습니다");
        }

        ticket.setStatus(TicketStatus.DEPLOYING);
        ticketMapper.update(ticket);

        PipelineExecution execution = new PipelineExecution();
        execution.setId(UUID.randomUUID());
        execution.setTicketId(ticketId);
        execution.setStatus(PipelineStatus.PENDING);
        execution.setStartedAt(LocalDateTime.now());
        executionMapper.insert(execution);

        // 스텝 생성 후 랜덤 [FAIL] 마커 삽입
        List<PipelineStep> steps = buildSteps(sources);
        injectRandomFailure(steps);
        stepMapper.insertBatch(execution.getId(), steps);

        EventMetadata metadata = EventMetadata.newBuilder()
                .setEventId(UUID.randomUUID().toString())
                .setCorrelationId(execution.getId().toString())
                .setEventType("PIPELINE_EXECUTION_STARTED")
                .setTimestamp(java.time.Instant.now())
                .setSource("pipeline-service")
                .build();

        List<String> stepNames = steps.stream().map(PipelineStep::getStepName).toList();

        PipelineExecutionStartedEvent event = PipelineExecutionStartedEvent.newBuilder()
                .setMetadata(metadata)
                .setExecutionId(execution.getId().toString())
                .setTicketId(ticketId)
                .setSteps(stepNames)
                .build();

        eventPublisher.publish("PIPELINE", execution.getId().toString(),
                "PIPELINE_EXECUTION_STARTED", AvroSerializer.serialize(event),
                "playground.pipeline.commands");

        log.info("[FAIL SIMULATION] Pipeline started with failure injection: executionId={}, failStep={}",
                execution.getId(), stepNames.stream().filter(n -> n.contains("[FAIL]")).findFirst().orElse("none"));

        return PipelineExecutionResponse.accepted(execution);
    }

    private void injectRandomFailure(List<PipelineStep> steps) {
        if (steps.size() <= 1) {
            // 스텝이 1개면 그 스텝에 [FAIL] 삽입
            steps.get(0).setStepName(steps.get(0).getStepName() + " [FAIL]");
            return;
        }
        // 첫 번째 스텝 제외 (보상 대상이 없으므로), 2번째 이후 중 랜덤 선택
        int failIndex = 1 + new Random().nextInt(steps.size() - 1);
        PipelineStep failStep = steps.get(failIndex);
        failStep.setStepName(failStep.getStepName() + " [FAIL]");
    }

    private List<PipelineStep> buildSteps(List<TicketSource> sources) {
        List<PipelineStep> steps = new ArrayList<>();
        int order = 1;

        for (TicketSource source : sources) {
            switch (source.getSourceType()) {
                case GIT -> {
                    steps.add(createStep(order++, PipelineStepType.GIT_CLONE, "Clone: " + source.getRepoUrl()));
                    steps.add(createStep(order++, PipelineStepType.BUILD, "Build: " + source.getBranch()));
                }
                case NEXUS -> {
                    steps.add(createStep(order++, PipelineStepType.ARTIFACT_DOWNLOAD,
                            "Download: " + source.getArtifactCoordinate()));
                }
                case HARBOR -> {
                    steps.add(createStep(order++, PipelineStepType.IMAGE_PULL,
                            "Pull: " + source.getImageName()));
                }
            }
        }

        steps.add(createStep(order, PipelineStepType.DEPLOY, "Deploy to Server"));
        return steps;
    }

    private PipelineStep createStep(int order, PipelineStepType type, String name) {
        PipelineStep step = new PipelineStep();
        step.setStepOrder(order);
        step.setStepType(type);
        step.setStepName(name);
        step.setStatus(StepStatus.PENDING);
        return step;
    }

}
