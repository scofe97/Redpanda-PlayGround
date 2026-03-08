package com.study.playground.kafka.dlq;

import com.study.playground.kafka.topic.Topics;
import io.github.springwolf.core.asyncapi.annotations.AsyncListener;
import io.github.springwolf.core.asyncapi.annotations.AsyncOperation;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

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
        log.error("[DLQ] Dead letter received: topic={}, key={}, size={}, headers={}",
                record.topic(), record.key(),
                record.value() != null ? record.value().length : 0,
                record.headers());
        // 관리자 알림은 콘솔 로그로 대체
    }
}
