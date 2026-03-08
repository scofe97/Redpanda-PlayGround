package com.study.playground.pipeline.event;

import com.study.playground.avro.pipeline.PipelineExecutionCompletedEvent;
import com.study.playground.avro.pipeline.PipelineStepChangedEvent;
import com.study.playground.avro.common.PipelineStatus;
import com.study.playground.common.outbox.EventPublisher;
import com.study.playground.kafka.serialization.AvroSerializer;
import com.study.playground.kafka.topic.Topics;
import com.study.playground.pipeline.domain.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PipelineEventProducer {

    private static final String TOPIC = Topics.PIPELINE_EVENTS;
    private final EventPublisher eventPublisher;

    public void publishStepChanged(PipelineExecution execution, PipelineStep step, StepStatus status) {
        PipelineStepChangedEvent event = PipelineStepChangedEvent.newBuilder()
                .setExecutionId(execution.getId().toString())
                .setTicketId(execution.getTicketId())
                .setStepName(step.getStepName())
                .setStepType(step.getStepType().name())
                .setStatus(status.name())
                .setLog(step.getLog())
                .build();

        eventPublisher.publish("PIPELINE", execution.getId().toString(),
                "PIPELINE_STEP_CHANGED", AvroSerializer.serialize(event), TOPIC,
                execution.getId().toString());
    }

    public void publishExecutionCompleted(PipelineExecution execution, PipelineStatus status,
                                           long durationMs, String errorMessage) {
        PipelineExecutionCompletedEvent event = PipelineExecutionCompletedEvent.newBuilder()
                .setExecutionId(execution.getId().toString())
                .setTicketId(execution.getTicketId())
                .setStatus(status)
                .setDurationMs(durationMs)
                .setErrorMessage(errorMessage)
                .build();

        eventPublisher.publish("PIPELINE", execution.getId().toString(),
                "PIPELINE_EXECUTION_COMPLETED", AvroSerializer.serialize(event), TOPIC,
                execution.getId().toString());
    }
}
