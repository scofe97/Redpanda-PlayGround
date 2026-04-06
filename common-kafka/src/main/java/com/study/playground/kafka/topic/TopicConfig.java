package com.study.playground.kafka.topic;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class TopicConfig {

    // --- Executor topics ---

    @Bean
    public NewTopic executorCmdJobDispatchTopic() {
        return TopicBuilder.name(Topics.EXECUTOR_CMD_JOB_DISPATCH)
                .partitions(3)
                .replicas(1)
                .config("retention.ms", String.valueOf(7L * 24 * 60 * 60 * 1000))
                .build();
    }

    @Bean
    public NewTopic executorCmdJobExecuteTopic() {
        return TopicBuilder.name(Topics.EXECUTOR_CMD_JOB_EXECUTE)
                .partitions(3)
                .replicas(1)
                .config("retention.ms", String.valueOf(7L * 24 * 60 * 60 * 1000))
                .build();
    }

    @Bean
    public NewTopic executorEvtJobStartedTopic() {
        return TopicBuilder.name(Topics.EXECUTOR_EVT_JOB_STARTED)
                .partitions(1)
                .replicas(1)
                .config("retention.ms", String.valueOf(7L * 24 * 60 * 60 * 1000))
                .build();
    }

    @Bean
    public NewTopic executorEvtJobCompletedTopic() {
        return TopicBuilder.name(Topics.EXECUTOR_EVT_JOB_COMPLETED)
                .partitions(1)
                .replicas(1)
                .config("retention.ms", String.valueOf(7L * 24 * 60 * 60 * 1000))
                .build();
    }

    @Bean
    public NewTopic executorNotifyJobStartedTopic() {
        return TopicBuilder.name(Topics.EXECUTOR_NOTIFY_JOB_STARTED)
                .partitions(1)
                .replicas(1)
                .config("retention.ms", String.valueOf(7L * 24 * 60 * 60 * 1000))
                .build();
    }

    @Bean
    public NewTopic executorNotifyJobCompletedTopic() {
        return TopicBuilder.name(Topics.EXECUTOR_NOTIFY_JOB_COMPLETED)
                .partitions(1)
                .replicas(1)
                .config("retention.ms", String.valueOf(7L * 24 * 60 * 60 * 1000))
                .build();
    }

    @Bean
    public NewTopic executorDlqJobTopic() {
        return TopicBuilder.name(Topics.EXECUTOR_DLQ_JOB)
                .partitions(1)
                .replicas(1)
                .config("retention.ms", String.valueOf(30L * 24 * 60 * 60 * 1000))
                .build();
    }
}
