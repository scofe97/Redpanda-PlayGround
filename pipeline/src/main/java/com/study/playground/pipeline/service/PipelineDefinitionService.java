package com.study.playground.pipeline.service;

import com.study.playground.avro.pipeline.PipelineExecutionStartedEvent;
import com.study.playground.common.dto.CommonErrorCode;
import com.study.playground.common.exception.BusinessException;
import com.study.playground.kafka.outbox.EventPublisher;
import com.study.playground.kafka.tracing.TraceContextUtil;
import com.study.playground.kafka.serialization.AvroSerializer;
import com.study.playground.kafka.topic.Topics;
import com.study.playground.pipeline.domain.*;
import com.study.playground.pipeline.dto.*;
import com.study.playground.pipeline.engine.DagValidator;
import com.study.playground.pipeline.mapper.PipelineDefinitionMapper;
import com.study.playground.pipeline.mapper.PipelineExecutionMapper;
import com.study.playground.pipeline.mapper.PipelineJobMapper;
import com.study.playground.pipeline.mapper.PipelineJobMappingMapper;
import com.study.playground.pipeline.mapper.PipelineJobExecutionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 파이프라인 정의의 CRUD와 실행 트리거를 담당한다.
 *
 * <p>Job이 독립 엔티티로 분리된 이후, Pipeline은 Job을 직접 소유하지 않고
 * pipeline_job_mapping 테이블을 통해 참조한다. updateMappings는 기존
 * updateJobs(Job 생성/삭제)를 대체하며, 이미 존재하는 Job ID를 매핑에 추가한다.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PipelineDefinitionService {

    private final PipelineDefinitionMapper definitionMapper;
    private final PipelineJobMapper jobMapper;
    private final PipelineJobMappingMapper mappingMapper;
    private final PipelineExecutionMapper executionMapper;
    private final PipelineJobExecutionMapper jobExecutionMapper;
    private final EventPublisher eventPublisher;
    private final AvroSerializer avroSerializer;
    private final DagValidator dagValidator;

    @Transactional
    public PipelineDefinitionResponse create(PipelineDefinitionRequest request) {
        var definition = new PipelineDefinition();
        definition.setName(request.getName());
        definition.setDescription(request.getDescription());
        definition.setStatus("ACTIVE");
        definitionMapper.insert(definition);

        definition = definitionMapper.findById(definition.getId());
        return PipelineDefinitionResponse.from(definition);
    }

    @Transactional(readOnly = true)
    public List<PipelineDefinitionResponse> findAll() {
        return definitionMapper.findAll().stream()
                .map(PipelineDefinitionResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public PipelineDefinitionResponse findById(Long id) {
        var definition = getDefinitionOrThrow(id);
        loadJobDependencies(definition.getId(), definition.getJobs());
        return PipelineDefinitionResponse.from(definition);
    }

    @Transactional
    public PipelineDefinitionResponse updateMappings(Long id, List<PipelineJobMappingRequest> mappingRequests) {
        getDefinitionOrThrow(id);

        // 기존 매핑된 Job들의 의존성 삭제 후 매핑 삭제
        jobMapper.deleteDependenciesByDefinitionId(id);
        mappingMapper.deleteByDefinitionId(id);

        if (!mappingRequests.isEmpty()) {
            // 매핑 삽입
            List<PipelineJobMapping> mappings = mappingRequests.stream().map(req -> {
                var m = new PipelineJobMapping();
                m.setJobId(req.getJobId());
                m.setExecutionOrder(req.getExecutionOrder());
                return m;
            }).toList();
            mappingMapper.insertBatch(id, mappings);

            // 의존성 삽입
            for (var req : mappingRequests) {
                if (req.getDependsOnJobIds() != null) {
                    for (Long depJobId : req.getDependsOnJobIds()) {
                        jobMapper.insertDependency(id, req.getJobId(), depJobId);
                    }
                }
            }
        }

        // DAG 검증
        var definition = definitionMapper.findById(id);
        loadJobDependencies(definition.getId(), definition.getJobs());
        if (definition.getJobs() != null && !definition.getJobs().isEmpty()) {
            dagValidator.validate(definition.getJobs());
        }

        return PipelineDefinitionResponse.from(definition);
    }

    @Transactional(readOnly = true)
    public List<PipelineExecutionResponse> getExecutions(Long pipelineDefinitionId) {
        return executionMapper.findByPipelineDefinitionId(pipelineDefinitionId).stream()
                .map(e -> {
                    e.setJobExecutions(jobExecutionMapper.findByExecutionId(e.getId()));
                    return toExecutionResponse(e);
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public PipelineExecutionResponse getExecution(UUID executionId) {
        var execution = executionMapper.findById(executionId);
        if (execution == null) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND, "Execution not found: " + executionId);
        }
        execution.setJobExecutions(jobExecutionMapper.findByExecutionId(executionId));
        return toExecutionResponse(execution);
    }

    private PipelineExecutionResponse toExecutionResponse(PipelineExecution execution) {
        return PipelineExecutionResponse.from(execution, execution.getJobExecutions());
    }

    @Transactional
    public PipelineExecutionResponse execute(Long id) {
        var definition = getDefinitionOrThrow(id);
        loadJobDependencies(definition.getId(), definition.getJobs());

        if (definition.getJobs() == null || definition.getJobs().isEmpty()) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT, "실행할 Job이 없습니다");
        }

        // DAG 검증
        dagValidator.validate(definition.getJobs());

        // 실행 레코드 생성
        var execution = new PipelineExecution();
        execution.setId(UUID.randomUUID());
        execution.setPipelineDefinitionId(id);
        execution.setStatus(PipelineStatus.PENDING);
        execution.setStartedAt(LocalDateTime.now());
        execution.setTraceParent(TraceContextUtil.captureTraceParent());
        executionMapper.insert(execution);

        // Job → JobExecution 변환: 각 Job이 하나의 JobExecution이 된다
        List<PipelineJobExecution> jobExecutions = new ArrayList<>();
        int order = 1;
        for (var job : definition.getJobs()) {
            var je = new PipelineJobExecution();
            je.setJobOrder(order++);
            je.setJobType(job.getJobType());
            je.setJobName(job.getJobName());
            je.setJobId(job.getId());
            je.setStatus(JobExecutionStatus.PENDING);
            jobExecutions.add(je);
        }
        jobExecutionMapper.insertBatch(execution.getId(), jobExecutions);

        // Outbox 이벤트 발행
        List<String> stepNames = jobExecutions.stream().map(PipelineJobExecution::getJobName).toList();
        var event = PipelineExecutionStartedEvent.newBuilder()
                .setExecutionId(execution.getId().toString())
                .setTicketId(null)
                .setPipelineDefinitionId(id)
                .setSteps(stepNames)
                .build();

        eventPublisher.publish(
                "PIPELINE"
                , execution.getId().toString()
                , "PIPELINE_EXECUTION_STARTED"
                , avroSerializer.serialize(event)
                , Topics.PIPELINE_CMD_EXECUTION
                , execution.getId().toString()
        );

        return PipelineExecutionResponse.accepted(execution);
    }

    @Transactional
    public void delete(Long id) {
        getDefinitionOrThrow(id);

        // 실행 중인 파이프라인이 있는지 확인
        var executions = executionMapper.findByPipelineDefinitionId(id);
        boolean hasRunning = executions.stream()
                .anyMatch(e -> e.getStatus() == PipelineStatus.RUNNING);
        if (hasRunning) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT, "실행 중인 파이프라인은 삭제할 수 없습니다");
        }

        // 매핑된 Job들의 의존성 삭제
        jobMapper.deleteDependenciesByDefinitionId(id);
        // 매핑 삭제 (Job 자체는 독립 엔티티이므로 삭제하지 않음)
        mappingMapper.deleteByDefinitionId(id);
        // 정의 삭제
        definitionMapper.delete(id);
    }

    private PipelineDefinition getDefinitionOrThrow(Long id) {
        var definition = definitionMapper.findById(id);
        if (definition == null) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND, "파이프라인 정의를 찾을 수 없습니다: " + id);
        }
        return definition;
    }

    private void loadJobDependencies(Long definitionId, List<PipelineJob> jobs) {
        if (jobs == null || definitionId == null) return;
        for (var job : jobs) {
            job.setDependsOnJobIds(jobMapper.findDependsOnJobIds(definitionId, job.getId()));
        }
    }
}
