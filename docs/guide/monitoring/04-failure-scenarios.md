# 장애 시나리오와 복구 절차

이 문서는 Redpanda Playground의 각 컴포넌트가 다운됐을 때 영향 범위, 감지 방법, 복구 절차를 정리한다. 장애를 빠르게 진단하고 서비스를 복원하는 것이 목적이다. 아키텍처 전반은 [monitoring-guide.md](../monitoring-guide.md)를, 알림 설정은 [05-dashboards-and-alerts.md](./05-dashboards-and-alerts.md)를 참조한다.

---

## 1. 장애 매트릭스

장애 시 가장 먼저 할 일은 영향 범위를 파악하는 것이다. 컴포넌트마다 의존 관계가 다르기 때문에, 동일한 증상도 원인이 다를 수 있다.

| 장애 컴포넌트 | 영향 범위 | 감지 방법 | 복구 절차 (Docker) | 복구 절차 (K8s) | 핵심 로그 패턴 |
|---|---|---|---|---|---|
| **Redpanda** | 전체 메시징 중단 — Producer 실패, Consumer 정지, Connect 파이프라인 중단 | `docker inspect --format='{{.State.Health.Status}}' playground-redpanda` 결과 `unhealthy`, consumer lag 급증, `up{job="redpanda"}==0` | `docker compose restart redpanda` 후 파티션 리밸런스 대기 | liveness probe → 자동 재시작, PDB로 순차 재시작 | `broker 연결 실패`, `Connection refused`, KafkaTemplate 예외 |
| **Redpanda Connect** | Jenkins 연동 중단 — 커맨드/웹훅 파이프라인 정지, DLQ 누적 | `curl -s http://localhost:4195/ready` 실패, `output_error` 급증 | `docker compose restart connect` | readiness probe → 트래픽 차단 후 재시작 | `unable to open connection`, `context deadline exceeded` |
| **PostgreSQL** | DB 의존 전체 기능 중단 — 티켓 API, Outbox 폴링, 이벤트 저장 불가 | Spring Boot actuator health DOWN, JDBC connection pool exhausted | `docker compose restart postgres` 후 Flyway 마이그레이션 상태 확인 | PDB + StatefulSet, 볼륨 보존 | `Connection refused :5432`, HikariPool timeout |
| **Spring Boot** | API 전체 중단 — 프론트엔드 불가, Outbox 폴링 중단 | health endpoint 무응답, Prometheus scrape 실패 | `./gradlew :app:bootRun` 재실행 | Deployment rollout restart | `application shutdown`, actuator 무응답 |
| **Alloy** | 텔레메트리 수집 중단 — 로그/트레이스/메트릭 신규 수집 안 됨 (서비스 자체는 정상 운영) | Alloy UI (`localhost:24312`) 무응답, Loki/Tempo에 새 데이터 미수신 | `docker compose -f docker-compose.monitoring.yml restart alloy` | DaemonSet rollout restart | `pipeline error`, `docker.sock permission denied` |
| **Loki** | 로그 조회 불가 — Grafana 로그 검색 안 됨 (수집은 Alloy가 버퍼링) | Grafana Loki 데이터소스 에러, `http://localhost:3100/ready` 실패 | restart + WAL 복구 대기 | StatefulSet restart | `ingester error`, `WAL corruption` |
| **Tempo** | 트레이스 조회 불가 — Grafana Bad Gateway (수집은 Alloy가 버퍼링) | Grafana Tempo Bad Gateway (502), exit code 137 (OOM) | restart + WAL replay 대기 (1GB 메모리 필요) | StatefulSet restart, 메모리 limit 확인 | `WAL replay OOM`, `exit 137` |
| **Prometheus** | 메트릭 조회 불가 — Grafana PromQL 실패 | `http://localhost:29090/-/ready` 실패, TSDB lock 에러 | restart + TSDB 복구 대기 | StatefulSet restart | `TSDB lock`, `WAL corruption` |

---

## 2. 복구 절차 상세 (Docker Compose 기준)

### 2-1. Redpanda

Redpanda가 다운되면 Spring Boot의 KafkaTemplate 전송이 실패하고 Connect 파이프라인이 멈춘다. Outbox 폴링은 계속 시도하지만 메시지를 발행하지 못하므로 outbox 테이블에 레코드가 쌓인다.

