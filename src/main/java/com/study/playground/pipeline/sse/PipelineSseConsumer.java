package com.study.playground.pipeline.sse;

import com.study.playground.avro.pipeline.PipelineExecutionCompletedEvent;
import com.study.playground.avro.pipeline.PipelineStepChangedEvent;
import com.study.playground.common.util.AvroSerializer;
import io.github.springwolf.core.asyncapi.annotations.AsyncListener;
import io.github.springwolf.core.asyncapi.annotations.AsyncOperation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Slf4j
@Component
@RequiredArgsConstructor
public class PipelineSseConsumer {

    private final SseEmitterRegistry sseRegistry;

    @AsyncListener(operation = @AsyncOperation(
            channelName = "playground.pipeline.events",
            description = "파이프라인 이벤트를 수신하여 SSE로 클라이언트에 실시간 스텝 상태와 완료 알림을 전송한다."
    ))
    @KafkaListener(topics = "playground.pipeline.events", groupId = "pipeline-sse",
            properties = {"auto.offset.reset=earliest"})
    public void onPipelineEvent(ConsumerRecord<String, byte[]> record) {
        String eventType = extractHeader(record, "eventType");
        if (eventType == null) {
            log.warn("Missing eventType header, skipping SSE event");
            return;
        }

        try {
            switch (eventType) {
                case "PIPELINE_STEP_CHANGED" -> {
                    PipelineStepChangedEvent event = AvroSerializer.deserialize(
                            record.value(), PipelineStepChangedEvent.getClassSchema());
                    String json = AvroSerializer.toJson(event);
                    sseRegistry.send(event.getTicketId(), "status", json);
                }
                case "PIPELINE_EXECUTION_COMPLETED" -> {
                    PipelineExecutionCompletedEvent event = AvroSerializer.deserialize(
                            record.value(), PipelineExecutionCompletedEvent.getClassSchema());
                    String json = AvroSerializer.toJson(event);
                    sseRegistry.send(event.getTicketId(), "completed", json);
                    sseRegistry.complete(event.getTicketId());
                }
                default -> log.debug("Ignoring event type for SSE: {}", eventType);
            }
        } catch (Exception e) {
            log.error("Failed to process SSE event: eventType={}", eventType, e);
        }
    }

    private String extractHeader(ConsumerRecord<String, byte[]> record, String key) {
        Header header = record.headers().lastHeader(key);
        return header != null ? new String(header.value(), StandardCharsets.UTF_8) : null;
    }
}
