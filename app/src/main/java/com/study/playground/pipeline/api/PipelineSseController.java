package com.study.playground.pipeline.api;

import com.study.playground.pipeline.sse.SseEmitterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/tickets/{ticketId}/pipeline")
@RequiredArgsConstructor
public class PipelineSseController {

    private final SseEmitterRegistry sseRegistry;

    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamEvents(@PathVariable Long ticketId) {
        return sseRegistry.register(ticketId);
    }
}
