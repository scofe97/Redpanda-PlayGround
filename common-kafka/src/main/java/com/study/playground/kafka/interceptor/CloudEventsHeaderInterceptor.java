package com.study.playground.kafka.interceptor;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerInterceptor;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.header.Headers;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * CloudEvents Binary Content Mode 헤더를 모든 Kafka 메시지에 자동 부착하는 ProducerInterceptor.
 *
 * 부착 헤더:
 * - ce_specversion: CloudEvents 스펙 버전 (1.0)
 * - ce_id: 이벤트 고유 식별자 (UUID)
 * - ce_source: 이벤트 발생 서비스명
 * - ce_time: 이벤트 발생 시각 (ISO-8601)
 * - trace-id: 분산 추적 ID (MDC에서 전파)
 *
 * ce_type은 OutboxPoller에서 이벤트별로 설정한다.
 */
@Slf4j
@Component
public class CloudEventsHeaderInterceptor implements ProducerInterceptor<String, byte[]> {

    @Value("${spring.application.name:unknown}")
    private String serviceName;

    @Override
    public ProducerRecord<String, byte[]> onSend(ProducerRecord<String, byte[]> record) {
        Headers headers = record.headers();

        addIfAbsent(headers, "ce_specversion", "1.0");
        addIfAbsent(headers, "ce_id", UUID.randomUUID().toString());
        addIfAbsent(headers, "ce_source", "/" + serviceName);
        addIfAbsent(headers, "ce_time", Instant.now().toString());

        // 분산 추적: MDC에서 traceId 전파
        String traceId = MDC.get("traceId");
        if (traceId != null) {
            addIfAbsent(headers, "trace-id", traceId);
        }

        return record;
    }

    /** 이미 같은 키가 있으면 덮어쓰지 않는다. Producer가 명시 설정한 헤더가 우선. */
    private void addIfAbsent(Headers headers, String key, String value) {
        if (headers.lastHeader(key) == null) {
            headers.add(key, value.getBytes(StandardCharsets.UTF_8));
        }
    }

    @Override
    public void onAcknowledgement(RecordMetadata metadata, Exception exception) {
        // no-op
    }

    @Override
    public void close() {
        // no-op
    }

    @Override
    public void configure(Map<String, ?> configs) {
        // Spring DI로 설정하므로 비워둠
    }
}
