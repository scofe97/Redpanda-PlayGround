package com.study.playground.pipeline.sse;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 티켓별 SSE 이미터를 관리하는 레지스트리.
 *
 * ConcurrentHashMap을 사용하는 이유는 다수의 클라이언트가 동시에 구독·해제할 수 있어
 * 티켓 키 단위의 동시 접근이 빈번하기 때문이다.
 * 티켓당 이미터 목록은 CopyOnWriteArrayList로 관리하는데,
 * 이미터 순회 중 remove가 발생해도 ConcurrentModificationException이 없기 때문이다.
 * 완료·타임아웃·에러 시 onCompletion/onTimeout/onError 콜백으로 이미터를 자동 제거하여
 * 메모리 누수를 방지한다.
 */
@Slf4j
@Component
public class SseEmitterRegistry {

    private final Map<Long, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

    /**
     * 티켓에 대한 SSE 이미터를 등록하고 반환한다.
     *
     * 타임아웃을 5분으로 설정하는 이유는 파이프라인 최대 실행 시간을
     * 커버하면서도 좀비 연결이 무한정 유지되는 것을 막기 위해서다.
     * 연결 종료 시 removeCallback이 호출되어 이미터가 목록에서 제거되고,
     * 목록이 비면 티켓 키도 맵에서 제거된다.
     */
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

    /**
     * 특정 티켓을 구독 중인 모든 클라이언트에게 이벤트를 전송한다.
     *
     * 전송 실패한 이미터(이미 연결 해제된 클라이언트)는 조용히 제거한다.
     * 모든 이미터가 제거되면 computeIfPresent로 빈 목록의 키도 맵에서 제거하여
     * 불필요한 메모리를 즉시 해제한다.
     */
    public void send(
            Long ticketId,
            String eventName,
            Object data) {
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

    /**
     * 파이프라인 완료 시 해당 티켓의 모든 SSE 연결을 정상 종료한다.
     *
     * complete()를 명시적으로 호출하면 클라이언트가 스트림 종료를 인지하고
     * 불필요한 재연결 시도를 하지 않는다.
     */
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
