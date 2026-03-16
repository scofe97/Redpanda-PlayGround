# SSE (Server-Sent Events) 실시간 추적

## 1. 개요

파이프라인 이벤트를 클라이언트에 실시간으로 전달할 때 SSE를 선택한 이유는 단방향성과 단순함 때문이다. 파이프라인 진행 상태는 서버에서 클라이언트 방향으로만 흐른다. 클라이언트가 서버에 보낼 데이터는 없으므로 WebSocket의 양방향 채널은 불필요한 복잡도를 추가할 뿐이다.

**WebSocket과의 차이:**

| 항목 | SSE | WebSocket |
|------|-----|-----------|
| 방향 | 서버 → 클라이언트 (단방향) | 양방향 |
| 프로토콜 | HTTP/1.1 위에서 동작 | 별도 업그레이드 핸드셰이크 |
| 프록시/방화벽 | 대부분 투명하게 통과 | 별도 설정 필요한 경우 있음 |
| 재연결 | 브라우저가 자동 처리 | 직접 구현 필요 |
| 서버 구현 | `SseEmitter` (Spring 표준) | `WebSocketHandler` |

**SSE가 적합한 경우:**
- 진행률, 로그 스트리밍, 알림처럼 서버가 일방적으로 push하는 시나리오
- 기존 HTTP 인프라(로드밸런서, 프록시)를 그대로 사용해야 하는 환경
- 클라이언트가 브라우저 표준 `EventSource` API만으로 구현하길 원할 때

---

## 2. 이 프로젝트에서의 적용

### 엔드포인트 — `GET /api/tickets/{id}/pipeline/events`

클라이언트가 이 엔드포인트에 GET 요청을 보내면 서버는 `SseEmitter`를 생성하고 응답으로 반환한다. HTTP 연결은 닫히지 않고 열린 채로 유지되며, 서버는 이 채널을 통해 이벤트를 순차적으로 전송한다.

```java
@GetMapping(value = "/api/tickets/{id}/pipeline/events",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter streamPipelineEvents(@PathVariable String id) {
    SseEmitter emitter = new SseEmitter(180_000L); // 3분 타임아웃
    sseService.register(id, emitter);
    return emitter;
}
```

`SseEmitter`는 Spring MVC가 반환값으로 인식하고 응답 스트림을 열어 둔다. `produces = TEXT_EVENT_STREAM_VALUE`는 Content-Type을 `text/event-stream`으로 설정해 브라우저가 SSE 연결임을 인식하게 한다.

### Kafka Consumer → SSE Push

Redpanda에서 파이프라인 이벤트를 소비하는 Kafka Consumer는 메시지를 받는 즉시 해당 티켓의 `SseEmitter`를 조회해 클라이언트에 push한다.

```java
@KafkaListener(topics = "pipeline-events")
public void consume(PipelineEvent event) {
    SseEmitter emitter = sseService.get(event.ticketId());
    if (emitter != null) {
        try {
            emitter.send(SseEmitter.event()
                .name(event.stage())
                .data(event));
        } catch (IOException e) {
            sseService.remove(event.ticketId());
        }
    }
}
```

Kafka Consumer는 멀티스레드로 동작하므로 `SseEmitterRegistry`는 `ConcurrentHashMap`으로 구현한다. `emitter.send()`가 `IOException`을 던지면 클라이언트 연결이 끊긴 것이므로 즉시 등록을 해제한다. `IllegalStateException`도 catch해야 한다 — emitter가 이미 완료/타임아웃된 상태에서 `send()`를 호출하면 이 예외가 발생하며, 마찬가지로 등록을 해제한다.

### React `usePipelineEvents` 훅

클라이언트는 브라우저 표준 `EventSource` API로 SSE 연결을 맺는다.

```typescript
function usePipelineEvents(ticketId: string) {
  const [events, setEvents] = useState<PipelineEvent[]>([]);

  useEffect(() => {
    const source = new EventSource(`/api/tickets/${ticketId}/pipeline/events`);

    source.onmessage = (e) => {
      setEvents(prev => [...prev, JSON.parse(e.data)]);
    };

    source.onerror = () => {
      // EventSource는 오류 발생 시 자동으로 재연결 시도
      console.warn('SSE 연결 오류, 재연결 중...');
    };

    return () => source.close(); // 컴포넌트 언마운트 시 연결 종료
  }, [ticketId]);

  return events;
}
```

`EventSource`는 연결이 끊기면 브라우저가 자동으로 재연결을 시도한다는 점이 WebSocket과 다른 핵심 장점이다. 별도의 재연결 로직 없이 `onerror` 핸들러에서 로그만 남겨도 브라우저가 알아서 복구한다.

---

## 3. 흐름

