# Setup and Usage

이 문서는 데이터 흐름별 설정(로그·트레이스·메트릭), Grafana 사용법, 설정 파일 맵을 다룬다. 아키텍처와 컴포넌트 개념은 [01-architecture-overview.md](./01-architecture-overview.md), OTel 계측 상세는 [03-otel-instrumentation.md](./03-otel-instrumentation.md), 문제가 생기면 [07-troubleshooting.md](./07-troubleshooting.md)을 참조한다.

---

## 1. 데이터 흐름별 설정

### 1-1. 로그 (Docker → Alloy → Loki)

Alloy가 Docker 소켓(`/var/run/docker.sock`)을 통해 `playground-*` 컨테이너의 로그를 자동 수집하여 Loki로 전송한다. 별도 설정 없이 컨테이너가 기동되면 로그가 쌓인다.

설정 파일: `infra/docker/shared/monitoring/alloy-config.alloy` (로컬), `infra/docker/deploy/server-*/monitoring/alloy-config.alloy` (GCP)

주의사항:
- **GitLab 제외**: GitLab은 로그량이 과도하여(~1MB/s) Loki 인제스트 제한을 초과하므로 수집 대상에서 drop했다. GCP에서는 Server 2 Alloy sidecar의 relabel에서 drop한다.
- **Java 멀티라인**: 스택트레이스를 하나의 로그 엔트리로 조인한다. `^\\d{4}-\\d{2}-\\d{2}` 패턴으로 새 로그 라인을 인식한다.
- **Spring Boot 로그**: 호스트에서 실행되므로 Docker 로그 수집 대상이 아니다. GCP 환경에서는 Loki4j Logback Appender로 직접 전송한다(섹션 7 참조). 트레이스/메트릭은 OTLP/Prometheus로 정상 수집된다.
- **Connect 로그 파일 접근 불가**: Connect는 stdout-only 출력이고 파일 로깅을 지원하지 않는다. Loki를 통한 Grafana 검색이 유일한 중앙 로그 접근 방법이다.

### 1-2. 트레이스 (OTel → Alloy → Tempo)

두 경로로 트레이스가 수집된다.

**Spring Boot → Tempo:**
OTel Java Agent가 Spring MVC, Kafka Producer/Consumer, JDBC를 자동 계측한다. `build.gradle`의 `bootRun` 태스크에서 `lib/opentelemetry-javaagent.jar`가 존재하면 자동으로 attach된다.

```
로컬:  Spring Boot → OTLP HTTP (localhost:24318) → Alloy → Tempo
GCP:   Spring Boot → OTLP gRPC (34.22.78.240:4317) → Alloy central (Server 3) → noise filter → Tempo
```

**Redpanda Connect → Tempo:**
`observability.yaml`에 `open_telemetry_collector` tracer가 설정되어 있다. Connect가 메시지를 처리할 때 자동으로 스팬을 생성하며, Kafka 메시지의 `traceparent` 헤더와 연결된다.

```
Connect → OTLP HTTP (alloy:4318) → Alloy → Tempo
```

**노이즈 트레이스 필터링 (2단계 전략):**

반복 실행되는 스케줄링 및 인프라 트레이스는 Tempo 저장소를 낭비하므로 2단계로 제거한다.

1단계 — OTel Agent에서 `OTEL_INSTRUMENTATION_SPRING_SCHEDULING_ENABLED=false`로 `@Scheduled` 계측을 비활성화한다. OutboxPoller(500ms)와 WebhookTimeoutChecker(30s) 메서드의 루트 스팬이 생성되지 않는다. 왜 Alloy가 아닌 Agent에서 비활성화하는가? Alloy의 span-level 필터(`otelcol.processor.filter`)로 루트 스팬만 드롭하면 자식 스팬(JDBC 등)이 남아 Tempo에서 `<root span not yet received>`가 표시된다.

2단계 — Alloy 필터로 남은 노이즈 스팬을 드롭한다. 스케줄링 비활성화 후에도 JDBC 자동 계측이 독립 root 스팬을 생성하기 때문이다. 모두 자식 없는 단독 스팬이므로 orphan 문제 없이 안전하게 드롭된다.

```alloy
otelcol.processor.filter "noise" {
  traces {
    span = [
      "IsMatch(name, \"SELECT playground.outbox_event.*\")",   # Outbox 폴링 JDBC
      "IsMatch(name, \"UPDATE playground.outbox_event.*\")",   # Outbox 상태 업데이트
      "name == \"playground\"",                                # DB 커넥션 체크
      "name == \"GET /actuator/prometheus\"",                  # Prometheus 스크래핑
    ]
  }
}
```

`@Scheduled` 에러 모니터링은 Loki(로그)와 Prometheus(에러 메트릭)로 대체한다.

### 1-3. 메트릭 (Alloy scrape → Prometheus remote_write)

Alloy가 15초 간격으로 4개 타겟을 스크래핑하고, `prometheus.remote_write`로 Prometheus에 전송한다. Prometheus의 `prometheus.yml`은 `scrape_configs: []`로 비어 있으며, `--web.enable-remote-write-receiver` 플래그로 Alloy의 remote_write를 수신한다.

| Alloy 컴포넌트 | 타겟 | 메트릭 경로 |
|----------------|------|------------|
| `prometheus.scrape "spring_boot"` | `host.docker.internal:8080` (로컬만) | `/actuator/prometheus` |
| `prometheus.scrape "redpanda"` | `redpanda:9644` | `/metrics` |
| `prometheus.scrape "connect_command"` | `connect:4195` | `/metrics` |
| `prometheus.scrape "node"` | `<host-ip>:9100` | `/metrics` |