**진단:**
```bash
# 헬스 상태 확인
docker inspect --format='{{.State.Health.Status}}' playground-redpanda

# 로그 확인 (최근 50줄)
docker logs playground-redpanda --tail 50

# Prometheus로 브로커 up 확인
curl -s 'http://localhost:29090/api/v1/query?query=up{job="redpanda"}' | jq '.data.result'

# consumer lag 확인
docker exec playground-redpanda rpk group list
docker exec playground-redpanda rpk group describe <group-id>
```

**복구:**
```bash
# 1. 재시작
docker compose restart redpanda

# 2. 컨테이너 상태 확인 (healthy가 될 때까지 대기)
watch docker inspect --format='{{.State.Health.Status}}' playground-redpanda

# 3. Redpanda Connect 재시작 (Redpanda가 먼저 올라온 후 실행)
docker compose restart connect

# 4. 파티션 리밸런스 완료 확인
docker exec playground-redpanda rpk cluster health
```

**주의:** Redpanda가 재시작되면 Connect가 연결을 잃고 자동 재연결을 시도한다. 대부분 자동 복구되지만, 재연결이 안 될 경우 Connect도 함께 재시작해야 한다.

**Spring Boot Outbox 복구 확인:**
```bash
# Outbox 테이블에 처리되지 않은 레코드가 남아 있으면 폴링이 재개될 때 자동 발행됨
docker exec playground-postgres psql -U playground -d playground -c \
  "SELECT COUNT(*) FROM outbox_events WHERE processed = false;"
```

---

### 2-2. Redpanda Connect

Connect가 다운되면 Jenkins 커맨드 실행 및 웹훅 수신이 중단된다. 이 파이프라인을 통해 들어오는 트리거가 없으므로 새 파이프라인 실행이 시작되지 않는다.

**진단:**
```bash
# ready 엔드포인트 확인
curl -s http://localhost:4195/ready

# 로그 확인
docker logs playground-connect --tail 50

# 에러 메트릭 확인
curl -s http://localhost:4195/metrics | grep output_error

# DLQ 토픽에 메시지 쌓였는지 확인
docker exec playground-redpanda rpk topic consume dlq.pipeline-commands --num 5
```

**복구:**
```bash
# 재시작
docker compose restart connect

# 재시작 후 파이프라인 상태 확인
curl -s http://localhost:4195/ready
curl -s http://localhost:4195/metrics | grep input_received
```

**DLQ 처리:** Connect 다운 중 누락된 커맨드는 DLQ에 쌓이지 않는다. 외부 시스템(Jenkins)이 Connect에 직접 전송하는 구조이므로, Connect가 다운된 동안 수신 못한 웹훅은 재전송이 필요하다.

---

### 2-3. PostgreSQL

PostgreSQL이 다운되면 Spring Boot의 모든 DB 작업이 실패한다. JPA 조회, Outbox INSERT, 이벤트 저장이 모두 불가능해진다.

**진단:**
```bash
# 컨테이너 상태 확인
docker inspect --format='{{.State.Health.Status}}' playground-postgres

# 로그 확인
docker logs playground-postgres --tail 30

# Spring Boot actuator health 확인 (DB 항목이 DOWN이면 PostgreSQL 문제)
curl -s http://localhost:8080/actuator/health | jq '.components.db'
```

**복구:**
```bash
# 재시작
docker compose restart postgres

# 연결 확인
docker exec playground-postgres pg_isready -U playground

# Spring Boot가 자동으로 커넥션 풀을 복구하는지 확인 (HikariCP 자동 재연결)
curl -s http://localhost:8080/actuator/health | jq '.components.db'

# Flyway 마이그레이션 상태 확인 (버전 불일치가 없는지)
docker exec playground-postgres psql -U playground -d playground -c \
  "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;"
```

**HikariCP 자동 복구:** Spring Boot는 HikariCP를 사용하므로 DB가 재시작되면 커넥션 풀이 자동으로 유효한 커넥션을 재확보한다. 별도의 애플리케이션 재시작이 필요하지 않다.

---

### 2-4. Spring Boot 애플리케이션

Spring Boot는 Docker가 아닌 호스트에서 실행되므로, 컨테이너 기반 복구 절차와 다르다. 이 때문에 Alloy의 Docker 로그 수집 범위에 포함되지 않으며, 로그는 콘솔 출력이나 파일 기반으로만 확인 가능하다.

**진단:**
```bash
# health 엔드포인트 확인
curl -s http://localhost:8080/actuator/health

# Prometheus scrape 상태 확인 (Spring Boot가 메트릭을 제공하는지)
curl -s http://localhost:8080/actuator/prometheus | head -5

# 프로세스 확인
ps aux | grep 'spring' | grep -v grep
```