```mermaid
flowchart TD
    A([브라우저]) -->|GET /api/tickets/{id}/pipeline/events| B[Spring Controller]
    B -->|SseEmitter 생성 & 등록| C[(SseEmitterRegistry\nConcurrentHashMap)]
    B -->|SseEmitter 반환| A

    D([Redpanda]) -->|pipeline-events 토픽| E[Kafka Consumer]
    E -->|ticketId로 조회| C
    C -->|emitter.send| A

    style A fill:#dbeafe,stroke:#1d4ed8,color:#333
    style B fill:#dcfce7,stroke:#15803d,color:#333
    style C fill:#fef9c3,stroke:#a16207,color:#333
    style D fill:#f3e8ff,stroke:#7e22ce,color:#333
    style E fill:#dcfce7,stroke:#15803d,color:#333
```

흐름의 핵심은 `SseEmitterRegistry`가 연결의 생명주기를 중재한다는 점이다. Controller가 emitter를 등록하고, Consumer가 emitter를 조회해 push하며, 연결 종료 시 두 곳 모두에서 정리를 트리거할 수 있다.

---

## 4. 연결 관리

### 타임아웃

`SseEmitter` 생성자에 밀리초 단위 타임아웃을 지정한다. 타임아웃이 지나면 Spring이 자동으로 연결을 종료하고 `onTimeout` 콜백을 호출한다. 파이프라인이 장시간 실행될 수 있으므로 충분한 여유를 두되, 무한(`-1L`)은 리소스 누수 위험이 있어 피한다.

```java
SseEmitter emitter = new SseEmitter(180_000L);
emitter.onTimeout(() -> sseService.remove(id));
emitter.onCompletion(() -> sseService.remove(id));
emitter.onError(e -> sseService.remove(id));
```

세 콜백 모두 등록 해제를 수행한다. `onCompletion`은 정상 종료, `onTimeout`은 타임아웃, `onError`는 오류 상황에 각각 호출된다.

### 재연결

브라우저의 `EventSource`는 연결이 끊기면 기본 3초 후 자동으로 재연결을 시도한다. 서버는 `retry` 필드로 이 간격을 조절할 수 있다.

```java
emitter.send(SseEmitter.event().reconnectTime(5000));
```

파이프라인 완료 후에는 클라이언트가 더 이상 재연결하지 않도록 완료 이벤트를 전송하고 emitter를 종료한다. `complete()` 호출 전에 반드시 Registry에서 먼저 제거해야 한다. Registry에서 제거해도 emitter 객체 자체는 유효하다 — 제거는 다른 스레드가 이 emitter에 새 메시지를 보내는 것을 방지할 뿐이다. 순서가 반대면 `onCompletion` 콜백과 다른 스레드의 `send()` 사이에 경쟁 조건이 생길 수 있다.

```java
sseService.remove(ticketId);  // 먼저 Registry에서 제거 (다른 스레드의 send 방지)
emitter.send(SseEmitter.event().name("done").data("pipeline-complete"));
emitter.complete();
```

### Emitter 정리

Registry가 비대해지는 것을 방지하기 위해 스케줄러로 주기적으로 만료된 emitter를 정리한다. 실제로는 `onTimeout`/`onCompletion` 콜백으로 대부분 정리되지만, 콜백이 누락된 케이스에 대한 안전망으로 동작한다.

---

## 5. 트레이드오프

**장점:**
- 브라우저 표준 `EventSource` API로 클라이언트 구현이 단순하다. 재연결, 이벤트 파싱이 내장되어 있다.
- 기존 HTTP 인프라를 그대로 사용하므로 로드밸런서나 프록시 설정 변경이 필요 없다.
- Spring `SseEmitter`는 스레드 안전하게 설계되어 있어 Kafka Consumer 스레드에서 직접 `send()`를 호출해도 안전하다.

**단점:**
- 수평 확장 시 문제가 생긴다. 클라이언트가 서버 A에 연결되어 있고, Kafka Consumer가 서버 B에서 이벤트를 소비하면 push가 되지 않는다. 이를 해결하려면 Redis Pub/Sub나 Sticky Session이 필요하다.
- HTTP/1.1 환경에서는 브라우저당 동일 도메인에 최대 6개의 연결 제한이 있다. HTTP/2를 사용하면 다중화로 이 제한이 사라진다.
- 파이프라인이 완료된 후에도 클라이언트가 연결을 유지하면 서버 리소스가 낭비된다. `done` 이벤트를 명시적으로 전송해 클라이언트가 `source.close()`를 호출하도록 유도해야 한다.

> 이 프로젝트는 단일 인스턴스 학습 환경이므로 수평 확장 문제는 현재 범위 밖이다. 프로덕션 적용 시에는 Redis Pub/Sub와 함께 사용하는 것이 표준 패턴이다.

---


