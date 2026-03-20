package com.study.playground.pipeline.jenkins;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.study.playground.pipeline.adapter.JenkinsAdapter;
import com.study.playground.pipeline.domain.PipelineJobType;
import com.study.playground.kafka.outbox.OutboxEvent;
import com.study.playground.kafka.outbox.OutboxEventHandler;
import com.study.playground.pipeline.mapper.JobMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * JENKINS aggregate 타입의 Outbox 이벤트를 처리하여 Jenkins API를 호출한다.
 *
 * <p>Outbox 패턴을 Jenkins API 호출에 확장한 것으로, DB 트랜잭션과 Jenkins 등록의
 * 원자성을 보장한다. Jenkins가 일시적으로 불가용하면 Outbox 재시도 메커니즘이
 * 지수 백오프로 재시도한다.</p>
 *
 * <p>이벤트 타입별 동작:
 * <ul>
 *   <li>{@code JENKINS_JOB_CREATE}: Jenkins 파이프라인 생성 (멱등 upsert)</li>
 *   <li>{@code JENKINS_JOB_UPDATE}: Jenkins 파이프라인 스크립트 업데이트</li>
 *   <li>{@code JENKINS_JOB_DELETE}: Jenkins 파이프라인 삭제</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JenkinsOutboxHandler implements OutboxEventHandler {

    private final JenkinsAdapter jenkinsAdapter;
    private final JobMapper jobMapper;
    private final ObjectMapper objectMapper;

    @Override
    public boolean supports(String aggregateType) {
        return "JENKINS".equals(aggregateType);
    }

    @Override
    public void handle(OutboxEvent event) throws Exception {
        JsonNode payload = objectMapper.readTree(event.getPayload());
        String eventType = event.getEventType();

        switch (eventType) {
            case "JENKINS_JOB_CREATE" -> handleCreate(payload);
            case "JENKINS_JOB_UPDATE" -> handleUpdate(payload);
            case "JENKINS_JOB_DELETE" -> handleDelete(payload);
            default -> log.warn("Unknown Jenkins event type: {}", eventType);
        }
    }

    @Override
    public void onDead(OutboxEvent event) {
        try {
            JsonNode payload = objectMapper.readTree(event.getPayload());
            Long jobId = payload.get("jobId").asLong();
            jobMapper.updateJenkinsStatus(jobId, "FAILED");
            log.warn("Jenkins outbox 이벤트 DEAD → jenkins_status=FAILED: jobId={}", jobId);
        } catch (Exception e) {
            log.error("onDead 처리 실패: eventId={}", event.getId(), e);
        }
    }

    private void handleCreate(JsonNode payload) {
        Long jobId = payload.get("jobId").asLong();
        String script = payload.get("script").asText();
        String jobType = payload.get("jobType").asText();
        String folderName = PipelineJobType.valueOf(jobType).toFolderName();
        String jenkinsJobName = "playground-job-%d".formatted(jobId);

        jenkinsAdapter.upsertPipelineJob(folderName, jenkinsJobName, script);
        jobMapper.updateJenkinsStatus(jobId, "ACTIVE");

        log.info("Jenkins 파이프라인 생성 완료: {}/{}", folderName, jenkinsJobName);
    }

    private void handleUpdate(JsonNode payload) {
        Long jobId = payload.get("jobId").asLong();
        String script = payload.get("script").asText();
        String jobType = payload.get("jobType").asText();
        String folderName = PipelineJobType.valueOf(jobType).toFolderName();
        String jenkinsJobName = "playground-job-%d".formatted(jobId);

        jenkinsAdapter.upsertPipelineJob(folderName, jenkinsJobName, script);

        log.info("Jenkins 파이프라인 업데이트 완료: {}/{}", folderName, jenkinsJobName);
    }

    private void handleDelete(JsonNode payload) {
        Long jobId = payload.get("jobId").asLong();
        String jobType = payload.get("jobType").asText();
        String folderName = PipelineJobType.valueOf(jobType).toFolderName();
        String jenkinsJobName = "playground-job-%d".formatted(jobId);

        jenkinsAdapter.deletePipelineJob(folderName, jenkinsJobName);

        log.info("Jenkins 파이프라인 삭제 완료: {}/{}", folderName, jenkinsJobName);
    }
}