**복구:**
```bash
# 프로젝트 루트에서 재시작
cd /path/to/redpanda-playground
./gradlew :app:bootRun

# 또는 환경변수가 필요한 경우
OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:24317 \
OTEL_SERVICE_NAME=redpanda-playground \
./gradlew :app:bootRun

# 정상 기동 확인
curl -s http://localhost:8080/actuator/health | jq '.status'
```

---

### 2-5. Alloy

Alloy가 다운되면 모니터링 데이터 수집이 중단된다. 하지만 서비스 자체(Redpanda, Spring Boot 등)는 계속 정상 동작한다. Alloy가 재시작되면 OTLP 수신을 재개하고, 스크래핑도 다시 시작한다.

**진단:**
```bash
# Alloy UI 확인
curl -s http://localhost:24312/-/ready

# 컨테이너 로그
docker logs playground-alloy --tail 30

# docker.sock 권한 문제 확인 (가장 흔한 원인)
docker logs playground-alloy 2>&1 | grep -i "permission denied"
```

**복구:**
```bash
docker compose -f docker-compose.monitoring.yml restart alloy

# 재시작 후 파이프라인 상태 확인
curl -s http://localhost:24312/-/ready
# Alloy UI에서 컴포넌트 상태 확인: http://localhost:24312
```

**docker.sock 권한 문제:** Alloy는 컨테이너 로그 수집을 위해 docker.sock에 접근한다. 권한 오류가 발생하면 `docker-compose.monitoring.yml`에서 Alloy 서비스의 user 설정과 그룹을 확인해야 한다.

---

### 2-6. Loki

Loki가 다운되면 Grafana에서 로그를 조회할 수 없다. 단, Alloy는 로그를 자체 버퍼에 유지하므로 Loki가 복구되면 버퍼 내 로그가 전달된다(일정 시간 내).

**진단:**
```bash
# ready 확인
curl -s http://localhost:3100/ready

# 로그 확인
docker logs playground-loki --tail 30

# WAL 파일 상태 (corruption 여부)
docker exec playground-loki ls -la /loki/wal/
```

**복구:**
```bash
docker compose -f docker-compose.monitoring.yml restart loki

# WAL 복구 대기 (시작 로그에서 "WAL replay complete" 확인)
docker logs playground-loki -f | grep -i "wal"

# Grafana에서 Loki 데이터소스 상태 확인
curl -s http://localhost:23000/api/datasources/proxy/uid/loki/ready
```

---

### 2-7. Tempo

Tempo는 이 프로젝트에서 가장 자원 민감한 컴포넌트다. 256MB/512MB 설정에서 OOM으로 강제 종료된 경험이 있으며, 현재 1GB로 설정되어 있다. WAL replay 시 메모리 사용량이 급증하므로 재시작 후 즉시 OOM이 발생할 수 있다.

**진단:**
```bash
# exit code 137은 OOM killed 신호
docker inspect playground-tempo --format='{{.State.ExitCode}}'

# 메모리 사용량 확인
docker stats playground-tempo --no-stream

# 로그 확인
docker logs playground-tempo --tail 50

# Grafana에서 Tempo Bad Gateway 오류 → Tempo가 완전히 죽은 상태
```

**복구:**
```bash
docker compose -f docker-compose.monitoring.yml restart tempo

# WAL replay 진행 상황 모니터링 (완료까지 대기)
docker logs playground-tempo -f | grep -E "replay|ingester|started"

# 재시작 후 ready 확인 (WAL replay 완료 후에야 ready)
curl -s http://localhost:3200/ready

# 메모리 사용량이 안정화되는지 확인
watch -n 5 'docker stats playground-tempo --no-stream'
```

**메모리 설정 확인:** `docker-compose.monitoring.yml`에서 Tempo의 `mem_limit`이 1GB로 설정되어 있는지 확인한다. Tempo 자원 측정 상세는 [06-tempo-resource-measurement.md](./06-tempo-resource-measurement.md)를 참조한다.

---

### 2-8. Prometheus

Prometheus가 다운되면 PromQL 기반 메트릭 조회가 불가능해진다. Alloy는 remote_write를 버퍼링하므로 Prometheus 복구 후 미전송 메트릭이 일부 복구된다.

**진단:**
```bash
# ready 확인
curl -s http://localhost:29090/-/ready

# TSDB 상태 확인
curl -s http://localhost:29090/api/v1/status/tsdb | jq '.data'

# 로그 확인
docker logs playground-prometheus --tail 30
```

