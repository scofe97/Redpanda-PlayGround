package com.study.playground.pipeline.event;

import com.study.playground.avro.common.PipelineStatus;
import com.study.playground.avro.pipeline.PipelineExecutionCompletedEvent;
import com.study.playground.avro.pipeline.PipelineStepChangedEvent;
import com.study.playground.common.outbox.EventPublisher;
import com.study.playground.kafka.serialization.AvroSerializer;
import com.study.playground.kafka.topic.Topics;
import com.study.playground.pipeline.domain.PipelineExecution;
import com.study.playground.pipeline.domain.PipelineStep;
import com.study.playground.pipeline.domain.StepStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 파이프라인 실행 중 발생하는 도메인 이벤트를 Kafka로 발행하는 프로듀서.
 *
 * PipelineEngine이 스텝 상태 변경·실행 완료 시점에 이 클래스를 호출한다.
 * EventPublisher(Outbox 패턴)를 통해 발행하는 이유는 DB 트랜잭션과 이벤트 발행의
 * 원자성을 보장하기 위해서다. 이 이벤트들은 PIPELINE_EVENTS 토픽으로 전달되어
 * SSE 컨슈머(PipelineSseConsumer)가 클라이언트에게 실시간으로 푸시한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PipelineEventProducer {

    private static final String AGGREGATE_TYPE = "PIPELINE";
    private static final String STEP_CHANGED_EVENT_TYPE = "PIPELINE_STEP_CHANGED";
    private static final String EXECUTION_COMPLETED_EVENT_TYPE = "PIPELINE_EXECUTION_COMPLETED";

    private final EventPublisher eventPublisher;
    private final AvroSerializer avroSerializer;

    /**
     * 파이프라인 스텝의 상태 변경 이벤트를 발행한다.
     *
     * 스텝 로그를 함께 포함시키는 이유는 클라이언트가 별도 조회 없이
     * SSE 이벤트 하나로 현재 상태와 로그를 모두 렌더링할 수 있도록 하기 위해서다.
     */
    public void publishStepChanged(
            PipelineExecution execution,
            PipelineStep step,
            StepStatus status) {
        var executionId = execution.getId().toString();
        var event = PipelineStepChangedEvent.newBuilder()
                .setExecutionId(executionId)
                .setTicketId(execution.getTicketId())
                .setStepName(step.getStepName())
                .setStepType(step.getStepType().name())
                .setStatus(status.name())
                .setLog(step.getLog())
                .build();

        // 파티션 키 = executionId → 동일 실행의 이벤트 순서 보장
        eventPublisher.publish(
                AGGREGATE_TYPE, executionId
                , STEP_CHANGED_EVENT_TYPE
                , avroSerializer.serialize(event)
                , Topics.PIPELINE_EVT_STEP_CHANGED
                , executionId
        );
    }

    /**
     * 파이프라인 전체 실행 완료(성공 또는 실패) 이벤트를 발행한다.
     *
     * durationMs를 포함하는 이유는 클라이언트가 실행 시간을 별도 계산 없이
     * 즉시 표시할 수 있도록 하기 위해서다.
     * errorMessage는 성공 시 null이며, 실패 시 첫 번째 에러 원인을 담는다.
     */
    public void publishExecutionCompleted(
            PipelineExecution execution,
            PipelineStatus status,
            long durationMs,
            String errorMessage) {
        var executionId = execution.getId().toString();
        var event = PipelineExecutionCompletedEvent.newBuilder()
                .setExecutionId(executionId)
                .setTicketId(execution.getTicketId())
                .setStatus(status)
                .setDurationMs(durationMs)
                .setErrorMessage(errorMessage)
                .build();

        // 파티션 키 = executionId → 동일 실행의 이벤트 순서 보장
        eventPublisher.publish(
                AGGREGATE_TYPE, executionId
                , EXECUTION_COMPLETED_EVENT_TYPE
                , avroSerializer.serialize(event)
                , Topics.PIPELINE_EVT_COMPLETED, executionId
        );
    }
}
