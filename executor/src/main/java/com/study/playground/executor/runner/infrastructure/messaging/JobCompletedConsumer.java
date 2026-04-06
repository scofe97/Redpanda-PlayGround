package com.study.playground.executor.runner.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.study.playground.executor.runner.domain.model.BuildCallback;
import com.study.playground.executor.runner.domain.port.in.HandleBuildCompletedUseCase;
import com.study.playground.kafka.topic.Topics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class JobCompletedConsumer {

    private final HandleBuildCompletedUseCase handleCompletedUseCase;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = Topics.EXECUTOR_EVT_JOB_COMPLETED
            , groupId = "executor-group"
    )
    public void onJobCompleted(ConsumerRecord<String, byte[]> record) {
        try {
            JsonNode json = objectMapper.readTree(record.value());
            var callback = BuildCallback.completed(
                    json.get("executionJobId").asText()
                    , json.get("buildNumber").asInt()
                    , json.has("result") ? json.get("result").asText() : null
            );
            handleCompletedUseCase.handle(callback);
        } catch (Exception e) {
            log.error("[JobCompleted] Failed: key={}, error={}"
                    , record.key(), e.getMessage(), e);
        }
    }
}
