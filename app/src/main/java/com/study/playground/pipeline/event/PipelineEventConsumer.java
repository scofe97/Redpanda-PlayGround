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
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
@RequiredArgsConstructor
public class PipelineEventConsumer {

    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger(0);
    private static final Executor PIPELINE_EXECUTOR = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "pipeline-exec-" + THREAD_COUNTER.incrementAndGet());
        t.setDaemon(true);
        return t;
    });

    private final PipelineEngine pipelineEngine;
    private final PipelineExecutionMapper executionMapper;
    private final PipelineStepMapper stepMapper;
    private final ProcessedEventMapper processedEventMapper;

    @RetryableTopic(
            attempts = "4",
            backoff = @Backoff(delay = 1000, multiplier = 2.0, maxDelay = 8000),
            dltStrategy = DltStrategy.FAIL_ON_ERROR,
            dltTopicSuffix = "-dlt",
            retryTopicSuffix = "-retry"
    )
    @KafkaListener(topics = Topics.PIPELINE_COMMANDS, groupId = "pipeline-engine",
            properties = {"auto.offset.reset=earliest"})
    public void onPipelineEvent(ConsumerRecord<String, byte[]> record) {
        String eventType = extractHeader(record, "eventType");
        if (!"PIPELINE_EXECUTION_STARTED".equals(eventType)) {
            return;
        }

        PipelineExecutionStartedEvent event = AvroSerializer.deserialize(
                record.value(), PipelineExecutionStartedEvent.getClassSchema());
        log.info("Received pipeline event: executionId={}, ticketId={}, steps={}",
                event.getExecutionId(), event.getTicketId(), event.getSteps());

        UUID executionId = UUID.fromString(event.getExecutionId());
        PipelineExecution execution = executionMapper.findById(executionId);
        if (execution == null) {
            // 실행 레코드 조회 실패는 일시적 상태일 수 있으므로 재시도로 넘긴다.
            throw new IllegalStateException("Execution not found: " + executionId);
        }

        String correlationId = extractHeader(record, "correlationId");
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = "pipeline-started:" + executionId;
            log.warn("Missing correlationId header. Fallback correlationId={}", correlationId);
        }

        ProcessedEvent processed = new ProcessedEvent();
        processed.setCorrelationId(correlationId);
        processed.setEventType(eventType);
        int affected = processedEventMapper.insert(processed);
        if (affected == 0) {
            log.info("Duplicate event, skipping: correlationId={}", correlationId);
            return;
        }

        execution.setSteps(stepMapper.findByExecutionId(executionId));

        // H3: Kafka 리스너 스레드 블로킹 방지를 위한 비동기 실행
        CompletableFuture.runAsync(() -> {
            try {
                pipelineEngine.execute(execution);
            } catch (Exception e) {
                log.error("Pipeline execution failed: executionId={}", executionId, e);
            }
        }, PIPELINE_EXECUTOR);
    }

    @DltHandler
    public void onPipelineEventDlt(ConsumerRecord<String, byte[]> record) {
        log.error("[DLT] Pipeline command failed after retries: topic={}, key={}, partition={}, offset={}",
                record.topic(), record.key(), record.partition(), record.offset());
    }

    private String extractHeader(ConsumerRecord<String, byte[]> record, String key) {
        Header header = record.headers().lastHeader(key);
        return header != null ? new String(header.value(), StandardCharsets.UTF_8) : null;
    }
}
