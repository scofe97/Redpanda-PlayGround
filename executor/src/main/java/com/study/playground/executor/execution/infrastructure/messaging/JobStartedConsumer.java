package com.study.playground.executor.execution.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.study.playground.executor.execution.domain.model.BuildCallback;
import com.study.playground.executor.execution.domain.port.in.HandleBuildStartedUseCase;
import com.study.playground.kafka.topic.Topics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class JobStartedConsumer {

    private final HandleBuildStartedUseCase handleStartedUseCase;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = Topics.EXECUTOR_EVT_JOB_STARTED
            , groupId = "${spring.kafka.consumer.group-id:executor-group}"
    )
    public void onJobStarted(ConsumerRecord<String, byte[]> record) {
        try {
            JsonNode json = objectMapper.readTree(record.value());
            var callback = BuildCallback.started(
                    json.get("jobId").asText()
                    , json.get("buildNumber").asInt()
            );
            handleStartedUseCase.handle(callback);
        } catch (Exception e) {
            log.error("[JobStarted] Failed: key={}, error={}"
                    , record.key(), e.getMessage(), e);
        }
    }
}
