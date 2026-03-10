package com.study.playground.webhook.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Jenkins 웹훅 콜백 페이로드.
 *
 * Jenkins Pipeline의 post { always { ... } } 블록에서 HTTP POST로 전송하는 빌드 결과다.
 * Redpanda Connect가 이 JSON을 Kafka 메시지로 변환하여 WEBHOOK_INBOUND 토픽에 발행한다.
 *
 * ignoreUnknown=true를 설정하는 이유는 Jenkins 플러그인 버전에 따라
 * 예상 밖 필드(artifacts, changeSets 등)가 추가될 수 있기 때문이다.
 * 알 수 없는 필드 때문에 역직렬화가 실패하면 파이프라인 전체가 멈추게 된다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record JenkinsWebhookPayload(
        String executionId,
        Integer stepOrder,
        String jobName,
        Integer buildNumber,
        String result,
        Long duration,
        String url
) {}
