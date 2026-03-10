package com.study.playground.pipeline.event;

import com.study.playground.avro.pipeline.PipelineExecutionStartedEvent;
import com.study.playground.common.idempotency.ProcessedEvent;
import com.study.playground.common.idempotency.ProcessedEventMapper;
import com.study.playground.kafka.serialization.AvroSerializer;
import com.study.playground.kafka.topic.Topics;
import com.study.playground.pipeline.domain.PipelineExecution;
import com.study.playground.pipeline.engine.PipelineEngine;
import com.study.playground.pipeline.mapper.PipelineExecutionMapper;
import com.study.playground.pipeline.mapper.PipelineStepMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.DltStrategy;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 파이프라인 커맨드 토픽에서 PIPELINE_EXECUTION_STARTED 이벤트를 수신하여
 * PipelineEngine을 비동기로 구동하는 컨슈머.
 *
 * @RetryableTopic으로 지수 백오프 재시도(최대 4회)를 구성하는 이유는
 * DB 지연이나 일시적 오류로 인한 실패를 자동 복구하기 위해서다.
 * 모든 재시도 후에도 실패하면 DLT(Dead Letter Topic)로 이동하여
 * 운영자가 수동으로 분석·재처리할 수 있도록 한다.
 *
 * 파이프라인 엔진은 Kafka 리스너 스레드가 아닌 전용 스레드풀(pipeline-exec-*)에서
 * 실행한다. 리스너 스레드를 블로킹하면 파티션 폴링이 멈춰 컨슈머 그룹 리밸런싱이
 * 발생할 수 있기 때문이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PipelineEventConsumer {

    private static final String CE_ID_HEADER = "ce_id";
    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger(0);

    /**
     * 파이프라인 전용 스레드풀.
     *
     * 데몬 스레드로 설정하는 이유는 JVM 종료 시 실행 중인 파이프라인이
     * 프로세스 종료를 막지 않도록 하기 위해서다.
     * 풀 크기를 4로 고정한 이유는 실습 환경에서 동시 실행 파이프라인 수를
     * 제한하여 리소스 경쟁을 줄이기 위해서다.
     */
    private static final Executor PIPELINE_EXECUTOR = Executors.newFixedThreadPool(4, runnable -> {
        var thread = new Thread(runnable, "pipeline-exec-" + THREAD_COUNTER.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    });

    private final PipelineEngine pipelineEngine;
    private final PipelineExecutionMapper executionMapper;
    private final PipelineStepMapper stepMapper;
    private final ProcessedEventMapper processedEventMapper;
    private final AvroSerializer avroSerializer;

    /**
     * PIPELINE_EXECUTION_STARTED 이벤트를 수신하여 파이프라인 엔진을 실행한다.
     *
     * 멱등성 보장을 위해 ce_id 헤더(outbox PK)로 중복 검사한다.
     * ce_id는 outbox 테이블의 auto-increment PK이므로 모든 메시지에 대해 고유하다.
     *
     * execution 레코드 조회 실패 시 IllegalStateException을 던지는 이유는
     * @RetryableTopic 예외를 감지하여 재시도 토픽으로 라우팅하도록 하기 위해서다.
     */
    @RetryableTopic(
            attempts = "4",
            backoff = @Backoff(delay = 1000, multiplier = 2.0, maxDelay = 8000),
            dltStrategy = DltStrategy.FAIL_ON_ERROR,
            dltTopicSuffix = "-dlt",
            retryTopicSuffix = "-retry"
    )
    @KafkaListener(topics = Topics.PIPELINE_CMD_EXECUTION, groupId = "pipeline-engine",
            properties = {"auto.offset.reset=earliest"})
    public void onPipelineEvent(ConsumerRecord<String, byte[]> record) {
        PipelineExecutionStartedEvent event = avroSerializer.deserialize(
                record.value(), PipelineExecutionStartedEvent.getClassSchema());
        log.info("Received pipeline event: executionId={}, ticketId={}, steps={}",
                event.getExecutionId(), event.getTicketId(), event.getSteps());

        var executionId = UUID.fromString(event.getExecutionId());
        var execution = Optional.ofNullable(executionMapper.findById(executionId))
                .orElseThrow(() -> new IllegalStateException("Execution not found: " + executionId));
        var eventId = extractHeader(record, CE_ID_HEADER)
                .orElseThrow(() -> new IllegalStateException("Missing ce_id header for execution: " + executionId));

        if (isDuplicateEvent(eventId)) {
            return;
        }

        execution.setSteps(stepMapper.findByExecutionId(executionId));
        runPipelineAsync(execution, executionId);
    }

    /**
     * 재시도를 모두 소진한 메시지를 DLT에서 수신하여 운영자가 확인할 수 있도록 로깅한다.
     *
     * DLT 핸들러에서 예외를 던지지 않는 이유는 DLT 자체도 재시도 루프에 빠지는 것을
     * 막기 위해서다. 알람 연동이 필요하다면 이 메서드에서 처리한다.
     */
    @DltHandler
    public void onPipelineEventDlt(ConsumerRecord<String, byte[]> record) {
        log.error("[DLT] Pipeline command failed after retries: topic={}, key={}, partition={}, offset={}",
                record.topic(), record.key(), record.partition(), record.offset());
    }

    private boolean isDuplicateEvent(String eventId) {
        if (processedEventMapper.existsByEventId(eventId)) {
            log.info("Duplicate event, skipping: eventId={}", eventId);
            return true;
        }

        var processed = new ProcessedEvent();
        processed.setEventId(eventId);
        processedEventMapper.insert(processed);
        return false;
    }

    private void runPipelineAsync(PipelineExecution execution, UUID executionId) {
        CompletableFuture.runAsync(() -> pipelineEngine.execute(execution), PIPELINE_EXECUTOR)
                .whenComplete((unused, throwable) -> {
                    if (throwable != null) {
                        log.error("Pipeline execution failed: executionId={}", executionId, throwable);
                    }
                });
    }

    /**
     * Kafka 레코드 헤더에서 특정 키의 값을 UTF-8 문자열로 추출한다.
     *
     * lastHeader 사용하는 이유는 동일 키가 여러 번 설정된 경우
     * 가장 최근 값이 유효하다는 관례를 따르기 때문이다.
     */
    private Optional<String> extractHeader(ConsumerRecord<String, byte[]> record, String key) {
        return Optional.ofNullable(record.headers().lastHeader(key))
                .map(Header::value)
                .map(value -> new String(value, StandardCharsets.UTF_8));
    }
}