**복구:**
```bash
docker compose -f docker-compose.monitoring.yml restart prometheus

# 복구 확인
curl -s http://localhost:29090/-/ready

# TSDB 크기와 시계열 수 확인
curl -s http://localhost:29090/api/v1/status/tsdb | jq '.data.headStats'
```

---

## 3. 복합 장애 시나리오

단일 컴포넌트 장애보다 복합 장애가 더 까다롭다. 의존 관계를 고려한 복구 순서가 중요하다.

### 3-1. Redpanda + Connect 동시 다운

Redpanda가 먼저 복구되어야 Connect가 재연결할 수 있다.

```bash
# 1. Redpanda 먼저 재시작
docker compose restart redpanda

# 2. Redpanda healthy 상태 확인
watch docker inspect --format='{{.State.Health.Status}}' playground-redpanda
# healthy가 되면 Ctrl+C

# 3. Connect 재시작
docker compose restart connect

# 4. Connect 파이프라인 정상 동작 확인
curl -s http://localhost:4195/ready
```

### 3-2. 모니터링 스택 전체 다운

서비스(Redpanda, Spring Boot, Connect)는 계속 동작한다. 모니터링만 복구하면 된다.

```bash
# 모니터링 스택 전체 재시작
docker compose -f docker-compose.monitoring.yml restart

# 순서: Loki → Tempo → Prometheus → Alloy → Grafana 순으로 올라오는지 확인
docker compose -f docker-compose.monitoring.yml ps

# Tempo WAL replay가 완료될 때까지 Grafana 트레이스 조회가 안 될 수 있음 (정상)
docker logs playground-tempo -f | grep "started"
```

### 3-3. PostgreSQL + Spring Boot 동시 다운

DB가 먼저 올라와야 Spring Boot가 정상 기동된다. Flyway 마이그레이션이 기동 시 실행되기 때문이다.

```bash
# 1. PostgreSQL 재시작
docker compose restart postgres

# 2. DB 연결 확인
docker exec playground-postgres pg_isready -U playground

# 3. Spring Boot 재시작
./gradlew :app:bootRun

# 4. actuator health 확인
curl -s http://localhost:8080/actuator/health | jq
```

### 3-4. 전체 스택 재시작 (호스트 재부팅 후)

호스트 재부팅 후에는 Docker 컨테이너와 Spring Boot를 특정 순서로 시작해야 한다.

```bash
# 1. 코어 인프라 (Redpanda, PostgreSQL, Connect)
make infra
# 또는: docker compose up -d

# 2. 인프라 헬스 확인
docker compose ps
docker exec playground-redpanda rpk cluster health

# 3. Spring Boot (인프라가 올라온 후)
./gradlew :app:bootRun &

# 4. 모니터링 스택
make monitoring
# 또는: docker compose -f docker-compose.monitoring.yml up -d

# 5. 전체 상태 확인
curl -s http://localhost:8080/actuator/health
curl -s http://localhost:4195/ready
curl -s http://localhost:23000/api/health
```

---

## 4. 로깅 전략

### 4-1. 컴포넌트별 로그 확인 명령어

```bash
# 실시간 로그 스트리밍
docker logs -f playground-redpanda
docker logs -f playground-connect
docker logs -f playground-postgres
docker logs -f playground-alloy
docker logs -f playground-loki
docker logs -f playground-tempo
docker logs -f playground-prometheus

# 최근 N줄
docker logs playground-tempo --tail 100

# 특정 키워드 필터링
docker logs playground-tempo 2>&1 | grep -i "error\|warn\|oom\|exit"

# 타임스탬프 포함
docker logs playground-redpanda --timestamps --tail 50
```

### 4-2. 핵심 로그 패턴

| 컴포넌트 | 패턴 | 의미 | 대응 |
|----------|------|------|------|
| Redpanda | `Connection refused` | 브로커 연결 불가 | 브로커 재시작 |
| Redpanda | `LEADER_NOT_AVAILABLE` | 파티션 리더 선출 중 | 잠시 대기 |
| Connect | `unable to open connection` | Redpanda 연결 실패 | Redpanda 상태 확인 |
| Connect | `context deadline exceeded` | 처리 타임아웃 | 타임아웃 설정 검토 |
| PostgreSQL | `max_connections exceeded` | 커넥션 풀 고갈 | HikariCP 설정 확인 |
| Tempo | `exit 137` | OOM killed | 메모리 limit 증가 |
| Tempo | `WAL replay` | 재시작 후 복구 중 | 완료까지 대기 |
| Alloy | `permission denied docker.sock` | 소켓 권한 없음 | 그룹/user 설정 확인 |
| Loki | `WAL corruption` | WAL 파일 손상 | WAL 디렉토리 삭제 후 재시작 |

