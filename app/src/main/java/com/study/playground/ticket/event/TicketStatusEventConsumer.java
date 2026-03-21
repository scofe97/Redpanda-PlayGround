package com.study.playground.ticket.event;

import com.study.playground.avro.common.AvroPipelineStatus;
import com.study.playground.avro.pipeline.PipelineExecutionCompletedEvent;
import com.study.playground.common.idempotency.ProcessedEvent;
import com.study.playground.common.idempotency.ProcessedEventMapper;
import com.study.playground.kafka.serialization.AvroSerializer;
import com.study.playground.kafka.topic.Topics;
import com.study.playground.ticket.domain.TicketStatus;
import com.study.playground.ticket.mapper.TicketMapper;
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
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;

/**
 * 파이프라인 실행 완료 이벤트를 수신하여 티켓 상태를 업데이트한다.
 * SUCCESS → DEPLOYED, FAILED → FAILED
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TicketStatusEventConsumer {

    private final TicketMapper ticketMapper;
    private final ProcessedEventMapper processedEventMapper;
    private final AvroSerializer avroSerializer;

    @RetryableTopic(
            attempts = "4",
            backoff = @Backoff(delay = 1000, multiplier = 2.0, maxDelay = 8000),
            dltStrategy = DltStrategy.FAIL_ON_ERROR,
            dltTopicSuffix = "-dlt",
            retryTopicSuffix = "-retry"
    )
    @KafkaListener(topics = Topics.PIPELINE_EVT_COMPLETED, groupId = "ticket-status-updater",
            properties = {"auto.offset.reset=earliest"})
    @Transactional
    public void onPipelineEvent(ConsumerRecord<String, byte[]> record) {
        PipelineExecutionCompletedEvent event = avroSerializer.deserialize(
                record.value(), PipelineExecutionCompletedEvent.getClassSchema());

        var eventId = extractHeader(record, "ce_id");
        if (eventId == null) {
            throw new IllegalStateException("Missing ce_id header for ticket status update");
        }

        // 멱등성 체크
        if (processedEventMapper.existsByEventId(eventId)) {
            log.info("Duplicate ticket status update event, skipping: eventId={}", eventId);
            return;
        }
        var processed = new ProcessedEvent();
        processed.setEventId(eventId);
        processedEventMapper.insert(processed);

        Long ticketId = event.getTicketId();
        if (ticketId == null) {
            log.debug("Skipping ticket status update: ticketId is null (DAG pipeline)");
            return;
        }
        AvroPipelineStatus pipelineStatus = event.getStatus();

        TicketStatus newStatus = switch (pipelineStatus) {
            case SUCCESS -> TicketStatus.DEPLOYED;
            case FAILED -> TicketStatus.FAILED;
            default -> null;
        };

        if (newStatus == null) {
            log.warn("Unexpected pipeline status for ticket update: ticketId={}, status={}", ticketId, pipelineStatus);
            return;
        }

        ticketMapper.updateStatus(ticketId, newStatus.name());
        log.info("Ticket status updated: ticketId={}, status={} (pipeline={})",
                ticketId, newStatus, pipelineStatus);
    }

    @DltHandler
    public void onPipelineEventDlt(ConsumerRecord<String, byte[]> record) {
        log.error("[DLT] Ticket status update failed after retries: topic={}, key={}, partition={}, offset={}",
                record.topic(), record.key(), record.partition(), record.offset());
    }

    private String extractHeader(ConsumerRecord<String, byte[]> record, String key) {
        Header header = record.headers().lastHeader(key);
        return header != null ? new String(header.value(), StandardCharsets.UTF_8) : null;
    }
}
