# 대시보드와 알림 설계

이 문서는 Redpanda Playground의 Grafana 대시보드 구성과 Prometheus 알림 규칙 설계를 정리한다. 현재 프로젝트에는 대시보드와 알림이 없는 상태에서 시작하여, 운영에 필요한 최소한의 가시성을 확보하는 것이 목표다. 아키텍처 전반은 [monitoring-guide.md](../monitoring-guide.md)를, 장애 대응은 [04-failure-scenarios.md](./04-failure-scenarios.md)를 참조한다.

---

## 1. 현재 상태와 목표

### 1-1. 현재 갭

| 항목 | 현재 | 목표 |
|------|------|------|
| Grafana 대시보드 | 0개 (Explore만 사용) | 4개 (컴포넌트별) |
| Prometheus 알림 규칙 | 0개 | 8개 핵심 알림 |
| SLO 정의 | 없음 | 3개 (가용성, 지연, lag) |
| 대시보드 프로비저닝 | 수동 | 파일 기반 자동 로드 |

현재 Grafana Explore를 통해 임시 쿼리로 상태를 확인할 수 있지만, 반복적인 진단마다 쿼리를 직접 입력해야 한다. 대시보드를 프로비저닝하면 재시작 후에도 설정이 유지되고, 팀원이 동일한 뷰를 볼 수 있다.

### 1-2. 대시보드 카탈로그

| 대시보드 | 파일명 | 주요 패널 |
|----------|--------|---------|
| Redpanda Overview | `redpanda-overview.json` | 브로커 상태, 파티션 수, throughput, consumer lag, 토픽별 메시지 수 |
| Connect Pipelines | `connect-pipelines.json` | input rate, output rate, 에러 rate, DLQ rate, 처리 지연 |
| Spring Boot App | `spring-boot-app.json` | HTTP RED (요청률/오류율/지연), JVM heap, GC pause, Kafka throughput, connection pool |
| System Health | `system-health.json` | Alloy 파이프라인, Loki ingestion, Tempo traces, Prometheus TSDB, 컨테이너 메모리 |

---

## 2. 대시보드 상세 설계

### 2-1. Redpanda Overview

Redpanda 브로커의 전체 상태를 한 화면에서 파악한다. consumer lag이 가장 중요한 지표다. lag이 쌓이면 Connect 파이프라인이 메시지를 소화하지 못하고 있다는 신호이기 때문이다.

#### 패널 구성

**브로커 상태 (Stat 패널)**
```promql
up{job="redpanda"}
```
- 1이면 정상, 0이면 다운. 임계값: 0 → 빨간색

**파티션 수 (Stat 패널)**
```promql
sum(redpanda_kafka_partitions)
```

**초당 처리량 — Bytes In (Time Series)**
```promql
rate(redpanda_kafka_request_bytes_total{request="produce"}[1m])
```

**초당 처리량 — Bytes Out (Time Series)**
```promql
rate(redpanda_kafka_request_bytes_total{request="fetch"}[1m])
```

**Consumer Lag (Time Series — 가장 중요)**
```promql
sum by (group, topic) (kafka_consumer_group_lag)
```
- 임계값: 1000 초과 시 주황색, 5000 초과 시 빨간색

**토픽별 메시지 수 (Bar Gauge)**
```promql
sum by (topic) (redpanda_kafka_topic_partition_committed_offset)
```

**Consumer Group 상태 테이블**
```promql
kafka_consumer_group_lag
```
- 컬럼: group, topic, partition, lag값

#### 대시보드 변수
- `interval`: `1m`, `5m`, `15m` (집계 시간 선택)
- `group`: consumer group 선택 (multi-value)

---

### 2-2. Connect Pipelines

Redpanda Connect의 파이프라인별 처리 현황을 보여준다. DLQ로 가는 메시지가 있으면 파이프라인에 문제가 생긴 것이므로 즉시 확인해야 한다.

#### 패널 구성

**Connect 서비스 상태 (Stat 패널)**
```promql
up{job=~"connect_.*"}
```

**파이프라인별 Input Rate (Time Series)**
```promql
rate(input_received[1m])
```

