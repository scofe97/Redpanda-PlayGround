# Observability

## 로그 마커

Grafana Pipeline Tracker 대시보드(`infra/k8s/monitoring/grafana/dashboards/pipeline-tracker.json`)는 Loki 로그의 마커 텍스트에 의존한다.

| 마커 | 클래스 | 대시보드 패널 |
|------|--------|--------------|
| `[StepChanged]` | `PipelineEventProducer`, `DagEventProducer` | Timeline |
| `[ExecutionCompleted]` | `PipelineEventProducer` | Status |
| `[DagJobDispatched]` | `DagEventProducer` | Logs |

### 형식

```
[마커] executionId={}, jobName={}, status={}, ...
```

key=value 쌍으로 구성. 대시보드 regexp가 이 형식을 파싱한다.

### 주의사항

- 마커명 변경 시 대시보드 JSON의 Loki 쿼리 regexp도 함께 수정
- 필드 순서 변경 시에도 regexp 확인 필요
- 상세: `infra/docs/01-monitoring/05-dashboards-and-alerts.md`

## 대시보드

| 대시보드 | 경로 | 용도 |
|----------|------|------|
| Pipeline Tracker | `infra/k8s/monitoring/grafana/dashboards/pipeline-tracker.json` | 파이프라인 실행 추적 |

## Loki 쿼리 패턴

```logql
# 특정 실행의 전체 흐름
{app="playground"} |= "executionId=<ID>"

# 스텝 변경 이벤트만
{app="playground"} |= "[StepChanged]"

# 실패한 실행
{app="playground"} |= "[ExecutionCompleted]" |= "status=FAILED"
```

## OTel 트레이싱

- 프로토콜: OTLP HTTP
- 로컬: `localhost:24318`
- GCP: `34.47.83.38:30318` (Alloy → Tempo)
- Kafka 헤더 기반 분산 추적 인터셉터 (`common-kafka` 모듈)
