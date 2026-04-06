package com.study.playground.operatorstub.listener;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.study.playground.avro.executor.ExecutorJobCompletedEvent;
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
            topics = Topics.EXECUTOR_NOTIFY_JOB_COMPLETED
            , groupId = "operator-stub-group"
    )
    @Transactional
    public void onJobCompleted(ConsumerRecord<String, byte[]> record) {
        byte[] value = record.value();
        String jobExcnId;
        boolean success;
        String result;
        String logFilePath;
        String logFileYn;

        if (isAvroFormat(value)) {
            ExecutorJobCompletedEvent event = avroSerializer.deserialize(
                    value, ExecutorJobCompletedEvent.getClassSchema());
            jobExcnId = event.getJobExcnId();
            success = event.getSuccess();
            result = event.getResult();
            logFilePath = event.getLogFilePath();
            logFileYn = event.getLogFileYn();
        } else {
            JsonNode json = parseJson(value);
            if (json == null) return;
            jobExcnId = json.get("jobExcnId").asText();
            success = json.has("success") && json.get("success").asBoolean();
            result = json.has("result") ? json.get("result").asText() : null;
            logFilePath = json.has("logFilePath") ? json.get("logFilePath").asText() : null;
            logFileYn = json.has("logFileYn") ? json.get("logFileYn").asText() : "N";
        }

        operatorJobRepository.findById(Long.parseLong(jobExcnId))
                .ifPresent(job -> {
                    var newStatus = success ? OperatorJobStatus.SUCCESS : OperatorJobStatus.FAILURE;
                    job.updateStatus(newStatus);
                    operatorJobRepository.save(job);
                    log.info("[OpListener] Job {}: id={}, jobName={}, logFile={}, logFileYn={}"
                            , newStatus, job.getId(), job.getJobName(), logFilePath, logFileYn);
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
