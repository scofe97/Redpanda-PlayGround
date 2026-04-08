# Redpanda Playground

이 문서는 프로젝트 전체를 하나의 흐름으로 이해하기 위한 종합 가이드다. 각 섹션에서 하위 문서를 교차 참조하되, 여기서는 전체 그림을 잡는 데 집중한다.

## 최근 변경

- [2026-04-09-operator-executor-implementation-notes.md](2026-04-09-operator-executor-implementation-notes.md)
  `app`, `pipeline`, `operator-stub`를 `operator`로 통합하고 `executor` 디스패치/재시도 흐름을 안정화한 작업 요약이다.
- 아래 `app`/`pipeline` 하위 review 문서는 현재 코드베이스의 live 구조라기보다 통합 이전 phase 문서에 가깝다.

---

## 문서 인덱스

### 프로젝트 공통

| 문서 | 내용 |
|------|------|
| [architecture.md](architecture.md) | DB 설계(ERD, Flyway), 핵심 기능 흐름, 이벤트/메시지 설계, 동시성 보장 |
| [domain-guide.md](domain-guide.md) | 도메인 온보딩 가이드 (Ticket → Pipeline → Webhook) |
| [patterns.md](patterns.md) | 아키텍처 패턴 카탈로그 (14개 패턴) |
| [demo-script.md](demo-script.md) | 데모 시나리오 스크립트 |

### app 모듈 (Phase 1)

| 문서 | 내용 |
|------|------|
| [app/review/01-structure-overview.md](app/review/01-structure-overview.md) | app 모듈 구조 오버뷰 |
| [app/review/02-db-migration.md](app/review/02-db-migration.md) | DB 마이그레이션 리뷰 |
| [app/review/03-service-layer-and-connector.md](app/review/03-service-layer-and-connector.md) | 서비스 계층 및 커넥터 리뷰 |
| [app/review/04-preset-domain.md](app/review/04-preset-domain.md) | 프리셋 도메인 리뷰 |

### pipeline 모듈 (Phase 2-3)

| 문서 | 내용 |
|------|------|
| [pipeline/pipeline-flow.md](pipeline/pipeline-flow.md) | 파이프라인 실행 흐름도 + 토픽/커넥터 상세 |
| [pipeline/review/05-phase2-structure-overview.md](pipeline/review/05-phase2-structure-overview.md) | Phase 2 전체 구조 오버뷰 (DAG 엔진) |
| [pipeline/review/06-phase2-db-migration.md](pipeline/review/06-phase2-db-migration.md) | Phase 2 DB 마이그레이션 리뷰 |
| [pipeline/review/07-phase2-dag-engine.md](pipeline/review/07-phase2-dag-engine.md) | DAG 엔진 핵심 리뷰 |
| [pipeline/review/08-phase3-dag-hardening.md](pipeline/review/08-phase3-dag-hardening.md) | Phase 3 DAG 엔진 고도화 리뷰 |

### frontend 모듈

| 문서 | 내용 |
|------|------|
| [frontend/01-architecture.md](frontend/01-architecture.md) | 기술 스택, 페이지 구성, 핵심 훅, SSE + TanStack Query 연동 |
| [frontend/02-ui-styling-guide.md](frontend/02-ui-styling-guide.md) | Google Stitch UI 디자인 적용 가이드 |

### requirements/

