package com.study.playground.pipeline.api;

import com.study.playground.pipeline.sse.SseEmitterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 파이프라인 SSE 구독 엔드포인트.
 *
 * REST(PipelineController)와 SSE 채널을 분리한 이유는
 * SSE는 long-lived 연결이라 타임아웃·에러 처리 정책이 일반 REST와 다르기 때문이다.
 * 클라이언트는 파이프라인 시작 직후 이 엔드포인트를 구독하여
 * 스텝 진행 상황을 실시간으로 수신한다.
 */
@RestController
@RequestMapping("/api/tickets/{ticketId}/pipeline")
@RequiredArgsConstructor
public class PipelineSseController {

    private final SseEmitterRegistry sseRegistry;

    /**
     * 특정 티켓의 파이프라인 이벤트 스트림을 구독한다.
     *
     * SseEmitterRegistry에 이미터를 등록하고 반환하면,
     * 이후 Kafka 컨슈머(PipelineSseConsumer)가 이벤트를 수신할 때마다
     * 해당 이미터를 통해 클라이언트로 푸시한다.
     */
    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamEvents(@PathVariable Long ticketId) {
        return sseRegistry.register(ticketId);
    }
}
