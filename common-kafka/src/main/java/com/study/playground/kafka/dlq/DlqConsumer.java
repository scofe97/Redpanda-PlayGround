package com.study.playground.kafka.dlq;

import com.study.playground.kafka.topic.Topics;
import io.github.springwolf.core.asyncapi.annotations.AsyncListener;
import io.github.springwolf.core.asyncapi.annotations.AsyncOperation;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Slf4j
@Component
public class DlqConsumer {

    @AsyncListener(operation = @AsyncOperation(
            channelName = "playground.dlq",
            description = "처리 실패한 메시지(Dead Letter)를 수신하여 에러 로그로 기록한다. 다양한 도메인의 실패 메시지가 혼합되어 수신되므로 페이로드 타입이 특정되지 않는다.",
            payloadType = byte[].class
    ))
    @KafkaListener(topics = Topics.DLQ, groupId = "dlq-handler")
    public void onDlqMessage(ConsumerRecord<String, byte[]> record) {
        var originalTopic = headerValue(record, "dlq.original.topic");
        var eventType = headerValue(record, "dlq.original.event.type");
        var reason = headerValue(record, "dlq.failure.reason");

        log.error("[DLQ] Dead letter received: topic={}, originalTopic={}, eventType={}, reason={}, key={}, size={}"
                , record.topic(), originalTopic, eventType, reason
                , record.key()
                , record.value() != null ? record.value().length : 0);
    }

    private String headerValue(ConsumerRecord<?, ?> record, String key) {
        var header = record.headers().lastHeader(key);
        return header != null ? new String(header.value(), StandardCharsets.UTF_8) : "unknown";
    }
}
