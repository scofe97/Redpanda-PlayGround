package com.study.playground.pipeline.event;

import com.study.playground.avro.common.EventMetadata;
import com.study.playground.avro.pipeline.JenkinsBuildCommand;
import com.study.playground.common.outbox.EventPublisher;
import com.study.playground.common.util.AvroSerializer;
import com.study.playground.pipeline.domain.PipelineExecution;
import com.study.playground.pipeline.domain.PipelineStep;
import io.github.springwolf.core.asyncapi.annotations.AsyncOperation;
import io.github.springwolf.core.asyncapi.annotations.AsyncPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class PipelineCommandProducer {

    private static final String TOPIC = "playground.pipeline.commands";
    private final EventPublisher eventPublisher;

    @AsyncPublisher(operation = @AsyncOperation(
            channelName = "playground.pipeline.commands",
            description = "Jenkins 빌드 커맨드(JenkinsBuildCommand)를 Avro JSON 직렬화하여 발행한다. Redpanda Connect가 수신하여 Jenkins API로 전달."
    ))
    public void publishJenkinsBuildCommand(PipelineExecution execution, PipelineStep step,
                                           String jobName, Map<String, String> params) {
        JenkinsBuildCommand command = JenkinsBuildCommand.newBuilder()
                .setMetadata(EventMetadata.newBuilder()
                        .setEventId(java.util.UUID.randomUUID().toString())
                        .setCorrelationId(execution.getId().toString())
                        .setEventType("JENKINS_BUILD_COMMAND")
                        .setTimestamp(Instant.now())
                        .setSource("pipeline-engine")
                        .build())
                .setExecutionId(execution.getId().toString())
                .setTicketId(execution.getTicketId())
                .setStepOrder(step.getStepOrder())
                .setJobName(jobName)
                .setParams(params)
                .build();

        // JSON 직렬화: Connect에서 Bloblang으로 파싱 가능하도록
        byte[] payload = AvroSerializer.toJson(command).getBytes(StandardCharsets.UTF_8);

        eventPublisher.publish("PIPELINE", execution.getId().toString(),
                "JENKINS_BUILD_COMMAND", payload, TOPIC);

        log.info("Published JenkinsBuildCommand: job={}, executionId={}, stepOrder={}",
                jobName, execution.getId(), step.getStepOrder());
    }
}
