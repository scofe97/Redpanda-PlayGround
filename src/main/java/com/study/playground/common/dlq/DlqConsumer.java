package com.study.playground.common.dlq;

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
            description = "처리 실패한 메시지(Dead Letter)를 수신하여 에러 로그로 기록한다."
    ))
    @KafkaListener(topics = "playground.dlq", groupId = "dlq-handler")
    public void onDlqMessage(ConsumerRecord<String, byte[]> record) {
        log.error("[DLQ] Dead letter received: topic={}, key={}, value={}",
                record.topic(), record.key(),
                record.value() != null ? new String(record.value()) : "null");
        // 관리자 알림은 콘솔 로그로 대체
    }
}
