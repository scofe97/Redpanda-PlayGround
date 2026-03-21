package com.study.playground.pipeline.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.study.playground.avro.pipeline.PipelineExecutionStartedEvent;
import com.study.playground.common.dto.CommonErrorCode;
import com.study.playground.common.exception.BusinessException;
import com.study.playground.kafka.outbox.EventPublisher;
import com.study.playground.kafka.serialization.AvroSerializer;
import com.study.playground.kafka.topic.Topics;
import com.study.playground.kafka.tracing.TraceContextUtil;
import com.study.playground.pipeline.domain.*;
import com.study.playground.pipeline.dag.domain.*;
import com.study.playground.pipeline.dto.JobRequest;
import com.study.playground.pipeline.dto.JobResponse;
import com.study.playground.pipeline.dto.PipelineExecutionResponse;
import com.study.playground.pipeline.mapper.JobMapper;
import com.study.playground.pipeline.mapper.PipelineExecutionMapper;
import com.study.playground.pipeline.mapper.PipelineJobExecutionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Job 독립 CRUD와 Job 단독 실행을 담당한다.
 *
 * <p>Pipeline 정의에 속하지 않는 독립 Job을 생성·관리하고,
 * 단일 Job을 즉시 실행하여 PipelineExecution 1건 + PipelineJobExecution 1건을 생성한다.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JobService {

    private final JobMapper jobMapper;
    private final PipelineExecutionMapper executionMapper;
    private final PipelineJobExecutionMapper jobExecutionMapper;
    private final EventPublisher eventPublisher;
    private final AvroSerializer avroSerializer;
    private final ObjectMapper objectMapper;

    /**
     * Job을 생성하고, jenkinsScript가 있으면 Outbox를 통해 Jenkins 파이프라인을 비동기 등록한다.
     *
     * <p>단일 DB 트랜잭션으로 pipeline_job INSERT와 outbox_event INSERT를 원자적으로 처리한다.
     * OutboxPoller가 비동기로 Jenkins API를 호출하여 파이프라인을 생성한다.</p>
     */
    @Transactional
    public JobResponse create(JobRequest request) {
        var job = new PipelineJob();
        job.setJobName(request.getJobName());
        job.setJobType(request.getJobType());
        job.setPresetId(request.getPresetId());
        job.setConfigJson(request.getConfigJson());
        job.setJenkinsScript(request.getJenkinsScript());
        job.setJenkinsStatus(request.getJenkinsScript() != null ? "PENDING" : null);
        job.setParameterSchemaJson(request.getParameterSchemaJson());
        jobMapper.insert(job);

        if (request.getJenkinsScript() != null && !request.getJenkinsScript().isBlank()) {
            publishJenkinsOutboxEvent("JENKINS_JOB_CREATE", job.getId()
                    , Map.of("jobId", job.getId(), "script", request.getJenkinsScript()
                            , "jobType", job.getJobType().name()));
        }

        return JobResponse.from(jobMapper.findById(job.getId()));
    }

    @Transactional(readOnly = true)
    public List<JobResponse> findAll() {
        return jobMapper.findAll().stream()
                .map(JobResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public JobResponse findById(Long id) {
        return JobResponse.from(getJobOrThrow(id));
    }

    @Transactional
    public JobResponse update(Long id, JobRequest request) {
        var job = getJobOrThrow(id);
        String oldScript = job.getJenkinsScript();
        job.setJobName(request.getJobName());
        job.setJobType(request.getJobType());
        job.setPresetId(request.getPresetId());
        job.setConfigJson(request.getConfigJson());
        job.setJenkinsScript(request.getJenkinsScript());
        job.setParameterSchemaJson(request.getParameterSchemaJson());
        jobMapper.update(job);

        // 스크립트 변경 시 Jenkins 업데이트 이벤트 발행
        String newScript = request.getJenkinsScript();
        if (newScript != null && !newScript.isBlank() && !newScript.equals(oldScript)) {
            publishJenkinsOutboxEvent("JENKINS_JOB_UPDATE", id
                    , Map.of("jobId", id, "script", newScript
                            , "jobType", job.getJobType().name()));
        }

        return JobResponse.from(jobMapper.findById(id));
    }

    @Transactional
    public void delete(Long id) {
        var job = getJobOrThrow(id);

        if (job.getJenkinsScript() != null && "ACTIVE".equals(job.getJenkinsStatus())) {
            jobMapper.updateJenkinsStatus(id, "DELETING");
            publishJenkinsOutboxEvent("JENKINS_JOB_DELETE", id
                    , Map.of("jobId", id, "jobType", job.getJobType().name()));
        }

        jobMapper.delete(id);
    }

    /**
     * Jenkins 등록에 실패한 Job을 재시도한다.
     * FAILED 상태의 Job에 대해 JENKINS_JOB_CREATE 이벤트를 재발행한다.
     */
    @Transactional
    public void retryJenkinsProvision(Long id) {
        var job = getJobOrThrow(id);
        if (!"FAILED".equals(job.getJenkinsStatus())) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT
                    , "FAILED 상태의 Job만 재시도할 수 있습니다. 현재: " + job.getJenkinsStatus());
        }
        if (job.getJenkinsScript() == null || job.getJenkinsScript().isBlank()) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT
                    , "Jenkins 스크립트가 없는 Job은 재시도할 수 없습니다");
        }

        jobMapper.updateJenkinsStatus(id, "PENDING");
        publishJenkinsOutboxEvent("JENKINS_JOB_CREATE", id
                , Map.of("jobId", id, "script", job.getJenkinsScript()
                        , "jobType", job.getJobType().name()));
    }

    /**
     * Job을 단독 실행한다.
     *
     * <p>pipelineDefinitionId가 null인 PipelineExecution을 생성하고,
     * 해당 Job 하나만 포함하는 PipelineJobExecution(jobOrder=1)을 함께 생성한 뒤
     * Outbox를 통해 PIPELINE_CMD_EXECUTION 토픽에 이벤트를 발행한다.</p>
     */
    @Transactional
    public PipelineExecutionResponse execute(Long id, Map<String, String> userParams) {
        var job = getJobOrThrow(id);

        // jenkinsScript가 있는 Job은 ACTIVE 상태에서만 실행 가능
        if (job.getJenkinsScript() != null && !"ACTIVE".equals(job.getJenkinsStatus())) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT
                    , "Jenkins 파이프라인이 아직 준비되지 않았습니다. 상태: " + job.getJenkinsStatus());
        }

        // 실행 레코드 생성 (pipelineDefinitionId = null — Job 단독 실행)
        var execution = new PipelineExecution();
        execution.setId(UUID.randomUUID());
        execution.setPipelineDefinitionId(null);
        execution.setStatus(PipelineStatus.PENDING);
        execution.setStartedAt(LocalDateTime.now());
        execution.setTraceParent(TraceContextUtil.captureTraceParent());
        if (userParams != null && !userParams.isEmpty()) {
            try {
                execution.setParametersJson(objectMapper.writeValueAsString(userParams));
            } catch (Exception e) {
                throw new RuntimeException("JSON 직렬화 실패", e);
            }
        }
        executionMapper.insert(execution);

        // JobExecution 1건 생성
        var jobExecution = new PipelineJobExecution();
        jobExecution.setJobOrder(1);
        jobExecution.setJobType(job.getJobType());
        jobExecution.setJobName(job.getJobName());
        jobExecution.setJobId(job.getId());
        jobExecution.setStatus(JobExecutionStatus.PENDING);
        jobExecutionMapper.insertBatch(execution.getId(), List.of(jobExecution));

        // Outbox 이벤트 발행
        var event = PipelineExecutionStartedEvent.newBuilder()
                .setExecutionId(execution.getId().toString())
                .setTicketId(null)
                .setPipelineDefinitionId(0L)
                .setSteps(List.of(job.getJobName()))
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

    /** Jenkins Outbox 이벤트를 발행한다. aggregate_type=JENKINS, topic은 사용하지 않으므로 빈 문자열. */
    private void publishJenkinsOutboxEvent(String eventType, Long jobId, Map<String, Object> payload) {
        try {
            byte[] payloadBytes = objectMapper.writeValueAsBytes(payload);
            eventPublisher.publish("JENKINS"
                    , String.valueOf(jobId)
                    , eventType
                    , payloadBytes
                    , "jenkins-internal"
                    , String.valueOf(jobId));
        } catch (Exception e) {
            throw new RuntimeException("Jenkins outbox 이벤트 직렬화 실패", e);
        }
    }

    private PipelineJob getJobOrThrow(Long id) {
        var job = jobMapper.findById(id);
        if (job == null) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND, "Job을 찾을 수 없습니다: " + id);
        }
        return job;
    }
}
