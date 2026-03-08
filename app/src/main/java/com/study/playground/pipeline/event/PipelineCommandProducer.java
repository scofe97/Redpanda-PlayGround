package com.study.playground.pipeline.event;

import com.study.playground.avro.pipeline.JenkinsBuildCommand;
import com.study.playground.common.outbox.EventPublisher;
import com.study.playground.kafka.serialization.AvroSerializer;
import com.study.playground.kafka.topic.Topics;
import com.study.playground.pipeline.domain.PipelineExecution;
import com.study.playground.pipeline.domain.PipelineStep;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class PipelineCommandProducer {

    private static final String TOPIC = Topics.PIPELINE_COMMANDS;
    private final EventPublisher eventPublisher;

    public void publishJenkinsBuildCommand(PipelineExecution execution, PipelineStep step,
                                           String jobName, Map<String, String> params) {
        JenkinsBuildCommand command = JenkinsBuildCommand.newBuilder()
                .setExecutionId(execution.getId().toString())
                .setTicketId(execution.getTicketId())
                .setStepOrder(step.getStepOrder())
                .setJobName(jobName)
                .setParams(params)
                .build();

        // JSON 직렬화: Connect에서 Bloblang으로 파싱 가능하도록
        byte[] payload = AvroSerializer.toJson(command).getBytes(StandardCharsets.UTF_8);

        eventPublisher.publish("PIPELINE", execution.getId().toString(),
                "JENKINS_BUILD_COMMAND", payload, TOPIC,
                execution.getId().toString());

        log.info("Published JenkinsBuildCommand: job={}, executionId={}, stepOrder={}",
                jobName, execution.getId(), step.getStepOrder());
    }
}
