package com.study.playground.executor.execution.infrastructure.messaging;

import com.study.playground.avro.executor.ExecutorJobExecuteCommand;
import com.study.playground.executor.execution.application.JobExecuteService;
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

@Component
@RequiredArgsConstructor
@Slf4j
public class JobExecuteConsumer {

    private final JobExecuteService jobExecuteService;

    @RetryableTopic(
            attempts = "4"
            , backoff = @Backoff(delay = 1000, multiplier = 2, maxDelay = 10000)
            , topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE
            , autoCreateTopics = "true"
            , kafkaTemplate = "avroRetryKafkaTemplate"
    )
    @KafkaListener(
            topics = Topics.EXECUTOR_CMD_JOB_EXECUTE
            , groupId = "${spring.kafka.consumer.group-id:executor-group}"
            , containerFactory = "avroListenerFactory"
    )
    public void onJobExecute(@Payload ExecutorJobExecuteCommand cmd) {
        try {
            jobExecuteService.execute(cmd.getJobExcnId());
        } catch (Exception e) {
            log.error("[JobExecute] Failed: jobExcnId={}, error={}"
                    , cmd.getJobExcnId(), e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    @DltHandler
    public void handleDlt(ExecutorJobExecuteCommand cmd) {
        log.error("[JobExecute-DLT] Exhausted retries: jobExcnId={}", cmd.getJobExcnId());
    }
}
