# OTel Instrumentation

이 문서는 OTel Java Agent 설정, Connect 관측성 설정, Outbox E2E 트레이스 연결, 그리고 향후 개선 방향을 다룬다. 아키텍처 개요는 [01-architecture-overview.md](./01-architecture-overview.md), 데이터 흐름 설정은 [02-setup-and-usage.md](./02-setup-and-usage.md), 문제 발생 시 [07-troubleshooting.md](./07-troubleshooting.md)을 참조한다.

---

## 1. OTel Java Agent 설정

### 1-1. 자동 attach 원리

`app/build.gradle`의 `bootRun` 태스크에서 `lib/opentelemetry-javaagent.jar` 파일 존재 여부를 체크한다. 파일이 있으면 `-javaagent` JVM 인자와 OTLP 환경변수를 자동 설정한다. IntelliJ에서 bootRun을 실행해도 동일하게 동작한다.

```groovy
tasks.named('bootRun') {
    def agentJar = rootProject.file('lib/opentelemetry-javaagent.jar')
    if (agentJar.exists()) {
        jvmArgs "-javaagent:${agentJar.absolutePath}"
        environment 'OTEL_SERVICE_NAME', 'redpanda-playground'
        // ...
    }
}
```

### 1-2. Agent 다운로드

JAR은 `.gitignore`에 추가되어 있으므로, 새 환경에서는 직접 다운로드해야 한다.

```bash
mkdir -p lib
curl -sL https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/latest/download/opentelemetry-javaagent.jar \
  -o lib/opentelemetry-javaagent.jar
```

### 1-3. Agent 비활성화

JAR 파일을 삭제하거나 이름을 바꾸면 Agent 없이 실행된다.

```bash
mv lib/opentelemetry-javaagent.jar lib/opentelemetry-javaagent.jar.bak
```

### 1-4. 자동 계측과 수동 계측의 경계

OTel Java Agent는 프레임워크 경계를 자동으로 계측한다. 하지만 애플리케이션이 의도적으로 만든 비동기 경계는 자동으로 넘지 못한다. 어디까지가 자동이고 어디서부터 수동 코드가 필요한지 구분해야 한다.

**자동 계측 (코드 변경 없이 Agent가 처리):**

| 경계 | 동작 | trace 연결 |
|------|------|-----------|
| HTTP 요청 → 서비스 로직 | Spring MVC 스팬 자동 생성 | 하나의 trace 안에서 연결 |
| 서비스 로직 → JDBC 쿼리 | SQL 스팬 자동 생성 | 부모 스팬의 자식으로 연결 |
| KafkaTemplate.send() | Producer 스팬 생성 + `traceparent` 헤더 삽입 | Kafka 메시지로 전파 |
| @KafkaListener 수신 | Consumer 스팬 생성 + `traceparent` 헤더에서 context 복원 | Producer trace에 연결 |

Agent가 이것들을 자동 처리할 수 있는 이유는, 프레임워크 API 호출 시점에 현재 스레드의 trace context가 살아 있기 때문이다. HTTP 요청 스레드에서 KafkaTemplate.send()를 호출하면 Agent가 현재 context를 읽어 Kafka 헤더에 넣을 수 있다.

**수동 계측이 필요한 경우 (Agent가 자동으로 못 하는 것):**

| 경계 | 왜 자동이 안 되는가 | 해결 |
|------|---------------------|------|
| Outbox 패턴 (DB → 별도 스레드 → Kafka) | DB에 저장 후 다른 스레드에서 폴링하므로 원래 trace context가 소멸 | traceparent를 DB에 함께 저장, 폴링 시 복원 |
| 외부 시스템 콜백 (Jenkins webhook) | Jenkins가 trace context를 전파하지 않음 | 연결 불가 (블랙박스) |
| 커스텀 스레드풀 / CompletableFuture | 스레드 전환 시 context가 전파되지 않을 수 있음 | `Context.current().wrap()` 사용 |

핵심 원리: **trace context는 스레드 로컬에 존재한다.** 같은 스레드 안에서 일어나는 프레임워크 호출은 Agent가 자동 연결한다. 그러나 DB 저장 → 별도 스레드 폴링처럼 스레드 경계를 넘으면서 동시에 프레임워크가 아닌 커스텀 로직으로 연결되는 경우, context를 명시적으로 저장/복원하는 코드가 필요하다.

이 프로젝트에서 수동 계측이 필요한 유일한 지점이 Outbox 패턴이며, `EventPublisher`에서 traceparent를 캡처하고 `OutboxPoller`에서 복원하는 코드로 해결했다 (섹션 3 참조).

**비활성화한 자동 계측:**

- **Spring Scheduling** (`OTEL_INSTRUMENTATION_SPRING_SCHEDULING_ENABLED=false`): OutboxPoller(500ms)와 WebhookTimeoutChecker(30s)가 반복 실행되어 대량의 노이즈 트레이스를 생성하므로 비활성화했다. OutboxPoller는 수동 계측으로 대체했고, WebhookTimeoutChecker 에러는 Loki/Prometheus로 모니터링한다.

---

## 2. Connect 관측성 설정

### 2-1. streams 모드와 observability.yaml

Redpanda Connect는 `streams --chilled` 모드로 실행된다. 이 모드에서 `tracer`, `logger`, `metrics`, `http`는 파이프라인별이 아닌 서비스 전역 설정이다. 따라서 `-o` 플래그로 별도 파일(`observability.yaml`)에 둔다.