**파이프라인별 Output Rate (Time Series)**
```promql
rate(output_sent[1m])
```

**에러 Rate (Time Series — 중요)**
```promql
rate(output_error[1m])
```
- 0보다 크면 주황색, 0.1 초과 시 빨간색

**DLQ 전송 Rate (Time Series)**
```promql
# DLQ 토픽으로 가는 output이 있는 경우
rate(output_sent{label="dlq"}[1m])
```

**처리 지연 — Batch Duration (Heatmap)**
```promql
rate(processor_latency_ns_bucket[5m])
```

**Input/Output 비율 (Stat 패널)**
```promql
# 처리율: output / input (1.0이면 100% 처리)
sum(rate(output_sent[5m])) / sum(rate(input_received[5m]))
```

#### 대시보드 변수
- `pipeline`: 파이프라인 이름 선택 (jenkins-command, jenkins-webhook 등)

---

### 2-3. Spring Boot App

Spring Boot 애플리케이션의 RED 메트릭(Rate, Errors, Duration)과 JVM 상태를 보여준다. Spring Boot는 호스트에서 실행되므로 컨테이너 메트릭은 없고, Micrometer가 노출하는 JVM/HTTP 메트릭을 사용한다.

#### HTTP — RED 패턴

**요청률 (Time Series)**
```promql
sum(rate(http_server_requests_seconds_count[1m])) by (uri, method)
```

**에러율 (Time Series)**
```promql
sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m]))
/ sum(rate(http_server_requests_seconds_count[5m]))
```
- 5% 초과 시 주황색, 10% 초과 시 빨간색

**p50 / p95 / p99 지연 (Time Series)**
```promql
histogram_quantile(0.50, sum(rate(http_server_requests_seconds_bucket[5m])) by (le))
histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket[5m])) by (le))
histogram_quantile(0.99, sum(rate(http_server_requests_seconds_bucket[5m])) by (le))
```
- p99 > 2s 시 주황색

#### JVM 패널

**Heap 사용량 (Time Series)**
```promql
# Used
sum(jvm_memory_used_bytes{area="heap"})

# Max
sum(jvm_memory_max_bytes{area="heap"})
```

**Heap 사용률 (Gauge)**
```promql
sum(jvm_memory_used_bytes{area="heap"}) / sum(jvm_memory_max_bytes{area="heap"}) * 100
```
- 80% 초과 시 주황색, 90% 초과 시 빨간색

**GC Pause Duration (Time Series)**
```promql
rate(jvm_gc_pause_seconds_sum[1m])
```

**스레드 수 (Stat 패널)**
```promql
jvm_threads_live_threads
```

#### Kafka 클라이언트 패널

**Producer 전송률 (Time Series)**
```promql
rate(kafka_producer_record_send_total[1m])
```

**Producer 에러율 (Time Series)**
```promql
rate(kafka_producer_record_error_total[1m])
```

**HikariCP 커넥션 풀 (Time Series)**
```promql
# 활성 커넥션
hikaricp_connections_active

# 대기 중인 커넥션 요청
hikaricp_connections_pending
```
- pending > 0 이 지속되면 풀 고갈 위험

**Outbox 처리 현황 (Stat 패널)**
```promql
# Outbox 처리 성공률 (커스텀 메트릭이 있는 경우)
rate(outbox_events_published_total[5m])
```

---

### 2-4. System Health

모니터링 스택 자체의 상태를 확인한다. 모니터링이 제대로 동작해야 다른 대시보드도 의미 있기 때문에, 이 대시보드가 정상이어야 나머지를 신뢰할 수 있다.

#### Alloy 패널

**Alloy 파이프라인 상태 (Stat 패널)**
```promql
up{job="alloy"}
```

**로그 수집률 (Time Series)**
```promql
rate(loki_source_docker_target_entries_total[1m])
```

**OTLP 수신률 (Time Series)**
```promql
rate(otelcol_receiver_accepted_spans_total[1m])
```

**메트릭 스크래핑 성공률 (Time Series)**
```promql
rate(prometheus_remote_storage_samples_in_total[1m])
```

#### Loki 패널

