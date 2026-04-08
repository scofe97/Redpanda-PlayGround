package com.study.playground.kafka.outbox.infrastructure.publishing;

import com.study.playground.kafka.outbox.OutboxEvent;
import com.study.playground.kafka.topic.Topics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * 최대 재시도를 초과한 DEAD 이벤트를 DLQ(Dead Letter Queue) 토픽으로 전송하는 인프라 컴포넌트.
 *
 * <p>DLQ 전송은 best-effort 방식이다. 전송 실패해도 이벤트의 DEAD 상태 전환을 막지 않는다.
 * DLQ 토픽({@code playground.dlq})의 메시지에는 원본 토픽, 이벤트 타입, 실패 사유 등
 * 디버깅에 필요한 메타데이터가 헤더로 포함된다.
 *
 * <h3>DLQ 메시지 헤더</h3>
 * <ul>
 *   <li>{@code dlq.original.topic}: 원래 발행하려던 토픽</li>
 *   <li>{@code dlq.original.event.type}: 이벤트 타입</li>
 *   <li>{@code dlq.original.aggregate.type}: aggregate 타입</li>
 *   <li>{@code dlq.failure.reason}: 실패 사유 (현재 "max_retries_exceeded" 고정)</li>
 *   <li>{@code dlq.retry.count}: 시도한 재시도 횟수</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxDlqPublisher {

    private final KafkaTemplate<String, byte[]> kafkaTemplate;

    /**
     * DEAD 상태의 이벤트를 DLQ 토픽으로 best-effort 전송한다.
     *
     * <p>전송 실패해도 예외를 던지지 않는다. DEAD 마킹은 이미 완료된 상태이므로
     * DLQ 전송 실패가 상태 전환에 영향을 주지 않아야 하기 때문이다.
     */
    public void publishToDlq(OutboxEvent event) {
        try {
            var record = new ProducerRecord<String, byte[]>(
                    Topics.DLQ
                    , null
                    , event.getAggregateId()
                    , event.getPayload()
            );

            // 디버깅용 메타데이터 헤더: 운영자가 DLQ 메시지를 보고 원본 컨텍스트를 파악할 수 있게 함
            record.headers().add("dlq.original.topic"
                    , event.getTopic().getBytes(StandardCharsets.UTF_8));
            record.headers().add("dlq.original.event.type"
                    , event.getEventType().getBytes(StandardCharsets.UTF_8));
            record.headers().add("dlq.original.aggregate.type"
                    , event.getAggregateType().getBytes(StandardCharsets.UTF_8));
            record.headers().add("dlq.failure.reason"
                    , "max_retries_exceeded".getBytes(StandardCharsets.UTF_8));
            record.headers().add("dlq.retry.count"
                    , String.valueOf(event.getRetryCount()).getBytes(StandardCharsets.UTF_8));

            kafkaTemplate.send(record).get(5, TimeUnit.SECONDS);
            log.info("Published DEAD outbox event to DLQ: id={}, type={}"
                    , event.getId(), event.getEventType());
        } catch (Exception e) {
            // best-effort: DLQ 전송 실패는 로그만 남기고 무시
            log.error("Failed to publish DEAD event to DLQ: id={}", event.getId(), e);
        }
    }
}
