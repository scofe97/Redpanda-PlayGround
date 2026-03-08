package com.study.playground.common.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {

    @Bean
    public NewTopic pipelineCommandsTopic() {
        return TopicBuilder.name("playground.pipeline.commands")
                .partitions(3)
                .replicas(1)
                .config("retention.ms", String.valueOf(7L * 24 * 60 * 60 * 1000))
                .build();
    }

    @Bean
    public NewTopic pipelineEventsTopic() {
        return TopicBuilder.name("playground.pipeline.events")
                .partitions(3)
                .replicas(1)
                .config("retention.ms", String.valueOf(7L * 24 * 60 * 60 * 1000))
                .build();
    }

    @Bean
    public NewTopic ticketEventsTopic() {
        return TopicBuilder.name("playground.ticket.events")
                .partitions(3)
                .replicas(1)
                .config("retention.ms", String.valueOf(7L * 24 * 60 * 60 * 1000))
                .build();
    }

    @Bean
    public NewTopic webhookInboundTopic() {
        return TopicBuilder.name("playground.webhook.inbound")
                .partitions(2)
                .replicas(1)
                .config("retention.ms", String.valueOf(3L * 24 * 60 * 60 * 1000))
                .build();
    }

    @Bean
    public NewTopic auditEventsTopic() {
        return TopicBuilder.name("playground.audit.events")
                .partitions(1)
                .replicas(1)
                .config("retention.ms", String.valueOf(30L * 24 * 60 * 60 * 1000))
                .build();
    }

    @Bean
    public NewTopic dlqTopic() {
        return TopicBuilder.name("playground.dlq")
                .partitions(1)
                .replicas(1)
                .config("retention.ms", String.valueOf(30L * 24 * 60 * 60 * 1000))
                .build();
    }
}
