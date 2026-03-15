# Troubleshooting

이 문서는 모니터링 스택 운영 중 발생하는 문제의 진단 레시피와 알려진 제한사항을 다룬다. 아키텍처 개요는 [01-architecture-overview.md](./01-architecture-overview.md), 데이터 흐름 설정은 [02-setup-and-usage.md](./02-setup-and-usage.md), OTel 계측 상세는 [03-otel-instrumentation.md](./03-otel-instrumentation.md)을 참조한다.

---

## 1. 알려진 제약사항

### 1-1. Jenkins 블랙박스

Connect → Jenkins HTTP 호출 후 Jenkins 내부는 추적 불가하다. Jenkins가 빌드를 완료하고 webhook 콜백을 보내면 새 trace가 시작된다. Jenkins가 W3C traceparent 헤더를 전파하지 않기 때문이며, 연결 방법이 없다.

### 1-2. Spring Boot 로그 미수집

Spring Boot는 Docker 컨테이너가 아닌 호스트에서 실행되므로 Alloy가 로그를 수집하지 못한다. 트레이스와 메트릭은 OTLP/Prometheus로 정상 수집된다. 로그는 터미널 또는 IntelliJ 콘솔에서 확인한다.

---

## 2. 트러블슈팅 레시피

### 2-1. Tempo에 트레이스가 안 보일 때

순서대로 확인한다.

1. `lib/opentelemetry-javaagent.jar` 파일이 존재하는지 확인
2. Spring Boot 시작 로그에서 `[otel.javaagent]` 로그가 출력되는지 확인
3. Alloy가 실행 중인지 확인: `docker ps | grep alloy`
4. Alloy → Tempo 연결 확인: `docker logs playground-alloy 2>&1 | grep -i error`
5. Alloy 메트릭으로 수신/전송 통계 확인: `curl http://localhost:24312/metrics | grep otelcol_`
6. Tempo가 OOM으로 재시작된 경우 WAL 데이터가 블록으로 플러시되지 않았을 수 있다. `docker compose -f docker-compose.monitoring.yml restart tempo`로 WAL을 강제 플러시한다.

### 2-2. Tempo OOM (exit 137)

Tempo는 WAL replay 시 메모리를 많이 소모한다. `docker logs playground-tempo`에서 로그 없이 종료되면 OOM killed를 의심한다. `docker-compose.monitoring.yml`의 memory limit을 확인한다. 현재 1GB로 설정되어 있으며, 256MB와 512MB에서 반복 OOM이 발생했다.

### 2-3. Grafana에서 Tempo Bad Gateway

Tempo 컨테이너가 중단되었을 가능성이 높다. `docker ps`로 상태를 확인하고, 재시작 후 healthy 상태가 될 때까지 기다린다(약 10초).

```bash
docker compose -f docker/docker-compose.monitoring.yml restart tempo
docker ps --filter name=playground-tempo   # Status: healthy 확인
```

### 2-4. Loki 인제스트 에러

`ingestion rate limit exceeded` 에러가 발생하면 `loki-config.yaml`의 `ingestion_rate_mb`를 올린다. 현재 10MB/s로 설정되어 있다.

```yaml
# loki-config.yaml
limits_config:
  ingestion_rate_mb: 10        # 필요 시 상향 (예: 20)
  ingestion_burst_size_mb: 20
```

GitLab처럼 로그량이 과도한 컨테이너가 수집 대상에 포함된 경우 `alloy-config.alloy`의 drop 필터를 확인한다.

### 2-5. Prometheus 타겟이 DOWN일 때

스크래핑은 Alloy가 담당하므로 Prometheus의 Targets 페이지(`http://localhost:29090/targets`)에는 타겟이 표시되지 않는다. 스크래핑 상태를 확인하려면 Alloy UI(`http://localhost:24312`)에서 `prometheus.scrape` 컴포넌트를 확인한다. Spring Boot가 꺼져 있으면 해당 scrape 컴포넌트에 에러가 표시되는데, 이건 정상이다.

### 2-6. Connect tracer가 동작하지 않을 때

Connect 로그에서 `field tracer not recognised` 경고가 나오면, tracer 설정이 파이프라인 YAML에 들어간 것이다. streams 모드에서는 `observability.yaml`(-o 플래그)에만 넣어야 한다.

올바른 구조 확인:

```bash
# docker-compose.yml의 command 확인
docker inspect playground-connect --format '{{.Args}}'
# 출력에 -o /etc/connect/observability.yaml 포함 여부 확인
```

Connect tracer가 실제로 스팬을 생성하는지 검증하는 절차:

