package com.study.playground.common.outbox;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.*;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Transactional Outbox 패턴의 폴링 퍼블리셔.
 *
 * DB outbox 테이블에 저장된 이벤트를 주기적으로 폴링하여 Kafka로 발행한다.
 * 트랜잭션과 메시지 발행의 원자성을 보장하기 위해, 비즈니스 로직은 DB에만 쓰고
 * 이 폴러가 비동기로 Kafka 발행을 담당한다.
 *
 * E2E 트레이스 연결:
 * outbox 이벤트에 저장된 traceparent를 복원하여 원래 HTTP 요청의 trace에
 * Kafka 발행 스팬을 연결한다. Tempo에서 하나의 trace로 시각화된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxPoller {

    private static final int MAX_RETRIES = 5;
    private static final String CE_SPECVERSION = "1.0";

    private final OutboxMapper outboxMapper;
    private final KafkaTemplate<String, byte[]> kafkaTemplate;

    @Value("${spring.application.name}")
    private String applicationName;

    /**
     * PENDING 상태의 outbox 이벤트를 폴링하여 Kafka로 발행한다.
     *
     * 흐름: PENDING 조회 → trace context 복원 → Kafka 발행(동기 대기 5s) → SENT 마킹.
     * 발행 실패 시 retryCount를 증가시키고, MAX_RETRIES 초과 시 DEAD로 전이하여
     * 무한 재시도를 방지한다.
     */
    @Transactional
    @Scheduled(fixedDelay = 500)
    public void pollAndPublish() {
        var events = outboxMapper.findPendingEvents(50);
        if (events.isEmpty()) {
            return;
        }
        for (var event : events) {
            try {
                ProducerRecord<String, byte[]> record = new ProducerRecord<>(
                        event.getTopic()
                        , null
                        , event.getAggregateId()
                        , event.getPayload()
                );
                addHeaders(record, event);

                // traceparent가 있으면 원래 trace에 연결하여 발행
                publishWithTraceContext(record, event);

                // 메시지 적재 성공 처리
                outboxMapper.markAsSent(event.getId());

                log.debug("Published outbox event: type={}, aggregateId={}",
                        event.getEventType(), event.getAggregateId());
            } catch (Exception e) {
                log.error("Failed to publish outbox event: id={}, type={}, retryCount={}",
                        event.getId(), event.getEventType(), event.getRetryCount(), e);

                // MAX_RETRIES 초과 시 DEAD 처리하여 poison event가 폴링을 계속 점유하는 것을 방지
                if (event.getRetryCount() != null && event.getRetryCount() >= MAX_RETRIES) {
                    outboxMapper.markAsDead(event.getId());
                    log.warn("Outbox event exceeded max retries, marked as DEAD: id={}", event.getId());
                } else {
                    // 재시도 카운트 증가
                    outboxMapper.incrementRetryCount(event.getId());
                }
            }
        }
    }

    /**
     * 저장된 traceparent로 trace context를 복원하고 Kafka 메시지를 발행한다.
     *
     * traceparent가 없으면(OTel Agent 없이 실행) 새 trace로 발행된다.
     * traceparent가 있으면 원래 HTTP 요청 trace의 자식 스팬으로 발행되어
     * Tempo에서 HTTP → Outbox → Kafka → Connect가 하나의 trace로 연결된다.
     */
    private void publishWithTraceContext(ProducerRecord<String, byte[]> record
            , OutboxEvent event) throws Exception {
        SpanContext parentContext = parseTraceParent(event.getTraceParent());
        if (parentContext == null) {
            kafkaTemplate.send(record).get(5, TimeUnit.SECONDS);
            return;
        }

        Tracer tracer = GlobalOpenTelemetry.getTracer("outbox-poller");
        Span span = tracer.spanBuilder("OutboxPoller.publish")
                .setParent(Context.current().with(Span.wrap(parentContext)))
                .setAttribute("outbox.event.id", event.getId())
                .setAttribute("outbox.event.type", event.getEventType())
                .setAttribute("outbox.aggregate.id", event.getAggregateId())
                .startSpan();

        try (Scope ignored = span.makeCurrent()) {
            kafkaTemplate.send(record).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }

    /**
     * W3C traceparent 문자열을 SpanContext로 파싱한다.
     * 형식: 00-{traceId(32hex)}-{spanId(16hex)}-{flags(2hex)}
     */
    private SpanContext parseTraceParent(String traceParent) {
        if (traceParent == null || traceParent.isEmpty()) {
            return null;
        }
        String[] parts = traceParent.split("-");
        if (parts.length < 4) {
            return null;
        }
        return SpanContext.createFromRemoteParent(
                parts[1]
                , parts[2]
                , TraceFlags.getSampled()
                , TraceState.getDefault()
        );
    }

    /**
     * ProducerRecord에 CloudEvents 표준 헤더와 레거시 호환 헤더를 설정한다.
     *
     * CloudEvents spec v1.0 필수 속성(specversion, id, source, type)을 ce_ 접두사로 추가하여
     * 컨슈머가 이벤트 메타데이터를 표준 방식으로 파싱할 수 있게 한다.
     * correlationId는 CloudEvents 확장 속성으로 ce_ 접두사를 붙여 전달한다.
     */
    private void addHeaders(ProducerRecord<String, byte[]> record, OutboxEvent event) {
        // CloudEvents 필수 속성 (spec v1.0)
        record.headers().add("ce_specversion", CE_SPECVERSION.getBytes(StandardCharsets.UTF_8));
        record.headers().add("ce_id", String.valueOf(event.getId()).getBytes(StandardCharsets.UTF_8));
        record.headers().add("ce_source", ("/" + applicationName).getBytes(StandardCharsets.UTF_8));
        record.headers().add("ce_type", event.getEventType().getBytes(StandardCharsets.UTF_8));

        // CloudEvents 확장 속성: 분산 추적용 correlation ID
        if (event.getCorrelationId() != null) {
            record.headers().add("ce_correlationid", event.getCorrelationId().getBytes(StandardCharsets.UTF_8));
        }
    }
}
