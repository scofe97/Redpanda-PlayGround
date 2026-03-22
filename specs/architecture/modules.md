# 모듈 구조

`settings.gradle`에 선언된 Gradle 모듈은 4개다. 각 모듈은 단방향 의존성 규칙을 따르며, 상위 모듈이 하위 모듈에만 의존한다.

## 모듈 개요

| 모듈 | 역할 | 주요 패키지 |
|------|------|-------------|
| `common` | 모든 모듈이 공유하는 유틸리티. 공통 DTO, 예외 처리, MyBatis 설정, 멱등성 체크 인프라 포함 | `com.study.playground.common` |
| `common-kafka` | Kafka/Redpanda 인프라 전담. 설정, 직렬화, Outbox 폴러, DLQ 라우터, 트레이싱 인터셉터 포함 | `com.study.playground.kafka` |
| `pipeline` | DAG 엔진과 파이프라인 실행 엔진. Jenkins 클라이언트, SSE 스트리밍, SAGA 보상 로직 포함 | `com.study.playground.pipeline` |
| `app` | Spring Boot 애플리케이션 진입점. REST API 컨트롤러, Kafka 이벤트 컨슈머, 도메인 오케스트레이션 담당 | `com.study.playground` |

## 의존성 방향

```
app → pipeline → common-kafka → common
```

단방향 규칙이다. `common`은 아무것도 의존하지 않으며, `app`은 모든 하위 모듈을 사용할 수 있다. 역방향 의존(`common`이 `pipeline`을 참조하는 등)은 ArchUnit 테스트가 빌드 시점에 차단한다.

## 도메인 → 모듈 매핑

| 도메인 | 모듈 | 패키지 | 비고 |
|--------|------|--------|------|
| ticket | `app` | `com.study.playground.ticket` | CRUD, 소스(GIT/NEXUS/HARBOR) 관리 |
| pipeline definition | `app` | `com.study.playground.pipeline` | 파이프라인 정의, 실행 시작 API |
| pipeline execution / DAG | `pipeline` | `com.study.playground.pipeline.dag` | DAG 실행 엔진, 의존성 그래프, 실패 정책 |
| pipeline engine (레거시) | `pipeline` | `com.study.playground.pipeline.engine` | 순차 실행, SAGA 보상 |
| preset | `app` | `com.study.playground.preset` | 미들웨어 프리셋 CRUD |
| supporttool | `app` | `com.study.playground.supporttool` | Jenkins/Nexus/Harbor 연결 관리 |
| adapter | `app` | `com.study.playground.adapter` | 외부 시스템 어댑터 DTO |
| connector | `app` | `com.study.playground.connector` | Redpanda Connect 스트림 동적 관리 |
| webhook | `app` | `com.study.playground.webhook` | 외부 웹훅 수신/핸들러 라우팅 |
| audit | `app` | `com.study.playground.audit` | 감사 이벤트 발행 |
| Jenkins client | `pipeline` | `com.study.playground.pipeline.jenkins` | Jenkins REST API 호출 |
| SSE | `pipeline` | `com.study.playground.pipeline.sse` | 실시간 실행 상태 스트리밍 |
| Kafka 설정/직렬화 | `common-kafka` | `com.study.playground.kafka.config` | Producer/Consumer 빈 설정, Avro 직렬화 |
| Outbox | `common-kafka` | `com.study.playground.kafka.outbox` | DB→Kafka 폴러 (500ms 주기) |
| DLQ | `common-kafka` | `com.study.playground.kafka.dlq` | 실패 메시지 Dead Letter Queue 라우팅 |
| 트레이싱 | `common-kafka` | `com.study.playground.kafka.tracing` | Kafka 헤더 기반 분산 추적 인터셉터 |
| 공통 DTO / 예외 | `common` | `com.study.playground.common.dto` | ApiResponse, ErrorCode, 공통 예외 |
| MyBatis 설정 | `common` | `com.study.playground.common.mybatis` | 타입 핸들러, 인터셉터 |
| 멱등성 | `common` | `com.study.playground.common.idempotency` | `processed_event` 테이블 기반 중복 수신 차단 |

## 도메인 격리 규칙 (ArchUnit)

ArchUnit 테스트가 빌드 시점에 아래 규칙을 검증한다. 위반 시 컴파일은 통과하지만 테스트가 실패한다.

| 규칙 | 내용 |
|------|------|
| ticket ↔ pipeline 직접 참조 금지 | 두 도메인은 이벤트(Kafka)로만 통신한다 |
| 예외 1 | `ticket.event` 패키지는 Avro 이벤트 클래스 참조 허용 (이벤트 소비자 역할) |
| 예외 2 | `pipeline.service`는 ticket 도메인 참조 허용 (파이프라인 시작 시 티켓/소스 조회) |
| controller(api) 의존 범위 | `service`, `dto`, `domain`, `sse`, `common` 패키지만 의존 가능 |
| mapper 접근 제한 | `service`, `mapper`, `event`, `engine` 패키지에서만 접근 가능 |

## 모듈별 주요 클래스

### common

| 클래스 | 역할 |
|--------|------|
| `ApiResponse<T>` | 표준 HTTP 응답 래퍼 |
| `ErrorCode` | 에러 코드 열거형 |
| `PlaygroundException` | 도메인 예외 기반 클래스 |

### common-kafka

| 클래스 | 역할 |
|--------|------|
| `Topics` | 토픽 이름 상수 (`playground.pipeline.commands.execution` 등 9개) |
| `OutboxPoller` | 500ms 주기로 `outbox_event` 테이블을 폴링해 Kafka에 발행 |
| `DlqRouter` | 처리 실패 메시지를 `playground.dlq`로 라우팅 |
| `AvroSerializer` / `AvroDeserializer` | Schema Registry 연동 Avro 직렬화/역직렬화 |

### pipeline

| 클래스 | 역할 |
|--------|------|
| `DagExecutionCoordinator` | DAG 의존성 순서로 Job 병렬 실행 조율 |
| `DagValidator` | 순환 참조(cycle) 탐지 |
| `PipelineEngine` | 레거시 순차 실행 엔진, SAGA Orchestrator |
| `SagaCompensator` | 실패 시 완료된 스텝 역순 보상 |
| `JenkinsClient` | Jenkins REST API 호출 (빌드 트리거, 상태 조회) |
| `SseEmitterRegistry` | SSE 연결 관리, 클라이언트별 이벤트 스트리밍 |

### app

| 패키지 | 대표 클래스 | 역할 |
|--------|-------------|------|
| `ticket.api` | `TicketController` | 티켓 CRUD REST API |
| `pipeline.api` | `PipelineController` | 파이프라인 실행 API |
| `webhook.handler` | `JenkinsWebhookHandler` | Jenkins 콜백 파싱 → PipelineEngine 재개 |
| `connector.service` | `ConnectorService` | Redpanda Connect 스트림 CRUD |
| `preset.service` | `PresetService` | 프리셋 CRUD |
| `audit` | `AuditEventPublisher` | 감사 이벤트 Outbox 발행 |