| 문서 | 내용 |
|------|------|
| [README.md](requirements/README.md) | 요구사항 인덱스 + 전체 현황 대시보드 + 유스케이스 |
| [01-ticket.md](requirements/01-ticket.md) | 티켓 관리 — 업무코드, 워크플로우 연계 |
| [02-workflow.md](requirements/02-workflow.md) | 워크플로우 — 컴포넌트 조합 (빌드/배포/결재) |
| [03-pipeline.md](requirements/03-pipeline.md) | 파이프라인 — 독립성, Job 구성관리 (가이드형/자유형) |
| [04-build-job.md](requirements/04-build-job.md) | 빌드 Job — 빌드/반입 선택, CI 추상화 |
| [05-test-job.md](requirements/05-test-job.md) | 테스트 Job — PoC 생략, TPS 참고용 |
| [06-deploy-job.md](requirements/06-deploy-job.md) | 배포 Job — VM/K8S 분기, CD 추상화 |
| [07-artifact-management.md](requirements/07-artifact-management.md) | 결과물 — 조회/업로드/수정 |
| [08-middleware.md](requirements/08-middleware.md) | 미들웨어 — 역할 기반 + 프리셋 |
| [09-deploy-environment.md](requirements/09-deploy-environment.md) | 배포 환경 — 서버/클러스터/저장소 관리 |
| [10-user-and-notification.md](requirements/10-user-and-notification.md) | 사용자 — 권한/알림 |

### infra/

| 문서 | 내용 |
|------|------|
| [01-docker-compose.md](infra/01-docker-compose.md) | Docker Compose 분리 구조 및 GCP 오프로딩 |
| [02-redpanda-guide.md](infra/02-redpanda-guide.md) | Redpanda 운영 가이드 + Connect Streams 모드 |
| [03-jenkins.md](infra/03-jenkins.md) | Jenkins 설정 및 Webhook 발송 전략 |
| [04-deployment.md](infra/04-deployment.md) | GCP 멀티 서버 분산 배포 가이드 |
| [05-monitoring/](infra/01-monitoring/) | 모니터링 스택 (Grafana, Loki, Tempo, Alloy, Prometheus) |

---

## 1. 프로젝트 목적과 범위

### 왜 만들었는가

TPS(CI/CD 플랫폼)에서 사용하는 이벤트 기반 아키텍처 패턴을 학습용으로 축소 구현한 PoC 프로젝트다. 실무에서는 수십 개 마이크로서비스가 엮이지만, 이 프로젝트는 단일 Spring Boot 앱 안에서 도메인 경계를 나누고 Kafka(Redpanda)로 비동기 통신하는 구조를 보여준다. "모놀리스 안의 이벤트 드리븐"이라고 볼 수 있다.

### 어떤 시나리오를 시뮬레이션하는가

배포 요청(티켓) 생성부터 파이프라인 실행까지의 전체 사이클:

1. **티켓 생성**: 어떤 소스(Git 저장소, Nexus 아티팩트, Harbor 이미지)를 배포할지 선택
2. **파이프라인 실행**: 소스 유형에 따라 Clone → Build → Deploy 스텝이 자동 생성되고 순차 실행
3. **실시간 모니터링**: SSE로 각 스텝의 진행 상태를 브라우저에 실시간 전달
4. **실패 복구**: 스텝 실패 시 SAGA 패턴으로 완료된 스텝을 역순 보상

### 기술 선택 이유

| 기술 | 이유 |
|------|------|
| **Redpanda** | Kafka API 호환이면서 JVM 없이 단일 바이너리로 실행되므로 로컬 개발이 가볍다. Schema Registry도 내장이라 별도 컨테이너가 불필요하다. |
| **Avro** | 스키마 진화(schema evolution)를 지원하고, 바이너리 직렬화로 메시지 크기가 작다. 실무에서 Kafka + Avro는 사실상 표준 조합이다. |
| **Spring Boot 3.4 + MyBatis** | TPS 실무 스택과 동일. JPA 대신 MyBatis를 쓰는 이유는 복잡한 쿼리 제어가 필요한 엔터프라이즈 환경의 관례를 따르기 위함이다. |
| **React 19 + TanStack Query** | 서버 상태 관리에 특화된 조합. SSE와의 연동에서 캐시 무효화(invalidation)로 UI를 갱신하는 패턴을 실습한다. |

### 기술 스택 버전

