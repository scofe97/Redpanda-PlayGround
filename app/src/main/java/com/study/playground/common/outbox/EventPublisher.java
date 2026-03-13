package com.study.playground.common.outbox;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class EventPublisher {

    private final OutboxMapper outboxMapper;

    public void publish(String aggregateType
            , String aggregateId
            , String eventType
            , byte[] payload
            , String topic
            , String correlationId) {
        OutboxEvent event = OutboxEvent.of(aggregateType, aggregateId, eventType, payload, topic, correlationId);
        event.setTraceParent(captureTraceParent());
        outboxMapper.insert(event);
    }

    /**
     * 현재 활성 스팬의 trace context를 W3C traceparent 형식으로 캡처한다.
     * OTel Agent가 없으면 SpanContext가 invalid이므로 null을 반환한다.
     * 형식: 00-{traceId(32hex)}-{spanId(16hex)}-01
     */
    private String captureTraceParent() {
        SpanContext ctx = Span.current().getSpanContext();
        if (!ctx.isValid()) {
            return null;
        }
        return String.format("00-%s-%s-01", ctx.getTraceId(), ctx.getSpanId());
    }
}
