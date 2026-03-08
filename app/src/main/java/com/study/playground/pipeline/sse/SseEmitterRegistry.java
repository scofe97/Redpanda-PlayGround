package com.study.playground.pipeline.sse;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
@Component
public class SseEmitterRegistry {

    private final Map<Long, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public SseEmitter register(Long ticketId) {
        SseEmitter emitter = new SseEmitter(300_000L); // 5분 타임아웃
        emitters.computeIfAbsent(ticketId, k -> new CopyOnWriteArrayList<>()).add(emitter);

        Runnable removeCallback = () -> {
            List<SseEmitter> list = emitters.get(ticketId);
            if (list != null) {
                list.remove(emitter);
                if (list.isEmpty()) {
                    emitters.remove(ticketId);
                }
            }
        };
        emitter.onCompletion(removeCallback);
        emitter.onTimeout(removeCallback);
        emitter.onError(e -> removeCallback.run());

        log.debug("SSE emitter registered for ticketId={}", ticketId);
        return emitter;
    }

    public void send(Long ticketId, String eventName, Object data) {
        List<SseEmitter> list = emitters.get(ticketId);
        if (list == null) return;

        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(data));
            } catch (IllegalStateException e) {
                // Emitter 이미 완료됨 (클라이언트 연결 해제) — 조용히 제거
                log.debug("SSE emitter already completed for ticketId={}, removing", ticketId);
                list.remove(emitter);
            } catch (Exception e) {
                log.warn("Failed to send SSE event for ticketId={}, removing emitter", ticketId);
                list.remove(emitter);
            }
        }
        emitters.computeIfPresent(ticketId, (k, v) -> v.isEmpty() ? null : v);
    }

    public void complete(Long ticketId) {
        List<SseEmitter> list = emitters.get(ticketId);
        if (list != null) {
            for (SseEmitter emitter : list) {
                try {
                    emitter.complete();
                } catch (Exception e) {
                    log.debug("Error completing SSE emitter for ticketId={}", ticketId);
                }
            }
            emitters.remove(ticketId);
        }
    }
}
