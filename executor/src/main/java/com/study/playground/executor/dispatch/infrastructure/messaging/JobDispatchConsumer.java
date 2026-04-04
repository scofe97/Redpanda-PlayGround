package com.study.playground.executor.dispatch.infrastructure.messaging;

import com.study.playground.avro.executor.ExecutorJobDispatchCommand;
import com.study.playground.executor.dispatch.domain.port.in.ReceiveJobUseCase;
import com.study.playground.kafka.topic.Topics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.TopicSuffixingStrategy;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Component
@RequiredArgsConstructor
@Slf4j
public class JobDispatchConsumer {

    private final ReceiveJobUseCase receiveJobUseCase;

    @RetryableTopic(
            attempts = "4"
            , backoff = @Backoff(delay = 1000, multiplier = 2, maxDelay = 10000)
            , topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE
            , autoCreateTopics = "true"
    )
    @KafkaListener(
            topics = Topics.EXECUTOR_CMD_JOB_DISPATCH
            , groupId = "executor-group"
            , concurrency = "2"
            , containerFactory = "avroListenerFactory"
    )
    public void onJobDispatch(@Payload ExecutorJobDispatchCommand cmd) {
        try {
            receiveJobUseCase.receive(
                    cmd.getJobExcnId()
                    , cmd.getPipelineExcnId()
                    , cmd.getJobId()
                    , LocalDateTime.parse(cmd.getPriorityDt(), DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                    , cmd.getRgtrId()
            );
        } catch (Exception e) {
            log.error("[JobDispatch] Failed: jobExcnId={}, error={}"
                    , cmd.getJobExcnId(), e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    @DltHandler
    public void handleDlt(ExecutorJobDispatchCommand cmd) {
        log.error("[JobDispatch-DLT] Exhausted retries: jobExcnId={}", cmd.getJobExcnId());
    }
}
