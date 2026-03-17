package com.study.playground.pipeline.event;

import com.study.playground.avro.pipeline.JenkinsBuildCommand;
import com.study.playground.common.outbox.EventPublisher;
import com.study.playground.kafka.serialization.AvroSerializer;
import com.study.playground.kafka.topic.Topics;
import com.study.playground.pipeline.domain.PipelineExecution;
import com.study.playground.pipeline.domain.PipelineStep;
import com.study.playground.supporttool.domain.SupportTool;
import com.study.playground.supporttool.domain.ToolCategory;
import com.study.playground.supporttool.mapper.SupportToolMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

/**
 * Jenkins 빌드 명령을 Kafka 커맨드 토픽으로 발행하는 프로듀서.
 *
 * 이벤트(Event)가 "일어난 사실"을 기록하는 것과 달리,
 * 커맨드(Command)는 외부 시스템(Jenkins)에 특정 행동을 요청하는 메시지다.
 * PIPELINE_COMMANDS 토픽을 별도로 사용하는 이유는 커맨드와 이벤트의
 * 컨슈머 그룹·재시도 정책이 다르기 때문이다.
 *
 * <h3>직렬화 방식: JSON (PipelineEventProducer의 Avro와 다름)</h3>
 * Redpanda Connect(Bloblang)가 이 메시지를 소비하여 Jenkins REST API를
 * 직접 호출하는데, Bloblang은 JSON 파싱만 지원하고 Avro 바이너리를
 * 디코딩하지 못한다. 반면 PipelineEventProducer의 이벤트는 Java 컨슈머
 * (PipelineSseConsumer)가 소비하므로 Avro 직렬화를 사용한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PipelineCommandProducer {

    private static final String AGGREGATE_TYPE = "PIPELINE";
    private static final String COMMAND_TYPE = "JENKINS_BUILD_COMMAND";
    private static final String TOPIC = Topics.PIPELINE_CMD_JENKINS;

    private final EventPublisher eventPublisher;
    private final AvroSerializer avroSerializer;
    private final SupportToolMapper supportToolMapper;

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
        // TODO: 멀티 Jenkins 지원 시 toolId를 파라미터로 받아 특정 Jenkins를 선택
        SupportTool jenkins = supportToolMapper.findActiveByCategory(ToolCategory.CI_CD_TOOL);
        if (jenkins == null) {
            throw new RuntimeException("Active Jenkins tool not found in support_tool table");
        }

        var command = JenkinsBuildCommand.newBuilder()
                .setExecutionId(executionId(execution))
                .setTicketId(execution.getTicketId())
                .setStepOrder(step.getStepOrder())
                .setJenkinsUrl(jenkins.getUrl())
                .setJobName(jobName)
                .setUsername(jenkins.getUsername())
                .setCredential(jenkins.getCredential())
                .setParams(Optional.ofNullable(params).map(Map::copyOf).orElseGet(Map::of))
                .build();

        publish(execution, avroSerializer.toJson(command).getBytes(StandardCharsets.UTF_8));

        log.info("Published JenkinsBuildCommand: job={}, executionId={}, stepOrder={}",
                jobName, execution.getId(), step.getStepOrder());
    }

    private void publish(
            PipelineExecution execution
            , byte[] payload) {
        var executionId = executionId(execution);

        // 마지막 인자(executionId)는 Kafka 파티션 키로, 동일 execution의 커맨드가
        // 같은 파티션에 들어가 순서를 보장한다.
        eventPublisher.publish(
                AGGREGATE_TYPE,
                executionId,
                PipelineCommandProducer.COMMAND_TYPE,
                payload,
                TOPIC,
                executionId);
    }

    private String executionId(PipelineExecution execution) {
        return execution.getId().toString();
    }
}
