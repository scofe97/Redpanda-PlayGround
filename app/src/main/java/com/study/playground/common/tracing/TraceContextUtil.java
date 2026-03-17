package com.study.playground.common.tracing;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.*;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;

import java.util.Map;

/**
 * OTel trace context의 캡처·복원 유틸.
 *
 * <p>DB에 저장된 W3C traceparent 문자열을 통해 비동기 경로(webhook resume, timeout)에서
 * 원래 trace에 span을 연결한다. Outbox, 파이프라인 생성, webhook 재개 3곳에서 재사용된다.
 */
public final class TraceContextUtil {

    private TraceContextUtil() {}

    /**
     * 현재 활성 스팬의 trace context를 W3C traceparent 형식으로 캡처한다.
     * OTel Agent가 없으면 SpanContext가 invalid이므로 null을 반환한다.
     */
    public static String captureTraceParent() {
        SpanContext ctx = Span.current().getSpanContext();
        if (!ctx.isValid()) {
            return null;
        }
        return String.format("00-%s-%s-01", ctx.getTraceId(), ctx.getSpanId());
    }

    /**
     * W3C traceparent 문자열을 SpanContext로 파싱한다.
     * 형식: 00-{traceId(32hex)}-{spanId(16hex)}-{flags(2hex)}
     *
     * @return 파싱된 SpanContext, 입력이 null이거나 형식이 올바르지 않으면 null
     */
    public static SpanContext parseTraceParent(String traceParent) {
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
     * 저장된 traceParent로 OTel context를 복원하고, 새 span 안에서 action을 실행한다.
     *
     * <p>traceParent가 null이거나 파싱 실패 시 새 trace로 실행된다(graceful degradation).
     *
     * @param traceParent  W3C traceparent 문자열 (nullable)
     * @param spanName     생성할 span 이름
     * @param attributes   span에 설정할 속성 (key-value)
     * @param action       실행할 로직
     */
    public static void executeWithRestoredTrace(String traceParent, String spanName
            , Map<String, String> attributes, Runnable action) {
        SpanContext parentContext = parseTraceParent(traceParent);
        if (parentContext == null) {
            action.run();
            return;
        }

        Tracer tracer = GlobalOpenTelemetry.getTracer("pipeline-engine");
        SpanBuilder spanBuilder = tracer.spanBuilder(spanName)
                .setParent(Context.current().with(Span.wrap(parentContext)));
        attributes.forEach(spanBuilder::setAttribute);
        Span span = spanBuilder.startSpan();

        try (Scope ignored = span.makeCurrent()) {
            action.run();
        } catch (Exception e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }
}
