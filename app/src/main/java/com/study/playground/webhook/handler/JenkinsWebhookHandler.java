package com.study.playground.webhook.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.study.playground.adapter.JenkinsAdapter;
import com.study.playground.common.idempotency.ProcessedEvent;
import com.study.playground.common.idempotency.ProcessedEventMapper;
import com.study.playground.pipeline.engine.PipelineEngine;
import com.study.playground.webhook.dto.JenkinsWebhookPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class JenkinsWebhookHandler {

    private final ObjectMapper objectMapper;
    private final ProcessedEventMapper processedEventMapper;
    private final PipelineEngine pipelineEngine;
    private final JenkinsAdapter jenkinsAdapter;

    public void handle(String rawMessage) {
        try {
            // Redpanda Connect wrapper: { webhookSource, payload (JSON string), headers, receivedAt }
            JsonNode wrapper = objectMapper.readTree(rawMessage);
            String payloadStr = wrapper.has("payload") ? wrapper.get("payload").asText() : rawMessage;

            JenkinsWebhookPayload payload = objectMapper.readValue(payloadStr, JenkinsWebhookPayload.class);

            if (payload.executionId() == null || payload.stepOrder() == null) {
                log.warn("Jenkins webhook missing executionId or stepOrder: {}", payloadStr);
                return;
            }

            // 멱등성 체크
            String correlationId = "jenkins:" + payload.executionId() + ":" + payload.stepOrder();
            String eventType = "WEBHOOK_RECEIVED";

            if (processedEventMapper.existsByCorrelationIdAndEventType(correlationId, eventType)) {
                log.info("Duplicate Jenkins webhook ignored: {}", correlationId);
                return;
            }

            ProcessedEvent processedEvent = new ProcessedEvent();
            processedEvent.setCorrelationId(correlationId);
            processedEvent.setEventType(eventType);
            processedEventMapper.insert(processedEvent);

            // Jenkins 콘솔 로그 조회
            String consoleLog = jenkinsAdapter.getConsoleLog(payload.jobName(), payload.buildNumber());
            String buildLog;
            if (consoleLog != null && !consoleLog.isBlank()) {
                buildLog = String.format("=== Jenkins %s #%d %s (%dms) ===\n%s",
                        payload.jobName(), payload.buildNumber(), payload.result(),
                        payload.duration(), consoleLog);
            } else {
                buildLog = String.format("Jenkins build #%d %s in %dms | url: %s",
                        payload.buildNumber(), payload.result(), payload.duration(),
                        payload.url() != null ? payload.url() : "N/A");
            }

            log.info("Jenkins webhook processed: executionId={}, stepOrder={}, result={}",
                    payload.executionId(), payload.stepOrder(), payload.result());

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
}
