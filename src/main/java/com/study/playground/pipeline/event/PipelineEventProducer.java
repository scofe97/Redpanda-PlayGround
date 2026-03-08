package com.study.playground.pipeline.event;

import com.study.playground.avro.common.EventMetadata;
import com.study.playground.avro.pipeline.PipelineExecutionCompletedEvent;
import com.study.playground.avro.pipeline.PipelineStepChangedEvent;
import com.study.playground.avro.common.PipelineStatus;
import com.study.playground.common.outbox.EventPublisher;
import com.study.playground.common.util.AvroSerializer;
import com.study.playground.pipeline.domain.*;
import io.github.springwolf.core.asyncapi.annotations.AsyncOperation;
import io.github.springwolf.core.asyncapi.annotations.AsyncPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PipelineEventProducer {

    private static final String TOPIC = "playground.pipeline.events";
    private final EventPublisher eventPublisher;

    @AsyncPublisher(operation = @AsyncOperation(
            channelName = "playground.pipeline.events",
            description = "파이프라인 스텝 상태 변경 이벤트(PipelineStepChangedEvent)를 Avro 직렬화하여 발행한다."
    ))
    public void publishStepChanged(PipelineExecution execution, PipelineStep step, StepStatus status) {
        EventMetadata metadata = EventMetadata.newBuilder()
                .setEventId(java.util.UUID.randomUUID().toString())
                .setCorrelationId(execution.getId().toString())
                .setEventType("PIPELINE_STEP_CHANGED")
                .setTimestamp(java.time.Instant.now())
                .setSource("pipeline-engine")
                .build();

        PipelineStepChangedEvent event = PipelineStepChangedEvent.newBuilder()
                .setMetadata(metadata)
                .setExecutionId(execution.getId().toString())
                .setTicketId(execution.getTicketId())
                .setStepName(step.getStepName())
                .setStepType(step.getStepType().name())
                .setStatus(status.name())
                .setLog(step.getLog())
                .build();

        eventPublisher.publish("PIPELINE", execution.getId().toString(),
                "PIPELINE_STEP_CHANGED", AvroSerializer.serialize(event), TOPIC);
    }

    @AsyncPublisher(operation = @AsyncOperation(
            channelName = "playground.pipeline.events",
            description = "파이프라인 실행 완료 이벤트(PipelineExecutionCompletedEvent)를 Avro 직렬화하여 발행한다."
    ))
    public void publishExecutionCompleted(PipelineExecution execution, PipelineStatus status,
                                           long durationMs, String errorMessage) {
        EventMetadata metadata = EventMetadata.newBuilder()
                .setEventId(java.util.UUID.randomUUID().toString())
                .setCorrelationId(execution.getId().toString())
                .setEventType("PIPELINE_EXECUTION_COMPLETED")
                .setTimestamp(java.time.Instant.now())
                .setSource("pipeline-engine")
                .build();

        PipelineExecutionCompletedEvent event = PipelineExecutionCompletedEvent.newBuilder()
                .setMetadata(metadata)
                .setExecutionId(execution.getId().toString())
                .setTicketId(execution.getTicketId())
                .setStatus(status)
                .setDurationMs(durationMs)
                .setErrorMessage(errorMessage)
                .build();

        eventPublisher.publish("PIPELINE", execution.getId().toString(),
                "PIPELINE_EXECUTION_COMPLETED", AvroSerializer.serialize(event), TOPIC);
    }
}
