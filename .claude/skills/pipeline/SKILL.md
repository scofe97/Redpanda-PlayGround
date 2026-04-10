---
name: playground-pipeline
description: DAG 실행 엔진, SAGA 패턴, Kafka 토픽, Connect 파이프라인, Observability 진입점
---

# Pipeline 스킬

트리거: "파이프라인", "DAG", "SAGA", "엔진", "실행", "토픽", "Connect"

## 모듈 구조

```
common → common-kafka → operator / executor
```

| 모듈 | 역할 | 주요 패키지 |
|------|------|-------------|
| `operator` | 파이프라인 정의/실행, REST API, DAG 엔진, SSE | `com.study.playground.pipeline` |
| `executor` | Job 실행 제어, Jenkins 연동, Hexagonal Architecture | `com.study.playground.executor` |
| `common-kafka` | Kafka 설정, Avro 직렬화, Outbox, DLQ, 트레이싱 | `com.study.playground.kafka` |
| `common` | 공통 DTO, 예외, MyBatis 설정, 멱등성 | `com.study.playground.common` |

## 아키텍처 패턴

| 패턴 | 설명 |
|------|------|
| SAGA Choreography | 파이프라인 스텝이 이벤트 토픽으로 조율 (오케스트레이터 없음) |
| Outbox Pattern | DB 쓰기 + Kafka 발행 원자적 보장 |
| Reconciliation Loop | JenkinsReconciler(60초) -- DB(desired) vs Jenkins(actual) 상태 수렴 |
| Hexagonal Architecture | executor 모듈 -- 순수 도메인 + Port/Adapter |
| Webhook Normalization | 이기종 소스(GitLab, Jenkins)를 단일 토픽으로 정규화 |
| Connect as Glue | Jenkins/GitLab 연동을 Spring Boot에 하드코딩하지 않음 |

## Phase 로드맵

| Phase | 목표 | 상태 | 스펙 |
|-------|------|------|------|
| 0 | 미들웨어 프리셋 | 완료 | `specs/phases/phase-0-middleware-preset.md` |
| 1 | Job 독립화 + DAG | 완료 | `specs/phases/phase-1-job-independence.md` |
| 2 | K8s 전면 이관 | 완료 | `specs/phases/phase-2-k8s-migration.md` |
| 3 | DAG 엔진 고도화 | 완료 | `specs/phases/phase-3-dag-hardening.md` |
| 4 | 결과물 + 배포환경 | 향후 | `specs/phases/phase-4-artifact-deploy-env.md` |
| 5 | 티켓-워크플로우-파이프라인 | 향후 | `specs/phases/phase-5-ticket-workflow.md` |

## Jenkins 통신 아키텍처

| 용도 | 경로 |
|------|------|
| Job 관리 (CRUD) | Spring Boot → JenkinsAdapter → Jenkins REST API (직접) |
| Job 실행 (빌드) | Spring Boot → Kafka → Connect → Jenkins (간접) |

## 상세 참조

- **Kafka 토픽 목록**: `references/topics.md`
- **Connect 파이프라인**: `references/connect.md`
- **Observability 마커**: `references/observability.md`
- **패턴 카탈로그**: `docs/patterns/`
- **DAG 엔진 상세**: `docs/pipeline/dag/`
- **Executor 스펙**: `executor/docs/spec.md`