**Loki 수집률 (Time Series)**
```promql
rate(loki_distributor_lines_received_total[1m])
```

**Loki WAL 크기 (Stat 패널)**
```promql
loki_ingester_wal_disk_full
```

#### Tempo 패널

**Tempo 수신 트레이스 (Time Series)**
```promql
rate(tempo_distributor_spans_received_total[1m])
```

**Tempo 메모리 사용량 (Gauge — 중요)**
```promql
container_memory_usage_bytes{name="playground-tempo"}
```
- 800MB 초과 시 주황색, 950MB 초과 시 빨간색 (limit 1GB)

**Tempo WAL 크기 (Time Series)**
```promql
tempo_ingester_live_traces
```

#### Prometheus 패널

**TSDB 크기 (Stat 패널)**
```promql
prometheus_tsdb_storage_blocks_bytes
```

**스크래핑 대상 수 (Stat 패널)**
```promql
count(up)
```

**스크래핑 실패 수 (Stat 패널)**
```promql
count(up == 0)
```

#### 컨테이너 메모리 (Bar Gauge)
```promql
container_memory_usage_bytes{name=~"playground-.*"}
```
- 컨테이너별 메모리 사용량을 한눈에 비교

---

## 3. 알림 규칙 설계

### 3-1. 알림 규칙 목록

| 카테고리 | 알림 이름 | PromQL | 심각도 | for | 설명 |
|----------|-----------|--------|--------|-----|------|
| 가용성 | `RedpandaDown` | `up{job="redpanda"} == 0` | critical | 1m | Redpanda 브로커 다운 |
| 가용성 | `ConnectDown` | `up{job=~"connect_.*"} == 0` | critical | 1m | Redpanda Connect 다운 |
| 가용성 | `SpringBootDown` | `up{job="spring_boot"} == 0` | warning | 2m | Spring Boot 앱 다운 |
| 성능 | `HighConsumerLag` | `sum(kafka_consumer_group_lag) > 1000` | warning | 5m | Consumer lag 과다 |
| 성능 | `HighHTTPLatency` | `histogram_quantile(0.99, rate(http_server_requests_seconds_bucket[5m])) > 2` | warning | 5m | API p99 지연 2s 초과 |
| 성능 | `HighErrorRate` | `sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m])) / sum(rate(http_server_requests_seconds_count[5m])) > 0.05` | critical | 5m | API 에러율 5% 초과 |
| 자원 | `TempoHighMemory` | `container_memory_usage_bytes{name="playground-tempo"} > 838860800` | warning | 5m | Tempo 메모리 800MB 초과 |
| 비즈니스 | `DLQAccumulating` | `rate(output_error[5m]) > 0` | warning | 10m | Connect DLQ 누적 중 |

### 3-2. 알림 규칙 파일

Prometheus 알림 규칙은 `monitoring/prometheus-rules.yml`에 정의하고, `prometheus.yml`의 `rule_files`에서 로드한다. docker-compose에서 `/etc/prometheus/rules/alerts.yml`로 마운트된다.