1. Connect 로그에서 `open_telemetry_collector` 관련 초기화 로그 확인
2. Alloy UI(`http://localhost:24312`) → `otelcol.receiver.otlp` 컴포넌트 → received spans 카운터 증가 여부 확인
3. Grafana Explore → Tempo → Service Name: `redpanda-connect` 검색

### 2-7. Alloy 파이프라인 디버깅 (UI: :24312)

Alloy UI는 파이프라인 상태를 시각적으로 보여준다. 각 컴포넌트의 처리량, 에러, 설정값을 실시간으로 확인할 수 있다.

```
http://localhost:24312
```

주요 확인 포인트:
- **`loki.source.docker`**: 수집 중인 컨테이너 수, 수신 로그 라인 수
- **`otelcol.receiver.otlp`**: 수신 spans/metrics 카운터
- **`otelcol.processor.filter "noise"`**: 드롭된 스팬 수 (필터가 너무 광범위한지 점검)
- **`prometheus.scrape.*`**: 각 타겟의 마지막 스크래핑 시각, 에러 여부
- **`prometheus.remote_write`**: Prometheus로 전송된 샘플 수

컴포넌트를 클릭하면 상세 설정과 현재 상태 메트릭이 표시된다. 설정 파일을 수정한 뒤 `docker compose restart alloy`하면 자동으로 재로드된다.

### 2-8. Docker 소켓 권한 문제

Alloy가 Docker 소켓을 마운트하지 못하면 로그 수집이 전혀 되지 않는다.

증상: Loki에 어떤 컨테이너 로그도 들어오지 않음. Alloy 로그에 `permission denied` 또는 `/var/run/docker.sock: no such file` 에러.

```bash
# Alloy 컨테이너 로그 확인
docker logs playground-alloy 2>&1 | grep -E "docker|socket|permission"

# docker-compose.monitoring.yml에서 소켓 마운트 확인
grep -A2 "docker.sock" docker/docker-compose.monitoring.yml
```

macOS Docker Desktop에서는 `/var/run/docker.sock`이 VM 내부에 있지만 Docker Desktop이 자동으로 소켓 프록시를 제공한다. 소켓 마운트 경로가 정확한지 확인한다.

### 2-9. Prometheus remote_write 연결 실패

Alloy가 Prometheus로 메트릭을 전송하지 못하면 Alloy UI의 `prometheus.remote_write` 컴포넌트에 에러가 표시된다.

```bash
# Prometheus가 remote_write receiver를 활성화했는지 확인
docker inspect playground-prometheus --format '{{.Args}}'
# --web.enable-remote-write-receiver 플래그 포함 여부 확인

# Prometheus 로그에서 수신 확인
docker logs playground-prometheus 2>&1 | grep -i "remote_write\|write"
```

Alloy의 remote_write 엔드포인트가 `http://prometheus:9090/api/v1/write`로 설정되어 있는지 `alloy-config.alloy`에서 확인한다. Docker Compose 네트워크 내부에서는 서비스명(`prometheus`)으로 통신한다.

---

## 3. 운영 점검

장애 시나리오 대응([04-failure-scenarios.md](./04-failure-scenarios.md))과 별도로, 정기 점검으로 문제를 사전에 감지할 수 있다.

### 3-1. 컨테이너 로그 확인 명령어

```bash
# 실시간 로그 스트리밍
docker logs -f playground-redpanda
docker logs -f playground-connect
docker logs -f playground-postgres

# 최근 N줄
docker logs playground-tempo --tail 100

# 특정 키워드 필터링
docker logs playground-tempo 2>&1 | grep -i "error\|warn\|oom\|exit"

# 타임스탬프 포함
docker logs playground-redpanda --timestamps --tail 50
```

### 3-2. LogQL 조회 예시

모니터링 스택이 정상일 때 Grafana Explore에서 LogQL로 컨테이너 로그를 조회할 수 있다. Spring Boot는 호스트에서 실행되므로 Docker 로그 수집 대상이 아니다.

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

### 3-3. 일일/주간 점검 체크리스트

**일일 점검** (실행 중인 경우):

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

**주간 점검:**

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

### 3-4. 리텐션 정책 요약

| 스토리지 | 보존 기간 | 설정 위치 |
|----------|----------|----------|
| Loki | 72h (3일) | `monitoring/loki-config.yaml` |
| Tempo | 72h (3일) | `monitoring/tempo-config.yaml` |
| Prometheus | 3일 | `docker-compose.monitoring.yml` `--storage.tsdb.retention.time` |

리텐션 기간이 지난 데이터는 자동 삭제된다. 장기 보존이 필요한 경우 GCP 배포 환경에서 리텐션을 늘리되, Tempo 디스크 사용량과 메모리 limit을 함께 조정해야 한다. 자원 측정 상세는 [06-tempo-resource-measurement.md](./06-tempo-resource-measurement.md)를 참조한다.
