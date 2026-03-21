package com.study.playground.kafka.topic;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class TopicConfig {

    @Bean
    public NewTopic pipelineCmdExecutionTopic() {
        return TopicBuilder.name(Topics.PIPELINE_CMD_EXECUTION)
                .partitions(3)
                .replicas(1) // TODO: Production에서는 replicas(3) + min.insync.replicas=2로 변경 필요
                .config("retention.ms", String.valueOf(7L * 24 * 60 * 60 * 1000))
                .build();
    }

    @Bean
    public NewTopic pipelineCmdJenkinsTopic() {
        return TopicBuilder.name(Topics.PIPELINE_CMD_JENKINS)
                .partitions(3)
                .replicas(1) // TODO: Production에서는 replicas(3) + min.insync.replicas=2로 변경 필요
                .config("retention.ms", String.valueOf(7L * 24 * 60 * 60 * 1000))
                .build();
    }

    @Bean
    public NewTopic pipelineEvtStepChangedTopic() {
        return TopicBuilder.name(Topics.PIPELINE_EVT_STEP_CHANGED)
                .partitions(3)
                .replicas(1) // TODO: Production에서는 replicas(3) + min.insync.replicas=2로 변경 필요
                .config("retention.ms", String.valueOf(7L * 24 * 60 * 60 * 1000))
                .build();
    }

    @Bean
    public NewTopic pipelineEvtCompletedTopic() {
        return TopicBuilder.name(Topics.PIPELINE_EVT_COMPLETED)
                .partitions(3)
                .replicas(1) // TODO: Production에서는 replicas(3) + min.insync.replicas=2로 변경 필요
                .config("retention.ms", String.valueOf(7L * 24 * 60 * 60 * 1000))
                .build();
    }

    @Bean
    public NewTopic ticketEventsTopic() {
        return TopicBuilder.name(Topics.TICKET_EVENTS)
                .partitions(3)
                .replicas(1) // TODO: Production에서는 replicas(3) + min.insync.replicas=2로 변경 필요
                .config("retention.ms", String.valueOf(7L * 24 * 60 * 60 * 1000))
                .build();
    }

    @Bean
    public NewTopic webhookInboundTopic() {
        return TopicBuilder.name(Topics.WEBHOOK_INBOUND)
                .partitions(2)
                .replicas(1) // TODO: Production에서는 replicas(3) + min.insync.replicas=2로 변경 필요
                .config("retention.ms", String.valueOf(3L * 24 * 60 * 60 * 1000))
                .build();
    }

    @Bean
    public NewTopic auditEventsTopic() {
        return TopicBuilder.name(Topics.AUDIT_EVENTS)
                .partitions(1)
                .replicas(1) // TODO: Production에서는 replicas(3) + min.insync.replicas=2로 변경 필요
                .config("retention.ms", String.valueOf(30L * 24 * 60 * 60 * 1000))
                .build();
    }

    @Bean
    public NewTopic pipelineEvtDagJobTopic() {
        return TopicBuilder.name(Topics.PIPELINE_EVT_DAG_JOB)
                .partitions(3)
                .replicas(1) // TODO: Production에서는 replicas(3) + min.insync.replicas=2로 변경 필요
                .config("retention.ms", String.valueOf(7L * 24 * 60 * 60 * 1000))
                .build();
    }

    @Bean
    public NewTopic dlqTopic() {
        return TopicBuilder.name(Topics.DLQ)
                .partitions(1)
                .replicas(1) // TODO: Production에서는 replicas(3) + min.insync.replicas=2로 변경 필요
                .config("retention.ms", String.valueOf(30L * 24 * 60 * 60 * 1000))
                .build();
    }
}