**`monitoring/prometheus-rules.yml`:**
```yaml
groups:
  - name: redpanda-playground.availability
    rules:
      - alert: RedpandaDown
        expr: up{job="redpanda"} == 0
        for: 1m
        labels:
          severity: critical
        annotations:
          summary: "Redpanda 브로커가 다운됐다"
          description: "{{ $labels.instance }} 브로커가 1분 이상 응답하지 않는다. 전체 메시징이 중단된 상태다."
          runbook: "https://github.com/your-repo/docs/guide/monitoring/04-failure-scenarios.md#4-1-redpanda-브로커-다운"

      - alert: ConnectDown
        expr: up{job=~"connect_.*"} == 0
        for: 1m
        labels:
          severity: critical
        annotations:
          summary: "Redpanda Connect가 다운됐다"
          description: "{{ $labels.job }} 파이프라인이 1분 이상 응답하지 않는다. Jenkins 연동이 중단된 상태다."

      - alert: SpringBootDown
        expr: up{job="spring_boot"} == 0
        for: 2m
        labels:
          severity: warning
        annotations:
          summary: "Spring Boot 애플리케이션이 다운됐다"
          description: "API 서버가 2분 이상 응답하지 않는다. 호스트에서 직접 재시작이 필요하다."

  - name: redpanda-playground.performance
    rules:
      - alert: HighConsumerLag
        expr: sum(kafka_consumer_group_lag) > 1000
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "Consumer lag이 1000을 넘었다"
          description: "현재 lag: {{ $value }}. Connect 파이프라인이 메시지를 충분히 빠르게 소화하지 못하고 있다."

      - alert: HighHTTPLatency
        expr: histogram_quantile(0.99, rate(http_server_requests_seconds_bucket[5m])) > 2
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "API p99 지연이 2초를 넘었다"
          description: "현재 p99: {{ $value }}s. DB 또는 Kafka 연결 지연을 확인해야 한다."

      - alert: HighErrorRate
        expr: >
          sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m]))
          / sum(rate(http_server_requests_seconds_count[5m])) > 0.05
        for: 5m
        labels:
          severity: critical
        annotations:
          summary: "API 에러율이 5%를 넘었다"
          description: "현재 에러율: {{ $value | humanizePercentage }}. Spring Boot 로그를 즉시 확인해야 한다."

  - name: redpanda-playground.resources
    rules:
      - alert: TempoHighMemory
        expr: container_memory_usage_bytes{name="playground-tempo"} > 838860800
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "Tempo 메모리 사용량이 800MB를 넘었다"
          description: "현재: {{ $value | humanize1024 }}B. limit이 1GB이므로 OOM 위험이 있다. 자원 측정 가이드를 참조한다."
          runbook: "https://github.com/your-repo/docs/guide/monitoring/06-tempo-resource-measurement.md"

  - name: redpanda-playground.business
    rules:
      - alert: DLQAccumulating
        expr: rate(output_error[5m]) > 0
        for: 10m
        labels:
          severity: warning
        annotations:
          summary: "Connect DLQ에 메시지가 쌓이고 있다"
          description: "{{ $labels.job }} 파이프라인에서 에러가 10분 이상 발생 중이다. DLQ 토픽을 확인해야 한다."
```

**`monitoring/prometheus.yml`에 추가:**
```yaml
rule_files:
  - /etc/prometheus/rules/*.yml
```

### 3-3. 알림 수신 (Alertmanager)

현재 프로젝트에는 Alertmanager가 없다. PoC 수준에서는 Grafana 자체 알림(Grafana Alerting)을 사용하면 Alertmanager 없이도 알림을 받을 수 있다.

**Grafana Alerting 설정 방법:**
1. Grafana → Alerting → Contact points → New contact point
2. Slack, Email, PagerDuty 중 선택
3. Notification policies에서 severity 기반 라우팅 설정

Grafana Alerting은 Prometheus 알림 규칙 파일을 읽어 동일한 규칙을 평가할 수 있다.

> AlertManager의 개념, 라우팅/억제 설계, Docker Compose 설정 예시는 [08-alertmanager-guide.md](./08-alertmanager-guide.md)를 참조한다.

---

## 4. SLO 정의

SLO(Service Level Objective)는 서비스 품질 목표를 수치로 정의한다. PoC 수준이지만 목표 수치가 있어야 알림 임계값과 대시보드 색상 기준을 일관성 있게 설정할 수 있다.

| SLO | 목표 | 측정 방법 | 알림 임계값 |
|-----|------|----------|-----------|
| API 가용성 | 99% (월간) | `up{job="spring_boot"}` | 0이면 즉시 알림 |
| API 지연 (p99) | < 2초 | `histogram_quantile(0.99, ...)` | 2초 초과 5분 → 알림 |
| Consumer Lag | < 1000 | `kafka_consumer_group_lag` | 1000 초과 5분 → 알림 |

**SLO 달성률 계산 PromQL:**
```promql
# API 가용성 (지난 30일 기준 - recording rule 권장)
avg_over_time(up{job="spring_boot"}[30d]) * 100

# p99 SLO 준수율 (지난 1시간 중 2초 이하인 비율)
sum(rate(http_server_requests_seconds_bucket{le="2"}[1h]))
/ sum(rate(http_server_requests_seconds_count[1h])) * 100
```

