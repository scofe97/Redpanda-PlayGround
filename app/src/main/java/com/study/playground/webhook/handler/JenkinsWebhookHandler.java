package com.study.playground.webhook.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.study.playground.pipeline.adapter.JenkinsAdapter;
import com.study.playground.common.idempotency.ProcessedEvent;
import com.study.playground.common.idempotency.ProcessedEventMapper;
import com.study.playground.pipeline.engine.PipelineEngine;
import com.study.playground.webhook.dto.JenkinsWebhookPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Jenkins 웹훅 콜백을 처리하여 파이프라인 실행을 재개하는 핸들러.
 *
 * WebhookEventConsumer가 Kafka에서 수신한 원시 메시지를 이 핸들러로 라우팅한다.
 * 핸들러는 멱등성 체크 → 콘솔 로그 조회 → 파이프라인 재개 순으로 처리한다.
 *
 * 멱등성을 ProcessedEvent 테이블의 ON CONFLICT DO NOTHING으로 보장하는 이유는
 * Kafka 리밸런싱이나 Redpanda Connect 재전송으로 동일 웹훅이 중복 도착할 수 있기 때문이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JenkinsWebhookHandler {

    /** 콘솔 로그 최대 길이. Jenkins 출력이 수십 MB에 달할 수 있어 DB 저장 전 절삭한다. */
    private static final int MAX_CONSOLE_LOG_LENGTH = 50_000;

    private final ObjectMapper objectMapper;
    private final ProcessedEventMapper processedEventMapper;
    private final PipelineEngine pipelineEngine;
    private final JenkinsAdapter jenkinsAdapter;

    /**
     * Jenkins 웹훅 메시지를 파싱하고 파이프라인 엔진에 결과를 전달한다.
     *
     * Redpanda Connect가 HTTP 웹훅을 Kafka 메시지로 변환할 때
     * { webhookSource, payload (JSON string), headers, receivedAt } 형태의 래퍼를 씌운다.
     * payload 필드가 없으면 rawMessage 자체가 페이로드라고 간주하여
     * 직접 호출 테스트도 가능하도록 했다.
     *
     * JsonProcessingException만 별도로 잡아 로그만 남기는 이유는
     * 형식이 깨진 메시지는 재시도해도 복구 불가능한 poison pill이기 때문이다.
     * 그 외 예외는 RuntimeException으로 래핑하여 Kafka 리스너의 재시도 메커니즘에 맡긴다.
     */
    public void handle(String rawMessage) {
        try {
            JsonNode wrapper = objectMapper.readTree(rawMessage);
            String payloadStr = wrapper.has("payload") ? wrapper.get("payload").asText() : rawMessage;

            JenkinsWebhookPayload payload = objectMapper.readValue(payloadStr, JenkinsWebhookPayload.class);

            if (payload.executionId() == null || payload.stepOrder() == null) {
                log.warn("Jenkins webhook missing executionId or stepOrder: {}", payloadStr);
                return;
            }

            // 멱등성: webhook은 Outbox를 거치지 않으므로 ce_id가 없다.
            // executionId + stepOrder 조합으로 고유 키를 생성하여 중복 검사한다.
            var eventId = "webhook:" + payload.executionId() + ":" + payload.stepOrder();
            if (processedEventMapper.existsByEventId(eventId)) {
                log.info("Duplicate Jenkins webhook ignored: {}", eventId);
                return;
            }
            var processedEvent = new ProcessedEvent();
            processedEvent.setEventId(eventId);
            processedEventMapper.insert(processedEvent);

            // Jenkins 콘솔 로그 조회 후 buildLog 포맷팅
            String buildLog = formatBuildLog(payload);

            log.info("Jenkins webhook processed: executionId={}, stepOrder={}, result={}",
                    payload.executionId(), payload.stepOrder(), payload.result());

            // Break-and-Resume: 중단된 파이프라인을 웹훅 결과와 함께 재개한다.
            // CAS(WAITING_WEBHOOK → SUCCESS/FAILED)로 타임아웃 체커와의 경합을 해소한다.
            pipelineEngine.resumeAfterWebhook(
                    UUID.fromString(payload.executionId()),
                    payload.stepOrder(),
                    payload.result(),
                    buildLog
            );
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.error("Invalid Jenkins webhook JSON, skipping: {}", e.getMessage());
        } catch (Exception e) {
            log.error("Failed to handle Jenkins webhook: {}", e.getMessage(), e);
            throw new RuntimeException("Jenkins webhook processing failed", e);
        }
    }

    /**
     * Jenkins 빌드 결과를 사람이 읽을 수 있는 로그 문자열로 변환한다.
     *
     * 콘솔 로그가 있으면 헤더(job명, 빌드번호, 결과, 소요시간)와 함께 포맷팅하고,
     * 없으면 한 줄 요약으로 대체한다. 로그가 MAX_CONSOLE_LOG_LENGTH를 초과하면
     * 뒷부분을 절삭하여 DB 부하를 방지한다.
     */
    private String formatBuildLog(JenkinsWebhookPayload payload) {
        int buildNum = payload.buildNumber() != null ? payload.buildNumber() : 0;
        long duration = payload.duration() != null ? payload.duration() : 0L;

        String consoleLog = null;
        if (payload.jobName() != null && !payload.jobName().isBlank()) {
            consoleLog = jenkinsAdapter.getConsoleLog(payload.jobName(), buildNum);
        }

        // 대형 빌드 로그 절삭
        if (consoleLog != null && consoleLog.length() > MAX_CONSOLE_LOG_LENGTH) {
            consoleLog = consoleLog.substring(0, MAX_CONSOLE_LOG_LENGTH)
                    + "\n... [truncated, total " + consoleLog.length() + " chars]";
        }

        String jobLabel = payload.jobName() != null ? payload.jobName() : "build";
        if (consoleLog != null && !consoleLog.isBlank()) {
            return String.format("=== Jenkins %s #%d %s (%dms) ===\n%s",
                    jobLabel, buildNum, payload.result(), duration, consoleLog);
        } else {
            return String.format("Jenkins %s #%d %s in %dms | url: %s",
                    jobLabel, buildNum, payload.result(), duration,
                    payload.url() != null ? payload.url() : "N/A");
        }
    }
}
