package com.study.playground.operatorstub.listener;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
public class JobStartedListener {

    private static final byte AVRO_MAGIC_BYTE = 0x00;

    private final OperatorJobRepository operatorJobRepository;
    private final AvroSerializer avroSerializer;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = Topics.EXECUTOR_NOTIFY_JOB_STARTED
            , groupId = "operator-stub-group"
    )
    @Transactional
    public void onJobStarted(ConsumerRecord<String, byte[]> record) {
        byte[] value = record.value();
        String jobExcnId;

        if (isAvroFormat(value)) {
            ExecutorJobStartedEvent event = avroSerializer.deserialize(
                    value, ExecutorJobStartedEvent.getClassSchema());
            jobExcnId = event.getJobExcnId();
        } else {
            JsonNode json = parseJson(value);
            if (json == null) return;
            jobExcnId = json.get("jobExcnId").asText();
        }

        operatorJobRepository.findById(Long.parseLong(jobExcnId))
                .ifPresent(job -> {
                    job.updateStatus(OperatorJobStatus.RUNNING);
                    operatorJobRepository.save(job);
                    log.info("[OpListener] Job RUNNING: id={}, jobName={}"
                            , job.getId(), job.getJobName());
                });
    }

    private boolean isAvroFormat(byte[] data) {
        return data != null && data.length > 5 && data[0] == AVRO_MAGIC_BYTE;
    }

    private JsonNode parseJson(byte[] data) {
        try {
            return objectMapper.readTree(data);
        } catch (Exception e) {
            log.error("[OpListener] Failed to parse started event as JSON: {}", e.getMessage());
            return null;
        }
    }
}
