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

/**
 * Jenkins 빌드 명령을 Kafka 커맨드 토픽으로 발행하는 프로듀서.
 *
 * 이벤트(Event)가 "일어난 사실"을 기록하는 것과 달리,
 * 커맨드(Command)는 외부 시스템(Jenkins)에 특정 행동을 요청하는 메시지다.
 * PIPELINE_COMMANDS 토픽을 별도로 사용하는 이유는 커맨드와 이벤트의
 * 컨슈머 그룹·재시도 정책이 다르기 때문이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PipelineCommandProducer {

    private static final String TOPIC = Topics.PIPELINE_CMD_JENKINS;
    private final EventPublisher eventPublisher;
    private final AvroSerializer avroSerializer;

    /**
     * Jenkins 빌드 트리거 커맨드를 발행한다.
     *
     * 페이로드를 Avro 바이너리가 아닌 JSON 문자열로 직렬화하는 이유는
     * Redpanda Connect(Bloblang)가 바이너리 Avro를 파싱하지 않고
     * JSON으로 Jenkins REST API를 직접 호출할 수 있도록 하기 위해서다.
     */
    public void publishJenkinsBuildCommand(
            PipelineExecution execution,
            PipelineStep step,
            String jobName,
            Map<String, String> params) {
        JenkinsBuildCommand command = JenkinsBuildCommand.newBuilder()
                .setExecutionId(execution.getId().toString())
                .setTicketId(execution.getTicketId())
                .setStepOrder(step.getStepOrder())
                .setJobName(jobName)
                .setParams(params)
                .build();

        // JSON 직렬화: Connect에서 Bloblang으로 파싱 가능하도록
        byte[] payload = avroSerializer.toJson(command).getBytes(StandardCharsets.UTF_8);

        eventPublisher.publish(
                "PIPELINE",
                execution.getId().toString(),
                "JENKINS_BUILD_COMMAND",
                payload,
                TOPIC,
                execution.getId().toString());

        log.info("Published JenkinsBuildCommand: job={}, executionId={}, stepOrder={}",
                jobName, execution.getId(), step.getStepOrder());
    }
}