---

## 5. 대시보드 프로비저닝

Grafana는 파일 기반 프로비저닝을 지원한다. `monitoring/grafana/provisioning/` 디렉토리에 JSON 파일을 두면 Grafana 재시작 시 자동으로 로드된다. 데이터소스는 이미 프로비저닝되어 있으므로 대시보드 설정만 추가하면 된다.

### 5-1. 디렉토리 구조

```
monitoring/
├── prometheus-rules.yml                    # 알림 규칙 (4그룹 9개)
├── grafana/provisioning/
│   ├── datasources/
│   │   └── datasources.yaml               # Loki, Tempo, Prometheus (고정 UID)
│   └── dashboards/
│       ├── dashboards.yaml                 # 대시보드 프로비저닝 설정
│       └── json/                           # 대시보드 JSON (별도 디렉토리)
│           ├── redpanda-overview.json
│           ├── connect-pipelines.json
│           ├── spring-boot-app.json
│           └── system-health.json
```

### 5-2. 프로비저닝 설정 파일

**`monitoring/grafana/provisioning/dashboards/dashboards.yaml`:**
```yaml
apiVersion: 1

providers:
  - name: 'redpanda-playground'
    orgId: 1
    folder: 'Redpanda Playground'
    folderUid: 'redpanda-playground'
    type: file
    disableDeletion: false
    updateIntervalSeconds: 30
    allowUiUpdates: true
    options:
      path: /etc/grafana/provisioning/dashboards
      foldersFromFilesStructure: false
```

`allowUiUpdates: true`로 설정하면 Grafana UI에서 대시보드를 수정할 수 있다. false로 하면 파일이 유일한 진실의 원천이 되어 UI 변경이 차단된다. 개발 중에는 true가 편리하다.

### 5-3. docker-compose.monitoring.yml에 마운트 추가

```yaml
grafana:
  volumes:
    - ./monitoring/grafana/provisioning:/etc/grafana/provisioning
    # 기존 datasources도 같은 경로로 마운트되어 있어야 함
```

### 5-4. 대시보드 JSON 생성 방법

대시보드 JSON을 처음부터 작성하기보다, Grafana UI에서 만든 후 내보내는 것이 훨씬 빠르다.

1. Grafana UI에서 새 대시보드 생성
2. 패널 추가 (위 PromQL 쿼리 활용)
3. 대시보드 설정(톱니바퀴 아이콘) → JSON Model 복사 또는 Share → Export → Save to file
4. `monitoring/grafana/provisioning/dashboards/` 에 저장
5. Grafana 재시작 또는 30초 대기 (updateIntervalSeconds 설정값)

---

## 6. 대시보드 유지보수

### 6-1. JSON 버전 관리

대시보드 JSON을 Git에 커밋해두면 Grafana를 재생성해도 동일한 대시보드를 복구할 수 있다. JSON 파일이 커지면 diff가 읽기 어려워지므로, 변경 시 의미 있는 커밋 메시지를 남기는 것이 중요하다.

```bash
# 대시보드 JSON을 Grafana API로 내보내기
curl -s http://localhost:23000/api/dashboards/uid/redpanda-overview \
  | jq '.dashboard' \
  > monitoring/grafana/provisioning/dashboards/redpanda-overview.json
```

### 6-2. 가져오기 (다른 환경으로 이식)

프로비저닝 파일이 있으면 다른 환경에서 `docker compose up`만 해도 동일한 대시보드가 자동으로 로드된다. GCP 3서버 배포 환경에서도 동일한 JSON 파일을 사용하면 로컬과 동일한 대시보드를 유지할 수 있다.

### 6-3. Datasource UID 고정

대시보드 JSON이 datasource를 참조할 때 UID를 사용한다. `datasources.yaml`에 `uid`를 명시하지 않으면 Grafana가 랜덤 UID를 생성하여, 로컬과 GCP에서 대시보드가 "datasource not found" 에러를 내게 된다.