### 4-3. Grafana에서 로그 조회

모니터링 스택이 정상일 때는 Grafana Explore에서 LogQL로 컨테이너 로그를 조회할 수 있다. Spring Boot는 Docker 컨테이너가 아닌 호스트에서 실행되므로 Docker 로그 수집 대상이 아니다.

```logql
# Redpanda 에러 로그
{container="playground-redpanda"} |= "error"

# Connect 파이프라인 에러
{container="playground-connect"} |= "error" | json

# PostgreSQL 연결 오류
{container="playground-postgres"} |= "connection"

# 전체 컨테이너 에러 (Tempo 제외)
{container=~"playground-.*"} != "playground-tempo" |= "error"
```

---

## 5. 온콜 정책 (PoC 수준)

PoC 환경이지만 장애 우선순위를 정의해두면 복구 순서를 빠르게 결정할 수 있다.

### P1 — 즉시 대응

서비스 핵심 기능이 완전히 중단된 경우다.

- **Redpanda 다운**: 메시지 발행/소비 전체 중단, Outbox 레코드 누적
- **PostgreSQL 다운**: API 전체 불가, 데이터 저장 불가

**대응:** 즉시 재시작 시도. 3회 재시작 실패 시 볼륨/디스크 상태 확인.

### P2 — 30분 내 대응

주요 기능이 중단됐지만 데이터 유실 위험이 낮은 경우다.

- **Redpanda Connect 다운**: Jenkins 연동 중단, 신규 파이프라인 트리거 불가
- **Spring Boot 다운**: API 불가, Outbox 폴링 중단 (DB에 레코드 보존됨)

**대응:** 로그 확인 후 재시작. Spring Boot는 Outbox 레코드가 보존되므로 재시작 후 자동 복구된다.

### P3 — 4시간 내 대응

서비스 자체는 정상이지만 가시성이 없는 경우다.

- **모니터링 스택 다운** (Alloy/Loki/Tempo/Prometheus 중 하나 이상): 로그, 트레이스, 메트릭 조회 불가
- 서비스 동작에는 영향 없음

**대응:** 여유 있게 원인 파악 후 재시작. Alloy 버퍼 내 데이터는 모니터링 스택 복구 후 자동 전달된다.

---

## 6. 예방적 점검

정기 점검으로 장애를 사전에 방지할 수 있다. 특히 Tempo는 디스크와 메모리 사용량이 지속적으로 증가하므로 주기적 확인이 필요하다.

### 6-1. 일일 점검 (실행 중인 경우)

```bash
# 전체 컨테이너 상태
docker compose ps
docker compose -f docker-compose.monitoring.yml ps

# 메모리 사용량 스냅샷
docker stats --no-stream

# Tempo 디스크 사용량
docker exec playground-tempo du -sh /var/tempo/

# consumer lag
docker exec playground-redpanda rpk group list
```

### 6-2. 주간 점검

```bash
# 디스크 전체 사용량
df -h

# Docker 볼륨 크기
docker system df -v

# Prometheus TSDB 크기
curl -s http://localhost:29090/api/v1/status/tsdb | jq '.data.headStats'

# Tempo 트레이스 데이터 크기
docker exec playground-tempo du -sh /var/tempo/traces/

# Loki 청크 크기
docker exec playground-loki du -sh /loki/chunks/
```

### 6-3. 로그 로테이션 확인

Docker 기본 설정은 로그 로테이션이 없어서 장시간 실행 시 로그 파일이 무제한 증가한다. `docker-compose.yml`과 `docker-compose.monitoring.yml`에 logging 설정이 있는지 확인한다.

```yaml
# docker-compose 파일에 추가 권장
logging:
  driver: "json-file"
  options:
    max-size: "50m"
    max-file: "3"
```

### 6-4. 리텐션 정책 확인

| 스토리지 | 보존 기간 | 설정 위치 |
|----------|----------|----------|
| Loki | 72h (3일) | `monitoring/loki-config.yaml` |
| Tempo | 72h (3일) | `monitoring/tempo-config.yaml` |
| Prometheus | 3일 | `docker-compose.monitoring.yml` `--storage.tsdb.retention.time` |

리텐션 기간이 지난 데이터는 자동 삭제된다. 장기 보존이 필요한 경우 GCP 배포 환경에서 리텐션을 늘리되, Tempo 디스크 사용량과 메모리 limit을 함께 조정해야 한다.