```bash
# docker-compose.yml의 command
redpanda-connect streams --chilled \
  -o /etc/connect/observability.yaml \
  /etc/connect/jenkins-command.yaml \
  /etc/connect/jenkins-webhook.yaml \
  /etc/connect/gitlab-webhook.yaml
```

설정 파일: `docker/connect/observability.yaml`

streams 모드에서 tracer 설정을 파이프라인 YAML에 넣으면 `field tracer not recognised` 경고가 발생한다. 반드시 `-o` 플래그 파일에만 넣어야 한다.

### 2-2. Connect 메트릭

Alloy가 Connect의 `/metrics` 엔드포인트를 스크래핑하고 Prometheus에 remote_write로 전송한다. 주요 메트릭:

| 메트릭 | 설명 |
|--------|------|
| `input_received` | 입력 메시지 수 |
| `output_sent` | 출력 메시지 수 |
| `output_error` | 출력 에러 수 |
| `processor_latency_ns` | 프로세서 처리 시간 |

---

## 3. Outbox E2E 트레이스 연결

Outbox 패턴에서 HTTP 요청과 Kafka 발행이 별도 스레드에서 실행되어 trace가 끊기는 문제를 해결했다. `outbox_event` 테이블에 W3C `traceparent`를 저장하고, OutboxPoller가 발행 시 해당 context를 복원한다.

```
[HTTP POST /api/tickets]
  └─ [INSERT outbox_event (traceparent 저장)]
  └─ [OutboxPoller.publish (traceparent 복원)]    ← 같은 trace
      └─ [KafkaTemplate.send]                     ← traceparent 헤더 전파
          └─ [Connect kafka_franz consume]         ← trace 계속
              └─ [HTTP POST Jenkins]
```

구현:
- `EventPublisher.publish()`: 현재 활성 스팬의 traceId+spanId를 W3C traceparent 형식(`00-{traceId}-{spanId}-01`)으로 캡처하여 outbox 이벤트에 저장한다.
- `OutboxPoller.publishWithTraceContext()`: 저장된 traceparent를 `SpanContext.createFromRemoteParent()`로 파싱하고, 해당 context 하위에 `OutboxPoller.publish` 스팬을 생성한 뒤 Kafka 발행을 실행한다. OTel Agent가 KafkaTemplate을 자동 계측하므로 Kafka 메시지에 `traceparent` 헤더가 전파되고, Connect가 이를 이어받는다.
- OTel Agent 없이 실행하면 `Span.current().getSpanContext().isValid()`가 false를 반환하여 traceparent를 저장하지 않는다. 기존 동작과 동일하다.

---

## 4. 향후 개선

### 4-1. Tail-based Sampling

현재는 Alloy의 `otelcol.processor.filter`로 특정 스팬을 이름 기반으로 드롭하는 방식이다. `07_Observability` PoC에서는 OTel Collector의 `tail_sampling` 프로세서로 더 정교한 전략을 구현했다.

```yaml
# 07_Observability PoC의 OTel Collector 설정
processors:
  tail_sampling:
    decision_wait: 5s
    policies:
      - name: error-policy        # 에러 트레이스는 항상 저장
        type: status_code
        status_code: { status_codes: [ERROR] }
      - name: latency-policy      # 500ms 초과는 저장
        type: latency
        latency: { threshold_ms: 500 }
      - name: probabilistic       # 나머지는 10% 샘플링
        type: probabilistic
        probabilistic: { sampling_percentage: 10 }
```

현재 Alloy는 `tail_sampling` 프로세서를 지원하지 않아 적용하지 못했다. 트레이스 볼륨이 증가하면 Alloy 대신 OTel Collector를 도입하여 tail-based sampling을 적용하는 것을 검토한다. 장점은 앱의 SDK sampling 비율을 100%로 유지하면서(전수 수집) Collector에서 중요한 트레이스만 선별 저장할 수 있다는 것이다.

### 4-2. Kafka Observation API vs OTel Agent

Spring Kafka의 `observation-enabled: true` 설정은 Micrometer Observation API를 통해 Kafka Producer/Consumer를 계측한다. 하지만 OTel Java Agent가 이미 Kafka 클라이언트 라이브러리 레벨에서 자동 계측하고 있으므로, 둘을 동시에 활성화하면 중복 스팬이 생성될 수 있다. 현재는 OTel Agent만 사용한다.

### 4-3. Alloy 대안

| 대안 | 장점 | 단점 |
|------|------|------|
| Promtail | Loki 공식 클라이언트, 설정 간단 | 로그만 수집. 트레이스/메트릭은 별도 도구 필요 |
| Fluent Bit | 경량(~5MB), 다양한 output 플러그인 | Loki 플러그인이 커뮤니티 기반, OTLP 릴레이 제한적 |
| OTel Collector | CNCF 표준, tail_sampling 지원 | Docker 로그 수집이 약함(filelog receiver만), 설정 복잡 |
| 직접 조합 (Promtail + OTel Collector + Prometheus) | 각 도구가 전문 영역 담당 | 3개 바이너리 운영, 설정 분산, 메모리 총합 증가 |

Alloy는 "Promtail + OTel Collector + Prometheus Agent"를 하나로 합친 것이다. 단일 호스트 PoC에서는 Alloy가 가장 간결하다.