**해결: `datasources.yaml`에 고정 UID 설정**
```yaml
datasources:
  - name: Loki
    uid: loki          # 고정
    ...
  - name: Tempo
    uid: tempo          # 고정
    ...
  - name: Prometheus
    uid: prometheus      # 고정
    ...
```

대시보드 JSON에서는 이 고정 UID를 참조한다.
```json
"datasource": { "type": "prometheus", "uid": "prometheus" }
```

이렇게 하면 어떤 환경에서든 `docker compose up`만으로 대시보드가 datasource를 정확히 찾는다.

### 6-4. 대시보드 UID 관리

JSON 파일 내 대시보드 자체의 `uid` 필드도 명시적으로 설정해야 프로비저닝 후 URL이 일관성을 가진다. UID를 설정하지 않으면 Grafana가 임의 값을 생성하여 북마크 URL이 매번 바뀐다.

| 대시보드 | UID | URL |
|----------|-----|-----|
| Redpanda Overview | `rp-overview` | `/d/rp-overview` |
| Connect Pipelines | `rp-connect` | `/d/rp-connect` |
| Spring Boot App | `rp-spring` | `/d/rp-spring` |
| System Health | `rp-system` | `/d/rp-system` |

### 6-5. 알림 규칙 변경 시 주의사항

알림 규칙(`prometheus-rules.yml`)을 변경한 후에는 Prometheus를 재시작하거나 hot-reload를 사용해야 변경이 적용된다.

```bash
# Prometheus hot-reload (SIGHUP)
docker kill --signal=SIGHUP playground-prometheus

# 또는 HTTP endpoint
curl -X POST http://localhost:29090/-/reload

# 적용 확인
curl -s http://localhost:29090/api/v1/rules | jq '.data.groups[].name'
```

---

## 7. Spring Boot 로그 Loki 직접 전송 (Loki4j)

Docker 컨테이너 로그는 Alloy가 Docker 소켓을 통해 자동 수집하지만, 로컬에서 직접 실행하는 Spring Boot 앱은 Docker 바깥이라 Alloy가 수집할 수 없다. Loki4j Logback Appender를 사용하면 앱에서 Loki로 직접 로그를 전송할 수 있다.

### 7-1. 의존성

```groovy
// app/build.gradle
implementation 'com.github.loki4j:loki-logback-appender:1.6.0'
```

주의: Maven Central의 아티팩트 이름은 `loki-logback-appender`이다. `loki4j-logback`이 아니다.

### 7-2. Logback 설정

`app/src/main/resources/logback-spring.xml`에서 GCP 프로필에서만 Loki appender를 활성화한다. 로컬 개발 시에는 콘솔 출력만 사용하고, GCP 환경에서만 Loki로 전송한다.

```xml
<springProfile name="gcp">
    <appender name="LOKI" class="com.github.loki4j.logback.Loki4jAppender">
        <http>
            <url>http://34.22.78.240:3100/loki/api/v1/push</url>
            <connectionTimeoutMs>5000</connectionTimeoutMs>
            <requestTimeoutMs>5000</requestTimeoutMs>
        </http>
        <format>
            <label>
                <pattern>service_name=redpanda-playground,level=%level,profile=gcp</pattern>
            </label>
            <message>
                <pattern>{"timestamp":"%d{...}","traceId":"%mdc{traceId:-}","message":"%message"}</pattern>
            </message>
        </format>
    </appender>
</springProfile>
```

프로필별 동작:
- **기본 프로필**: 콘솔만 (Loki 없음)
- **gcp 프로필**: 콘솔 + Loki (외부 IP로 전송)
- **test 프로필**: 콘솔만 (Loki 없음)

### 7-3. GCP 방화벽

로컬 앱에서 GCP Loki로 직접 전송하려면 포트 3100이 열려야 한다.

```bash
gcloud compute firewall-rules create allow-loki \
    --allow tcp:3100 \
    --target-tags http-server \
    --source-ranges 0.0.0.0/0 \
    --description "Allow Loki push from local Spring Boot"
```

### 7-4. Grafana에서 앱 로그 조회

```logql
{service_name="redpanda-playground"} |= ""
{service_name="redpanda-playground", level="ERROR"}
{service_name="redpanda-playground"} | json | traceId != ""
```
