package com.study.playground.pipeline.service;

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
import com.study.playground.kafka.serialization.AvroSerializer;
import com.study.playground.kafka.topic.Topics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class PipelineService {

    private final TicketMapper ticketMapper;
    private final TicketSourceMapper ticketSourceMapper;
    private final PipelineExecutionMapper executionMapper;
    private final PipelineStepMapper stepMapper;
    private final EventPublisher eventPublisher;
    private final AvroSerializer avroSerializer;

    @Transactional
    public PipelineExecutionResponse startPipeline(Long ticketId) {
        return doStartPipeline(ticketId);
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

    private PipelineExecutionResponse doStartPipeline(Long ticketId) {

        // 티켓 조회
        Ticket ticket = ticketMapper.findById(ticketId);
        if (ticket == null) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND, "티켓을 찾을 수 없습니다: " + ticketId);
        }

        // 티켓 소스 조회
        List<TicketSource> sources = ticketSourceMapper.findByTicketId(ticketId);
        if (sources.isEmpty()) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT, "소스가 없는 티켓은 배포할 수 없습니다");
        }

        // 배포중 상태 변경
        ticket.setStatus(TicketStatus.DEPLOYING);
        ticketMapper.update(ticket);

        // 파이프라인 실행
        PipelineExecution execution = new PipelineExecution();
        execution.setId(UUID.randomUUID());
        execution.setTicketId(ticketId);
        execution.setStatus(PipelineStatus.PENDING);
        execution.setStartedAt(LocalDateTime.now());
        executionMapper.insert(execution);

        // 단계
        List<PipelineStep> steps = buildSteps(sources);
        stepMapper.insertBatch(execution.getId(), steps);

        // Outbox INSERT → Kafka 발행은 OutboxPoller가 수행
        List<String> stepNameList = steps.stream()
                .map(PipelineStep::getStepName)
                .toList();

        PipelineExecutionStartedEvent event = PipelineExecutionStartedEvent.newBuilder()
                .setExecutionId(execution.getId().toString())
                .setTicketId(ticketId)
                .setSteps(stepNameList)
                .build();

        // 아웃박스 DB 생성
        eventPublisher.publish("PIPELINE"
                , execution.getId().toString()
                , "PIPELINE_EXECUTION_STARTED"
                , avroSerializer.serialize(event),
                Topics.PIPELINE_CMD_EXECUTION
                , execution.getId().toString()
        );

        return PipelineExecutionResponse.accepted(execution);
    }

    private List<PipelineStep> buildSteps(List<TicketSource> sources) {
        List<PipelineStep> steps = new ArrayList<>();
        int order = 1;

        for (TicketSource source : sources) {
            switch (source.getSourceType()) {
                case GIT -> {
                    String repoUrl = source.getRepoUrl() != null ? source.getRepoUrl() : "";
                    String branch = source.getBranch() != null ? source.getBranch() : "main";
                    steps.add(createStep(order++, PipelineStepType.GIT_CLONE,
                            "Clone: " + repoUrl + "#" + branch));
                    steps.add(createStep(order++, PipelineStepType.BUILD,
                            "Build: " + repoUrl + "#" + branch));
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

        String deployTarget = sources.stream()
                .map(s -> switch (s.getSourceType()) {
                    case GIT -> {
                        String url = s.getRepoUrl() != null ? s.getRepoUrl() : "";
                        String name = url.contains("/") ? url.substring(url.lastIndexOf('/') + 1) : url;
                        yield name + " (" + (s.getBranch() != null ? s.getBranch() : "main") + ")";
                    }
                    case NEXUS -> s.getArtifactCoordinate() != null ? s.getArtifactCoordinate() : "";
                    case HARBOR -> s.getImageName() != null ? s.getImageName() : "";
                })
                .collect(Collectors.joining(", "));
        steps.add(createStep(order, PipelineStepType.DEPLOY, "Deploy: " + deployTarget));
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
