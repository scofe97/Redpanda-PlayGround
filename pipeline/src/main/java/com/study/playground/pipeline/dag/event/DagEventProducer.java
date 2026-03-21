package com.study.playground.pipeline.dag.event;

import com.study.playground.avro.pipeline.DagJobCompletedEvent;
import com.study.playground.avro.pipeline.DagJobDispatchedEvent;
import com.study.playground.kafka.outbox.EventPublisher;
import com.study.playground.kafka.serialization.AvroSerializer;
import com.study.playground.kafka.topic.Topics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * DAG 실행 중 발생하는 도메인 이벤트를 Kafka로 발행하는 프로듀서.
 *
 * DagExecutionCoordinator가 Job 디스패치·완료 시점에 이 클래스를 호출한다.
 * 프론트엔드 DAG 실시간 시각화에서 Job 시작/완료를 표시하는 데 사용한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DagEventProducer {

    private static final String AGGREGATE_TYPE = "PIPELINE";
    private static final String DAG_JOB_DISPATCHED_EVENT_TYPE = "DAG_JOB_DISPATCHED";
    private static final String DAG_JOB_COMPLETED_EVENT_TYPE = "DAG_JOB_COMPLETED";

    private final EventPublisher eventPublisher;
    private final AvroSerializer avroSerializer;

    /**
     * DAG Job 디스패치 이벤트를 발행한다.
     * 프론트엔드 DAG 실시간 시각화에서 Job 시작을 표시하는 데 사용한다.
     */
    public void publishDagJobDispatched(
            String executionId
            , Long jobId
            , String jobName
            , String jobType
            , int jobOrder) {
        var event = DagJobDispatchedEvent.newBuilder()
                .setExecutionId(executionId)
                .setJobId(jobId)
                .setJobName(jobName)
                .setJobType(jobType)
                .setJobOrder(jobOrder)
                .setDispatchedAt(LocalDateTime.now().toString())
                .build();

        eventPublisher.publish(
                AGGREGATE_TYPE, executionId
                , DAG_JOB_DISPATCHED_EVENT_TYPE
                , avroSerializer.serialize(event)
                , Topics.PIPELINE_EVT_DAG_JOB, executionId
        );

        log.info("[DagJobDispatched] executionId={}, jobName={}, jobType={}, jobOrder={}"
                , executionId, jobName, jobType, jobOrder);
    }

    /**
     * DAG Job 완료 이벤트를 발행한다.
     * 프론트엔드 DAG 실시간 시각화에서 Job 결과를 표시하는 데 사용한다.
     */
    public void publishDagJobCompleted(
            String executionId
            , Long jobId
            , String jobName
            , String jobType
            , int jobOrder
            , String status
            , long durationMs
            , int retryCount
            , String logSnippet) {
        var event = DagJobCompletedEvent.newBuilder()
                .setExecutionId(executionId)
                .setJobId(jobId)
                .setJobName(jobName)
                .setJobType(jobType)
                .setJobOrder(jobOrder)
                .setStatus(status)
                .setDurationMs(durationMs)
                .setRetryCount(retryCount)
                .setLogSnippet(logSnippet)
                .build();

        eventPublisher.publish(
                AGGREGATE_TYPE, executionId
                , DAG_JOB_COMPLETED_EVENT_TYPE
                , avroSerializer.serialize(event)
                , Topics.PIPELINE_EVT_DAG_JOB, executionId
        );

        log.info("[StepChanged] executionId={}, jobName={}, status={}, jobType={}, durationMs={}"
                , executionId, jobName, status, jobType, durationMs);
    }
}