| 기술 | 버전 |
|------|------|
| Java | 21 (Gradle toolchain 강제) |
| Spring Boot | 3.4.0 |
| Apache Avro | 1.12.1 |
| Redpanda | v24.3.1 |
| Redpanda Connect | 4.43.0 |
| React | 19 |
| Vite | 6 |
| PostgreSQL | 16-alpine |
| Springwolf | 1.21.0 |

---

## 2. 시스템 아키텍처

### 전체 구성도

```
[Frontend: React 19 + Vite 6]
  │
  ├── REST API ─────────────── [Spring Boot 3.4 :8080]
  │                               ├── ticket/       (CRUD)
  │                               ├── pipeline/     (실행 엔진)
  │                               ├── supporttool/  (도구 관리)
  │                               ├── webhook/      (웹훅 수신)
  │                               └── common/       (outbox, idempotency)
  │
  ├── SSE Stream ───────────── PipelineSseConsumer → SseEmitterRegistry
  │
  [Redpanda :29092]             [PostgreSQL :25432]
  │  6개 토픽                     8개 테이블
  │
  [Redpanda Connect :24195]
  │  HTTP↔Kafka 브릿지
  │
  [Jenkins :29080] [GitLab :29180] [Nexus :28881] [Registry :25050]
```

### 컨테이너 구성

**docker-compose.yml** (핵심 — 항상 필요):

| 서비스 | 이미지 | 포트 | 메모리 | 역할 |
|--------|--------|------|--------|------|
| redpanda | redpanda:v24.3.1 | 29092, 28081, 29644 | - | 메시지 브로커 + Schema Registry + Admin |
| console | console:v2.8.0 | 28080 | 256M | 토픽/메시지 모니터링 UI |
| connect | connect:4.43.0 | 24195 | 128M | HTTP↔Kafka 브릿지 |
| postgres | postgres:16-alpine | 25432 | 256M | 메인 DB |

> 상세: [infra/01-docker-compose.md](infra/01-docker-compose.md)

### 데이터 흐름 요약

| 방향 | 흐름 | 미들웨어 |
|------|------|---------|
| **Event (외부→앱)** | Jenkins/GitLab → Connect HTTP :4195 → `playground.webhook.inbound` → WebhookEventConsumer | Connect가 HTTP→Kafka 변환 |
| **Command (앱→외부)** | PipelineEngine → Outbox → `playground.pipeline.commands` → Connect → Jenkins REST | Connect가 Kafka→HTTP 변환 |
| **Query (앱→외부)** | ToolRegistry → Jenkins/GitLab/Nexus REST API 직접 호출 | 미들웨어 없음 (동기) |
| **Domain Event** | TicketService → Outbox → `playground.ticket.events` → PipelineEngine | Outbox 폴러가 DB→Kafka 변환 |
| **Realtime** | `playground.pipeline.events` → PipelineSseConsumer → SseEmitterRegistry → 브라우저 | SSE 단방향 스트리밍 |

---

## 3. 적용 패턴 요약