GCP에서는 Server 1 Alloy sidecar가 Redpanda/Connect 메트릭을 스크래핑한 후 Server 3 Prometheus로 remote_write한다. Spring Boot 메트릭은 로컬 전용이다(GCP에서 원격 스크래핑 미설정).

설정 파일: `infra/docker/shared/monitoring/alloy-config.alloy` (로컬), `infra/docker/deploy/server-1/monitoring/alloy-config.alloy` (GCP)

---

## 2. Grafana 사용법

### 2-1. 로그 탐색 (Loki)

1. Grafana > **Explore** (좌측 나침반 아이콘)
2. 상단 데이터소스 → **Loki** 선택
3. 쿼리 예시:

```logql
# 모든 playground 컨테이너 로그
{container=~"playground.*"}

# Connect만
{container="playground-connect"}

# ERROR 레벨만
{container=~"playground.*"} |= "ERROR"

# 특정 execution_id 추적
{container=~"playground.*"} |= "9aacb626-bbbe-467d"
```

### 2-2. 트레이스 탐색 (Tempo)

1. Grafana > **Explore**
2. 상단 데이터소스 → **Tempo** 선택
3. **Search** 탭에서:
   - Service Name: `redpanda-playground` 또는 `redpanda-connect`
   - 시간 범위 조정 (Last 1 hour)
4. 트레이스 클릭 → **워터폴 뷰**로 스팬 타이밍/호출 흐름 확인

TraceQL 쿼리 예시:
```
{resource.service.name = "redpanda-playground"}
{span.http.route = "/api/tickets"}
{duration > 500ms}
```

### 2-3. 메트릭 탐색 (Prometheus)

1. Grafana > **Explore**
2. 상단 데이터소스 → **Prometheus** 선택
3. 쿼리 예시:

```promql
# 모든 타겟 상태
up

# Spring Boot HTTP 요청 수
http_server_requests_seconds_count

# Kafka 컨슈머 레코드 수
kafka_consumer_records_consumed_total

# Redpanda 파티션 수 (리더 기준)
count(vectorized_cluster_partition_leader == 1)
```

### 2-4. 로그 → 트레이스 연결

로그와 트레이스를 연결하는 경로가 2가지 있다.

**Loki → Tempo (Derived Field):** Loki 데이터소스에 Derived Field(`traceId=(\w+)` 정규식)가 설정되어 있어, 로그 본문에서 `traceId=...` 패턴을 발견하면 Tempo로 자동 링크된다. 로그 라인의 traceId 링크를 클릭하면 해당 트레이스로 점프한다.

**Loki Structured Metadata:** Alloy의 `loki.process "extract_trace"` 단계에서 로그 본문의 `traceId=xxx`를 정규식으로 추출하여 Loki Structured Metadata(`trace_id`)에 저장한다. 라벨이 아닌 Structured Metadata를 사용하는 이유는 trace_id가 요청마다 고유하여 라벨로 올리면 스트림 카디널리티가 폭발하기 때문이다. `{container=~"playground.*"} | trace_id != ""`로 trace가 있는 로그만 필터링할 수 있다.

**Tempo → Loki (tracesToLogs):** Tempo 데이터소스에 `tracesToLogs` 설정이 있어 트레이스 상세에서 Logs 탭을 클릭하면 해당 trace_id의 로그를 자동 조회한다. `mappedTags`로 `service.name` → `service_name` 매핑이 설정되어 있어 서비스별 필터링도 동작한다.

---

## 3. 설정 파일 맵

### 로컬 환경

```
infra/docker/
├── local/
│   ├── .env                              # 포트/버전 환경변수
│   ├── docker-compose.yml                # Core (Redpanda, Console, Connect)
│   ├── docker-compose.monitoring.yml     # 모니터링 5개 서비스 정의
│   └── docker-compose.*.yml              # DB, Jenkins, GitLab, Nexus
└── shared/
    ├── connect/
    │   └── observability.yaml            # Connect 전역 관측성 (tracer/logger/metrics)
    └── monitoring/
        ├── alloy-config.alloy            # 단일 수집: 로그 + OTLP 릴레이 + 메트릭 스크래핑
        ├── loki-config.yaml              # Loki filesystem 모드
        ├── tempo-config.yaml             # Tempo filesystem 모드
        ├── prometheus.yml                # scrape_configs 비어 있음 (Alloy가 스크래핑)
        ├── prometheus-rules.yml          # 알림 규칙 (4그룹 9개)
        └── grafana/provisioning/
            ├── datasources/
            │   └── datasources.yaml      # Loki, Tempo, Prometheus 자동 등록
            └── dashboards/
                └── dashboards.yaml       # 대시보드 프로바이더
```

### GCP 환경 (self-contained)

```
infra/docker/deploy/
├── server-1/                             # Redpanda + DB + Connect + Alloy sidecar
│   ├── .env
│   ├── docker-compose.yml
│   ├── connect/                          # Connect 설정 (자체 포함)
│   ├── monitoring/alloy-config.alloy     # Sidecar: 로그 + OTLP 릴레이 + 메트릭
│   └── init-db/
├── server-2/                             # Jenkins + GitLab + Alloy sidecar
│   ├── docker-compose.yml
│   ├── jenkins/
│   └── monitoring/alloy-config.alloy     # Sidecar: Jenkins 로그만
└── server-3/                             # 모니터링 풀 스택
    ├── docker-compose.yml
    └── monitoring/
        ├── alloy-config.alloy            # Central: OTLP 수신 + 노이즈 필터
        ├── loki-config.yaml
        ├── tempo-config.yaml
        ├── prometheus.yml
        ├── prometheus-rules.yml
        └── grafana/provisioning/
```
