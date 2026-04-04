package com.study.playground.operatorstub.listener;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.study.playground.avro.executor.ExecutorJobCompletedEvent;
import com.study.playground.avro.executor.ExecutorJobStartedEvent;
import com.study.playground.kafka.serialization.AvroSerializer;
import com.study.playground.kafka.topic.Topics;
import com.study.playground.operatorstub.domain.OperatorJobRepository;
import com.study.playground.operatorstub.domain.OperatorJobStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Slf4j
public class JobCompletionListener {

    private static final byte AVRO_MAGIC_BYTE = 0x00;

    private final OperatorJobRepository operatorJobRepository;
    private final AvroSerializer avroSerializer;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = Topics.EXECUTOR_EVT_JOB_STARTED
            , groupId = "operator-stub-group"
    )
    @Transactional
    public void onJobStarted(ConsumerRecord<String, byte[]> record) {
        byte[] value = record.value();
        long executionJobId;

        if (isAvroFormat(value)) {
            ExecutorJobStartedEvent event = avroSerializer.deserialize(
                    value, ExecutorJobStartedEvent.getClassSchema());
            executionJobId = event.getExecutionJobId();
        } else {
            JsonNode json = parseJson(value);
            if (json == null) return;
            executionJobId = json.get("executionJobId").asLong();
        }

        operatorJobRepository.findById(executionJobId)
                .ifPresent(job -> {
                    job.updateStatus(OperatorJobStatus.RUNNING);
                    operatorJobRepository.save(job);
                    log.info("[OpListener] Job RUNNING: id={}, jobName={}"
                            , job.getId(), job.getJobName());
                });
    }

    @KafkaListener(
            topics = Topics.EXECUTOR_EVT_JOB_COMPLETED
            , groupId = "operator-stub-group"
    )
    @Transactional
    public void onJobCompleted(ConsumerRecord<String, byte[]> record) {
        byte[] value = record.value();
        long executionJobId;
        boolean success;
        long durationMs;

        if (isAvroFormat(value)) {
            ExecutorJobCompletedEvent event = avroSerializer.deserialize(
                    value, ExecutorJobCompletedEvent.getClassSchema());
            executionJobId = event.getExecutionJobId();
            success = event.getSuccess();
            durationMs = event.getDurationMs();
        } else {
            JsonNode json = parseJson(value);
            if (json == null) return;
            executionJobId = json.get("executionJobId").asLong();
            success = "SUCCESS".equalsIgnoreCase(json.get("result").asText());
            durationMs = json.has("duration") ? json.get("duration").asLong() : 0;
        }

        operatorJobRepository.findById(executionJobId)
                .ifPresent(job -> {
                    OperatorJobStatus newStatus = success
                            ? OperatorJobStatus.SUCCESS
                            : OperatorJobStatus.FAILED;
                    job.updateStatus(newStatus);
                    operatorJobRepository.save(job);
                    log.info("[OpListener] Job {}: id={}, jobName={}, duration={}ms"
                            , newStatus, job.getId(), job.getJobName(), durationMs);
                });
    }

    private boolean isAvroFormat(byte[] data) {
        return data != null && data.length > 5 && data[0] == AVRO_MAGIC_BYTE;
    }

    private JsonNode parseJson(byte[] data) {
        try {
            return objectMapper.readTree(data);
        } catch (Exception e) {
            log.error("[OpListener] Failed to parse message as JSON: {}", e.getMessage());
            return null;
        }
    }
}
