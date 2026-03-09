package com.study.playground.pipeline.sse;

import com.study.playground.avro.pipeline.PipelineExecutionCompletedEvent;
import com.study.playground.avro.pipeline.PipelineStepChangedEvent;
import com.study.playground.kafka.serialization.AvroSerializer;
import com.study.playground.kafka.topic.Topics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * 파이프라인 이벤트 토픽을 구독하여 SSE 클라이언트에게 실시간으로 푸시하는 컨슈머.
 *
 * PipelineEngine이 발행하는 이벤트(스텝 변경, 실행 완료)를 Kafka에서 수신하고
 * SseEmitterRegistry를 통해 해당 티켓을 구독 중인 모든 클라이언트로 전달한다.
 * SSE 전용 컨슈머 그룹("pipeline-sse")을 별도로 사용하는 이유는
 * 파이프라인 엔진 컨슈머 그룹("pipeline-engine")과 오프셋을 독립적으로 관리하기 위해서다.
 *
 * 토픽-스키마 1:1 매칭으로 각 토픽이 단일 스키마만 포함하므로
 * record.topic()으로 페이로드 타입을 판별한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PipelineSseConsumer {

    private final SseEmitterRegistry sseRegistry;
    private final AvroSerializer avroSerializer;

    /**
     * 파이프라인 이벤트를 수신하여 토픽에 따라 SSE로 전달한다.
     *
     * 토픽-스키마 1:1 매칭이므로 record.topic()으로 페이로드 타입을 판별한다.
     * PIPELINE_EXECUTION_COMPLETED 수신 시에는 이벤트 전송 후 SSE 연결도 종료하여
     * 클라이언트가 스트림 끝을 인지할 수 있도록 한다.
     */
    @KafkaListener(topics = {Topics.PIPELINE_EVT_STEP_CHANGED, Topics.PIPELINE_EVT_COMPLETED},
            groupId = "pipeline-sse", properties = {"auto.offset.reset=earliest"})
    public void onPipelineEvent(ConsumerRecord<String, byte[]> record) {
        try {
            switch (record.topic()) {
                case Topics.PIPELINE_EVT_STEP_CHANGED -> {
                    PipelineStepChangedEvent event = avroSerializer.deserialize(
                            record.value(), PipelineStepChangedEvent.getClassSchema());
                    String json = avroSerializer.toJson(event);
                    sseRegistry.send(event.getTicketId(), "status", json);
                }
                case Topics.PIPELINE_EVT_COMPLETED -> {
                    PipelineExecutionCompletedEvent event = avroSerializer.deserialize(
                            record.value(), PipelineExecutionCompletedEvent.getClassSchema());
                    String json = avroSerializer.toJson(event);
                    sseRegistry.send(event.getTicketId(), "completed", json);
                    sseRegistry.complete(event.getTicketId());
                }
                default -> log.debug("Ignoring unknown topic for SSE: {}", record.topic());
            }
        } catch (Exception e) {
            log.error("Failed to process SSE event: topic={}", record.topic(), e);
        }
    }
}
