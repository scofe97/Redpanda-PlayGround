package com.study.playground.kafka.outbox.infrastructure.publishing;

import com.study.playground.kafka.outbox.OutboxEvent;
import com.study.playground.kafka.tracing.TraceContextUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * OutboxEvent를 Kafka로 발행하는 인프라 컴포넌트.
 *
 * <p>두 가지 책임을 담당한다:
 * <ol>
 *   <li>CloudEvents 표준 헤더 설정 (ce_specversion, ce_id, ce_source, ce_type)</li>
 *   <li>Kafka 메시지 전송 (동기 대기 5초 타임아웃)</li>
 * </ol>
 *
 * <h3>[EXPERIMENTAL] OpenTelemetry 트레이스 전파</h3>
 * <p>OTel이 classpath에 있으면 저장된 traceparent로 trace context를 복원하여
 * 원래 HTTP 요청의 자식 스팬으로 Kafka 발행을 기록한다.
 * OTel 제거 시 {@code sendWithTraceContext()} 블록을 삭제하고
 * {@code kafkaTemplate.send(record).get(5, SECONDS)}로 교체하면 된다.
 */
@Slf4j
@Component
public class OutboxKafkaPublisher {

    private static final String CE_SPECVERSION = "1.0";

    private final KafkaTemplate<String, byte[]> kafkaTemplate;

    @Value("${spring.application.name}")
    private String applicationName;

    public OutboxKafkaPublisher(KafkaTemplate<String, byte[]> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * OutboxEvent를 Kafka로 발행한다.
     *
     * <p>발행 순서:
     * 1. ProducerRecord 생성 (topic, key=aggregateId, value=payload)
     * 2. CloudEvents 헤더 추가 (ce_specversion, ce_id, ce_source, ce_type, ce_correlationid)
     * 3. trace context 복원 후 전송 (OTel 활성 시) 또는 직접 전송
     *
     * @throws Exception Kafka 전송 실패 시. 호출자(OutboxPollService)가 재시도/DLQ를 처리한다.
     */
    public void publish(OutboxEvent event) throws Exception {
        ProducerRecord<String, byte[]> record = new ProducerRecord<>(
                event.getTopic()
                , null
                , event.getAggregateId()
                , event.getPayload()
        );

        // CloudEvents 표준 헤더: 수신자가 이벤트 출처와 타입을 식별하는 데 사용
        addCloudEventsHeaders(record, event);

        // Kafka 전송 (OTel 활성 시 trace context 복원)
        sendWithTraceContext(record, event);
    }

    /**
     * ProducerRecord에 CloudEvents 표준 헤더와 correlation ID를 설정한다.
     *
     * <p>CloudEvents spec v1.0 기반. 수신 측에서 이벤트를 라우팅하거나
     * 모니터링할 때 이 헤더들을 참조한다.
     */
    private void addCloudEventsHeaders(ProducerRecord<String, byte[]> record
            , OutboxEvent event) {
        record.headers().add("ce_specversion", CE_SPECVERSION.getBytes(StandardCharsets.UTF_8));
        record.headers().add("ce_id", String.valueOf(event.getId()).getBytes(StandardCharsets.UTF_8));
        record.headers().add("ce_source", ("/" + applicationName).getBytes(StandardCharsets.UTF_8));
        record.headers().add("ce_type", event.getEventType().getBytes(StandardCharsets.UTF_8));

        if (event.getCorrelationId() != null) {
            record.headers().add("ce_correlationid"
                    , event.getCorrelationId().getBytes(StandardCharsets.UTF_8));
        }
    }

    // --- [EXPERIMENTAL: OpenTelemetry] 시작 ---
    // OTel 제거 시: 이 메서드를 삭제하고 publish()에서 kafkaTemplate.send(record).get(5, SECONDS) 직접 호출로 교체
    /**
     * 저장된 traceparent로 OTel trace context를 복원하고 Kafka 메시지를 발행한다.
     *
     * <p>OTel이 classpath에 없으면 trace 복원 없이 바로 발행한다 (graceful degradation).
     * OTel이 있고 traceparent가 유효하면 원래 HTTP 요청 trace의 자식 스팬으로 발행된다.
     * 이를 통해 Grafana Tempo에서 "API 요청 → Outbox 저장 → Kafka 발행" 전체 경로를 추적할 수 있다.
     */
    private void sendWithTraceContext(ProducerRecord<String, byte[]> record
            , OutboxEvent event) throws Exception {

        // OTel이 classpath에 없으면 직접 전송
        if (!TraceContextUtil.isOtelAvailable()) {
            kafkaTemplate.send(record).get(5, TimeUnit.SECONDS);
            return;
        }

        // OTel 활성: 저장된 traceparent로 부모 스팬을 복원하고, 새 스팬 안에서 Kafka 전송
        TraceContextUtil.publishWithTrace(
                event.getTraceParent()
                , "OutboxKafkaPublisher.publish"
                , event.getId()
                , event.getEventType()
                , event.getAggregateId()
                , () -> {
                    // 현재 활성 스팬의 traceparent를 Kafka 헤더에 주입 → 수신자도 동일 trace에 연결
                    String traceparent = TraceContextUtil.captureTraceParent();
                    if (traceparent != null) {
                        record.headers().add("traceparent"
                                , traceparent.getBytes(StandardCharsets.UTF_8));
                    }
                    try {
                        kafkaTemplate.send(record).get(5, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
    }
    // --- [EXPERIMENTAL: OpenTelemetry] 끝 ---
}
