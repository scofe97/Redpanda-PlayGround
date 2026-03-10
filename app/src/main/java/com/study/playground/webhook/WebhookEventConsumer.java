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

/**
 * 외부 시스템(Jenkins 등)의 웹훅 콜백을 Kafka에서 수신하여 소스별 핸들러로 라우팅하는 컨슈머.
 *
 * Redpanda Connect가 HTTP 웹훅을 Kafka 메시지로 변환할 때 record key에 소스명(JENKINS 등)을
 * 설정한다. key가 누락된 경우에는 payload 내부의 webhookSource 필드를 보조 라우팅에 사용한다.
 *
 * @RetryableTopic으로 지수 백오프 재시도(최대 4회)를 구성하는 이유는
 * Jenkins API 일시 장애나 DB 연결 실패 같은 일시적 오류를 자동 복구하기 위해서다.
 * 형식이 깨진 JSON(poison pill)은 JenkinsWebhookHandler 내부에서 잡아 로그만 남기고
 * 정상 반환하므로, 재시도 토픽으로 빠지지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebhookEventConsumer {

    private final JenkinsWebhookHandler jenkinsWebhookHandler;
    private final ObjectMapper objectMapper;

    /**
     * WEBHOOK_INBOUND 토픽에서 웹훅 이벤트를 수신하여 소스별 핸들러로 라우팅한다.
     *
     * tombstone 레코드(value=null)를 무시하는 이유는 Kafka 토픽 컴팩션 시
     * 삭제 마커로 전송되는 레코드이기 때문이다. 이를 방어하지 않으면 NPE가 발생한다.
     */
    @RetryableTopic(
            attempts = "4",
            backoff = @Backoff(delay = 1000, multiplier = 2.0, maxDelay = 8000),
            dltStrategy = DltStrategy.FAIL_ON_ERROR,
            dltTopicSuffix = "-dlt",
            retryTopicSuffix = "-retry"
    )
    @KafkaListener(topics = Topics.WEBHOOK_INBOUND, groupId = "webhook-processor")
    public void onWebhookEvent(ConsumerRecord<String, byte[]> record) {
        // tombstone 레코드 방어
        if (record.value() == null) {
            log.debug("Tombstone record ignored: key={}", record.key());
            return;
        }

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

    /**
     * 재시도를 모두 소진한 메시지를 DLT에서 수신하여 운영자가 확인할 수 있도록 로깅한다.
     *
     * DLT 핸들러에서 예외를 던지지 않는 이유는 DLT 자체도 재시도 루프에 빠지는 것을
     * 막기 위해서다. 알람 연동이 필요하다면 이 메서드에서 처리한다.
     */
    @DltHandler
    public void onWebhookEventDlt(ConsumerRecord<String, byte[]> record) {
        log.error("[DLT] Webhook processing failed after retries: topic={}, key={}, partition={}, offset={}",
                record.topic(), record.key(), record.partition(), record.offset());
    }

    /**
     * 웹훅 소스를 결정한다. record key를 우선하고, 없으면 payload에서 추출한다.
     *
     * key 우선인 이유는 Redpanda Connect가 HTTP 경로(/webhook/jenkins)를 기반으로
     * key를 설정하므로 payload 파싱 없이 빠르게 라우팅할 수 있기 때문이다.
     * payload 폴백은 직접 Kafka 발행 테스트나 key 설정이 누락된 경우를 위한 방어다.
     */
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
