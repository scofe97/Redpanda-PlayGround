package com.study.playground.webhook;

import com.study.playground.webhook.handler.JenkinsWebhookHandler;
import io.github.springwolf.core.asyncapi.annotations.AsyncListener;
import io.github.springwolf.core.asyncapi.annotations.AsyncOperation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.DltStrategy;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class WebhookEventConsumer {

    private final JenkinsWebhookHandler jenkinsWebhookHandler;

    @RetryableTopic(
            attempts = "4",
            backoff = @Backoff(delay = 1000, multiplier = 2.0, maxDelay = 8000),
            dltStrategy = DltStrategy.FAIL_ON_ERROR,
            dltTopicSuffix = "-dlt",
            retryTopicSuffix = "-retry"
    )
    @AsyncListener(operation = @AsyncOperation(
            channelName = "playground.webhook.inbound",
            description = "외부 시스템으로부터 수신된 웹훅 이벤트를 처리한다."
    ))
    @KafkaListener(topics = "playground.webhook.inbound", groupId = "webhook-processor")
    public void onWebhookEvent(ConsumerRecord<String, byte[]> record) {
        String payload = new String(record.value());
        String key = record.key();
        log.info("Webhook event received: key={}, size={}", key, payload.length());

        // Redpanda Connect wrapper의 webhookSource로 라우팅
        if ("JENKINS".equals(key)) {
            jenkinsWebhookHandler.handle(payload);
        } else {
            log.warn("Unknown webhook source: key={}", key);
        }
    }

    @DltHandler
    public void onWebhookEventDlt(ConsumerRecord<String, byte[]> record) {
        log.error("[DLT] Webhook processing failed after retries: topic={}, key={}, partition={}, offset={}",
                record.topic(), record.key(), record.partition(), record.offset());
    }
}
