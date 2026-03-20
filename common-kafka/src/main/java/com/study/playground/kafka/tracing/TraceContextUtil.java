package com.study.playground.kafka.tracing;

import java.util.Map;

/**
 * OTel trace context의 캡처·복원 유틸.
 *
 * <p>DB에 저장된 W3C traceparent 문자열을 통해 비동기 경로(webhook resume, timeout)에서
 * 원래 trace에 span을 연결한다. Outbox, 파이프라인 생성, webhook 재개 3곳에서 재사용된다.
 *
 * <p>OTel이 compileOnly이므로 클래스 로드 시점에 가용 여부를 확인하고,
 * 없으면 모든 trace 관련 기능이 no-op으로 동작한다.
 */
public final class TraceContextUtil {

    private static final boolean OTEL_AVAILABLE;

    static {
        boolean available;
        try {
            Class.forName("io.opentelemetry.api.trace.Span");
            available = true;
        } catch (ClassNotFoundException e) {
            available = false;
        }
        OTEL_AVAILABLE = available;
    }

    private TraceContextUtil() {}

    public static boolean isOtelAvailable() {
        return OTEL_AVAILABLE;
    }

    /**
     * 현재 활성 스팬의 trace context를 W3C traceparent 형식으로 캡처한다.
     * OTel이 classpath에 없거나 Agent가 없으면 null을 반환한다.
     */
    public static String captureTraceParent() {
        if (!OTEL_AVAILABLE) {
            return null;
        }
        return OtelBridge.captureTraceParent();
    }

    /**
     * W3C traceparent 문자열을 파싱하여 유효성을 검증한다.
     * @return 파싱 성공 여부
     */
    public static boolean isValidTraceParent(String traceParent) {
        if (traceParent == null || traceParent.isEmpty()) {
            return false;
        }
        String[] parts = traceParent.split("-");
        return parts.length >= 4;
    }

    /**
     * 저장된 traceparent로 OTel context를 복원하고, 새 span 안에서 action을 실행한다.
     * OTel이 없으면 action만 실행한다 (graceful degradation).
     */
    public static void executeWithRestoredTrace(String traceParent, String spanName
            , Map<String, String> attributes, Runnable action) {
        if (!OTEL_AVAILABLE || !isValidTraceParent(traceParent)) {
            action.run();
            return;
        }
        OtelBridge.executeWithRestoredTrace(traceParent, spanName, attributes, action);
    }

    /**
     * OutboxPoller 전용: traceparent 복원 + span 생성 + 발행 action 실행.
     * OTel이 없으면 action만 실행한다.
     */
    public static void publishWithTrace(String traceParent, String spanName
            , Long eventId, String eventType, String aggregateId
            , Runnable action) {
        if (!OTEL_AVAILABLE || !isValidTraceParent(traceParent)) {
            action.run();
            return;
        }
        OtelBridge.publishWithTrace(traceParent, spanName, eventId, eventType, aggregateId, action);
    }

    /**
     * OTel API를 직접 참조하는 내부 클래스.
     * OTEL_AVAILABLE=true일 때만 이 클래스가 로드되므로 ClassNotFoundException이 발생하지 않는다.
     */
    private static final class OtelBridge {

        static String captureTraceParent() {
            io.opentelemetry.api.trace.SpanContext ctx =
                    io.opentelemetry.api.trace.Span.current().getSpanContext();
            if (!ctx.isValid()) {
                return null;
            }
            return String.format("00-%s-%s-01", ctx.getTraceId(), ctx.getSpanId());
        }

        static io.opentelemetry.api.trace.SpanContext parseTraceParent(String traceParent) {
            String[] parts = traceParent.split("-");
            if (parts.length < 4) {
                return null;
            }
            return io.opentelemetry.api.trace.SpanContext.createFromRemoteParent(
                    parts[1]
                    , parts[2]
                    , io.opentelemetry.api.trace.TraceFlags.getSampled()
                    , io.opentelemetry.api.trace.TraceState.getDefault()
            );
        }

        static void executeWithRestoredTrace(String traceParent, String spanName
                , Map<String, String> attributes, Runnable action) {
            var parentContext = parseTraceParent(traceParent);
            if (parentContext == null) {
                action.run();
                return;
            }

            var tracer = io.opentelemetry.api.GlobalOpenTelemetry.getTracer("pipeline-engine");
            var spanBuilder = tracer.spanBuilder(spanName)
                    .setParent(io.opentelemetry.context.Context.current()
                            .with(io.opentelemetry.api.trace.Span.wrap(parentContext)));
            attributes.forEach(spanBuilder::setAttribute);
            var span = spanBuilder.startSpan();

            try (var ignored = span.makeCurrent()) {
                action.run();
            } catch (Exception e) {
                span.setStatus(io.opentelemetry.api.trace.StatusCode.ERROR, e.getMessage());
                span.recordException(e);
                throw e;
            } finally {
                span.end();
            }
        }

        static void publishWithTrace(String traceParent, String spanName
                , Long eventId, String eventType, String aggregateId
                , Runnable action) {
            var parentContext = parseTraceParent(traceParent);
            if (parentContext == null) {
                action.run();
                return;
            }

            var tracer = io.opentelemetry.api.GlobalOpenTelemetry.getTracer("outbox-poller");
            var span = tracer.spanBuilder(spanName)
                    .setParent(io.opentelemetry.context.Context.current()
                            .with(io.opentelemetry.api.trace.Span.wrap(parentContext)))
                    .setAttribute("outbox.event.id", eventId)
                    .setAttribute("outbox.event.type", eventType)
                    .setAttribute("outbox.aggregate.id", aggregateId)
                    .startSpan();

            try (var ignored = span.makeCurrent()) {
                action.run();
            } catch (Exception e) {
                span.setStatus(io.opentelemetry.api.trace.StatusCode.ERROR, e.getMessage());
                span.recordException(e);
                throw e;
            } finally {
                span.end();
            }
        }
    }
}