| # | 패턴 | 핵심 한 줄 | 상세 |
|---|------|-----------|------|
| 1 | **202 Accepted** | 긴 작업은 즉시 응답 + 추적 URL 제공 | [patterns.md](patterns.md#1-202-accepted) |
| 2 | **SAGA Orchestrator** | PipelineEngine이 오케스트레이터, 실패 시 완료 스텝 역순 보상 | [patterns.md](patterns.md#2-saga-orchestrator) |
| 3 | **Transactional Outbox** | DB 트랜잭션 + 이벤트 발행 원자성, 500ms 폴링 | [patterns.md](patterns.md#3-transactional-outbox) |
| 4 | **SSE 실시간 알림** | 서버→클라이언트 단방향 스트리밍, TanStack Query 캐시 무효화 | [patterns.md](patterns.md#4-sse-실시간-알림) |
| 5 | **Break-and-Resume** | 웹훅 대기 시 스레드 해제, CAS로 경쟁 조건 방지 | [patterns.md](patterns.md#5-break-and-resume) |
| 6 | **Redpanda Connect** | HTTP↔Kafka 브릿지 (전송만, 비즈니스 로직 없음) | [patterns.md](patterns.md#6-redpanda-connect) |
| 7 | **토픽/메시지 설계** | 도메인별 토픽, EventMetadata 공통 스키마, CloudEvents | [patterns.md](patterns.md#7-토픽메시지-설계) |
| 8 | **Adapter/Fallback** | 외부 시스템별 어댑터 분리, ToolRegistry 기반 동적 해석 | [patterns.md](patterns.md#8-adapterfallback) |
| 9 | **Idempotency** | (correlationId, eventType) 복합 키, preemptive acquire | [patterns.md](patterns.md#9-idempotency) |
| 10 | **Dynamic Connector** | 런타임 Connect 스트림 등록/삭제, DB 영속화 | [patterns.md](patterns.md#10-dynamic-connector-management) |

---

## 4. 패키지 구조

### 1. ticket (배포 티켓 도메인)

| 패키지 | 구성 |
|--------|------|
| **ticket/domain** | Ticket, TicketSource, TicketStatus (DRAFT → READY → DEPLOYING → DEPLOYED/FAILED) |
| **ticket/mapper** | TicketMapper, TicketSourceMapper (MyBatis SQL 매핑) |
| **ticket/service** | TicketCommandService (생성/수정/삭제 + Outbox), TicketQueryService (조회) |
| **ticket/api** | TicketController (REST API) |
| **ticket/dto** | TicketCreateRequest/Response, TicketUpdateRequest/Response |

### 2. pipeline (배포 파이프라인 도메인)

| 패키지 | 구성 |
|--------|------|
| **pipeline/domain** | PipelineExecution, PipelineStep, PipelineStatus, StepEvent |
| **pipeline/mapper** | PipelineExecutionMapper, PipelineStepMapper |
| **pipeline/service** | PipelineCommandService (202 Accepted), PipelineQueryService |
| **pipeline/engine** | PipelineEngine (SAGA Orchestrator), SagaCompensator, WebhookTimeoutChecker |
| **pipeline/event** | PipelineEventConsumer, PipelineEventProducer, PipelineCommandProducer |
| **pipeline/sse** | PipelineSseConsumer, SseEmitterRegistry |
| **pipeline/dag/domain** | PipelineDefinition, PipelineJob, PipelineJobMapping, FailurePolicy |
| **pipeline/dag/engine** | DagExecutionCoordinator, DagExecutionState, DagValidator |
| **pipeline/dag/event** | DagEventProducer |
| **pipeline/dag/service** | PipelineDefinitionService |
| **pipeline/dag/api** | PipelineDefinitionController |
| **pipeline/dag/mapper** | PipelineDefinitionMapper, PipelineJobMapper, PipelineJobMappingMapper |

### 3. common (공통 인프라)

| 패키지 | 구성 |
|--------|------|
| **common/outbox** | OutboxEvent, OutboxMapper (FOR UPDATE SKIP LOCKED), OutboxPoller (500ms) |
| **common/idempotency** | IdempotentEventRecord, IdempotencyMapper, IdempotencyFilter |
| **common/dto** | ApiResponse<T>, ErrorResponse |

### 4. 기타 도메인

| 패키지 | 구성 |
|--------|------|
| **webhook** | WebhookEventConsumer (Kafka Consumer), JenkinsWebhookHandler |
| **audit** | AuditEventListener (모든 도메인 이벤트 → playground.audit.events) |
| **adapter** | Jenkins, GitLab, Nexus, Registry 어댑터 (인터페이스 기반) |
| **supporttool** | 외부 도구 연결 정보 런타임 관리 (DB 기반, 앱 재시작 불필요) |

> 상세: [architecture.md](architecture.md)

---

## 5. 개발 환경 & 실행 방법

### 사전 요구

- Java 21 (Corretto 21 권장)
- Docker + Docker Compose
- Node.js (프론트엔드)
- Yarn 4.x (패키지 매니저)

### make 명령어

| 명령어 | 설명 |
|--------|------|
| `make infra` | Core 인프라 시작 (Redpanda, PostgreSQL, Console, Connect) |
| `make infra-all` | 전체 인프라 (Core + Jenkins, GitLab, Nexus, Registry) |
| `make infra-down` | 전체 인프라 중지 |
| `make backend` | Spring Boot 실행 |
| `make frontend` | React 개발 서버 실행 |
| `make build` | 백엔드 빌드 (테스트 제외) |
| `make test` | 백엔드 테스트 |
| `make setup-all` | 미들웨어 셋업 (GitLab/Nexus/Registry/Jenkins에 샘플 데이터 등록) |
| `make demo-deploy` | 데모 시나리오 실행 (티켓 생성 → 파이프라인 → 결과 확인) |
| `make monitoring` | Grafana + Loki + Tempo + Alloy + Prometheus |

### 포트 맵

| 서비스 | 포트 | URL |
|--------|------|-----|
| Spring Boot | 8080 | http://localhost:8080 |
| Frontend (Vite) | 5173 | http://localhost:5173 |
| Redpanda Console | 28080 | http://localhost:28080 |
| AsyncAPI (Springwolf) | 8080 | http://localhost:8080/springwolf/asyncapi-ui.html |
| Jenkins | 29080 | http://localhost:29080 (admin/admin) |
| GitLab | 29180 | http://localhost:29180 (root/playground1234!) |
| Nexus | 28881 | http://localhost:28881 |
| Registry UI | 25051 | http://localhost:25051 |

### 데모 시나리오

```bash
# 1. 인프라 시작
make infra-all

# 2. 미들웨어 셋업 (샘플 데이터)
make setup-all

# 3. 백엔드 + 프론트엔드 (별도 터미널)
make backend
make frontend

# 4. 브라우저에서 http://localhost:5173
#    도구 → 티켓 생성 → 파이프라인 시작 → SSE로 실시간 모니터링
```

> 상세: [demo-script.md](demo-script.md)

---

## 6. 설정 가능한 항목

| 항목 | 기본값 | 설정처 |
|------|--------|--------|
| Outbox 폴링 주기 | 500ms | application.yml |
| Kafka Consumer Group | playground | kafka-defaults.yml |
| Avro Schema Registry | http://localhost:28081 | application.yml |
| SSE 타임아웃 | 1시간 | SseEmitterRegistry |
| Webhook 타임아웃 | 5분 | WebhookTimeoutChecker.TIMEOUT_MINUTES |
| 타임아웃 체크 주기 | 30초 | WebhookTimeoutChecker @Scheduled fixedDelay |
| Docker 네트워크 | playground-net | docker-compose.yml |
| Connect webhook 엔드포인트 | :4195/jenkins-webhook/webhook/jenkins | jenkins-webhook.yaml |
| Connect jenkins-command | kafka → http://jenkins:8080 | jenkins-command.yaml |

---

## 7. Redpanda 활용 패턴

| 패턴 | 이 프로젝트의 적용 | 실무 확장 |
|------|-------------------|----------|
| **Schema Registry 내장 활용** | Avro 스키마를 ByteArray로 직접 관리 | 프로덕션에서는 자동 serde + 호환성 검증 활용 |
| **Redpanda Connect 브릿지** | Jenkins HTTP → Kafka 토픽 변환 | 외부 시스템(Slack, GitHub 등) webhook을 Kafka로 통합 |
| **Console로 이벤트 추적** | 데모 시 토픽 메시지 실시간 확인 | 장애 시 메시지 내용/순서 확인, 컨슈머 래그 모니터링 |
| **rpk로 운영** | `rpk topic list`, `rpk group describe` | 토픽 설정 변경, 오프셋 리셋, 파티션 재배분 |
| **단일 바이너리 경량 인프라** | Docker Compose 1개 컨테이너로 Kafka+SR 대체 | CI/CD에서 Testcontainers와 조합 |
