package com.study.playground.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.study.playground.kafka.topic.Topics;
import com.study.playground.webhook.handler.JenkinsWebhookHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.DltStrategy;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Slf4j
@Component
@RequiredArgsConstructor
public class WebhookEventConsumer {

    private final JenkinsWebhookHandler jenkinsWebhookHandler;
    private final ObjectMapper objectMapper;

    @RetryableTopic(
            attempts = "4",
            backoff = @Backoff(delay = 1000, multiplier = 2.0, maxDelay = 8000),
            dltStrategy = DltStrategy.FAIL_ON_ERROR,
            dltTopicSuffix = "-dlt",
            retryTopicSuffix = "-retry"
    )
    @KafkaListener(topics = Topics.WEBHOOK_INBOUND, groupId = "webhook-processor")
    public void onWebhookEvent(ConsumerRecord<String, byte[]> record) {
        String payload = new String(record.value(), StandardCharsets.UTF_8);
        String source = resolveWebhookSource(record.key(), payload);
        log.info("Webhook event received: key={}, source={}, size={}",
                record.key(), source, payload.length());

        // key가 비어 있거나 달라져도 payload.webhookSource로 보조 라우팅
        if ("JENKINS".equalsIgnoreCase(source)) {
            jenkinsWebhookHandler.handle(payload);
        } else {
            log.warn("Unknown webhook source: key={}, resolvedSource={}", record.key(), source);
        }
    }

    @DltHandler
    public void onWebhookEventDlt(ConsumerRecord<String, byte[]> record) {
        log.error("[DLT] Webhook processing failed after retries: topic={}, key={}, partition={}, offset={}",
                record.topic(), record.key(), record.partition(), record.offset());
    }

    private String resolveWebhookSource(String key, String payload) {
        if (key != null && !key.isBlank()) {
            return key;
        }
        try {
            JsonNode wrapper = objectMapper.readTree(payload);
            if (wrapper.hasNonNull("webhookSource")) {
                return wrapper.get("webhookSource").asText();
            }
        } catch (Exception e) {
            log.debug("Failed to parse webhookSource from payload: {}", e.getMessage());
        }
        return null;
    }
}
