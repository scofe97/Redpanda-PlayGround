# Redpanda Playground — 프로젝트 스펙

## 개발 목적

TPS(CI/CD 플랫폼) 실무에서 사용하는 이벤트 기반 아키텍처 패턴을 학습용으로 축소 구현한 PoC 프로젝트이다.
Redpanda(Kafka 호환 메시지 브로커)를 중심으로, 비동기 파이프라인 실행/웹훅 연동/SSE 실시간 알림 등
실무 수준의 분산 시스템 패턴을 직접 구현하고 검증하는 것이 목표이다.

## 기술 스택

| 영역 | 기술 | 버전 |
|------|------|------|
| **Language** | Java | 17 |
| **Framework** | Spring Boot | 3.4.0 |
| **ORM** | MyBatis | 3.0.3 |
| **DB** | PostgreSQL | latest |
| **Migration** | Flyway | (Spring Boot 관리) |
| **Message Broker** | Redpanda (Kafka 호환) | v25.x |
| **Serialization** | Apache Avro | 1.12.1 |
| **Schema Registry** | Redpanda 내장 | - |
| **Stream Processing** | Redpanda Connect | - |
| **AsyncAPI Doc** | Springwolf | 1.21.0 |
| **Frontend** | React + Vite + TanStack Query | - |
| **Build** | Gradle (Groovy DSL) | 8.10 |
| **Test** | JUnit 5 + Mockito + ArchUnit | - |

> **Java 버전 주의**: 반드시 Java 17로 빌드/테스트해야 한다.
> `JAVA_HOME`을 Corretto 17 등으로 설정할 것.

## 아키텍처

### 도메인 (5개)

| 도메인 | 패키지 | 역할 |
|--------|--------|------|
| **ticket** | `ticket/` | 배포 티켓 CRUD, 소스(GIT/NEXUS/HARBOR) 관리 |
| **pipeline** | `pipeline/` | 파이프라인 실행 엔진, SAGA 보상, SSE 스트리밍 |
| **webhook** | `webhook/` | 외부 시스템(Jenkins) 웹훅 수신/처리 |
| **audit** | `common/audit/` | 감사 이벤트 발행 |
| **supporttool** | `supporttool/` | 지원 도구(Jenkins/Nexus/Harbor) 연결 관리 |
| **connector** | `connector/` | Redpanda Connect 스트림 동적 관리 |

### 도메인 격리 규칙 (ArchUnit)

- `ticket` ↔ `pipeline`: 직접 의존 금지 (이벤트로만 통신)
  - 예외: `ticket.event` → Avro 이벤트 클래스 참조 허용 (이벤트 소비자)
  - 예외: `pipeline.service` → ticket 도메인 참조 허용 (파이프라인 시작 시 티켓/소스 조회)
- `controller(api)` → `service`, `dto`, `domain`, `sse`, `common` 패키지만 의존 가능
- `mapper` → `service`, `mapper`, `event`, `engine` 에서만 접근 가능

### Kafka 토픽 (6개)

토픽 이름은 `common-kafka` 모듈의 `Topics.java`에 `static final String` 상수로 정의되어 있다.
하드코딩된 토픽 문자열 대신 `Topics.PIPELINE_COMMANDS` 등으로 참조한다.

| 토픽 | 상수 | 용도 | 직렬화 |
|------|------|------|--------|
| `playground.pipeline.commands` | `Topics.PIPELINE_COMMANDS` | 파이프라인 실행 커맨드 | Avro |
| `playground.pipeline.events` | `Topics.PIPELINE_EVENTS` | 파이프라인 스텝/완료 이벤트 | Avro |
| `playground.ticket.events` | `Topics.TICKET_EVENTS` | 티켓 생성 이벤트 | Avro |
| `playground.webhook.inbound` | `Topics.WEBHOOK_INBOUND` | 외부 웹훅 수신 | JSON |
| `playground.audit.events` | `Topics.AUDIT_EVENTS` | 감사 이벤트 | Avro |
| `playground.dlq` | `Topics.DLQ` | Dead Letter Queue | - |

### 핵심 패턴

#### 1. Transactional Outbox
모든 이벤트는 `EventPublisher` → `outbox_event` 테이블 INSERT → `OutboxPoller`(500ms 폴링) → Kafka 발행.
DB 트랜잭션과 이벤트 발행의 원자성을 보장한다.

#### 2. Idempotent Consumer
`processed_event` 테이블에 `(correlationId, eventType)` 복합 키로 중복 수신 차단.
Preemptive acquire 패턴: INSERT 먼저 시도, 실패하면 중복으로 판단.

#### 3. Break-and-Resume (Webhook)
Jenkins 빌드처럼 오래 걸리는 작업은 스레드를 해제하고 웹훅 콜백을 기다린다.
`PipelineEngine.executeFrom()` → `WAITING_WEBHOOK` → `resumeAfterWebhook()` 패턴.
CAS(Compare-and-Swap)로 타임아웃과의 경쟁 조건을 방지한다.

#### 4. SAGA Orchestration (보상 트랜잭션)
`PipelineEngine`이 SAGA Orchestrator 역할. 스텝 실패 시 `SagaCompensator`가
완료된 스텝을 역순으로 보상(compensate)한다.
- 각 `PipelineStepExecutor`는 `execute()` + `compensate()` 메서드 제공
- 보상 실패 시 로그 기록 후 계속 진행 (수동 개입 필요)
- Webhook 실패 시에도 보상 실행

#### 5. Redpanda Connect (Stream Processor) — 전송만 담당
3개의 YAML 파이프라인:
- 웹훅 → Kafka 포워딩 (`jenkins-webhook.yaml`)
- 커맨드 → Jenkins API 호출 (`pipeline-commands.yaml`)
- 에러 → DLQ 라우팅

**Connect vs Spring의 역할 분리**:
Connect는 **전송(transport)** 만 담당한다. HTTP 수신 → Kafka 토픽 포워딩, Kafka → Jenkins REST API 호출 등
프로토콜 변환/라우팅이 전부이다. 도메인 로직, DB 접근, 상태 전이는 일절 하지 않는다.

Spring은 **처리(processing)** 를 담당한다. Kafka Consumer로 메시지를 소비한 후,
비즈니스 로직을 실행한다 (DB 조회, 상태 전이, SAGA 보상 등).

```
[Jenkins] --HTTP POST--> [Connect: HTTP→Kafka 브릿지] --토픽--> [Spring: 비즈니스 로직]
                          (단순 포워딩, 무상태)                   (DB, 상태머신, SAGA)
```

예시: Jenkins 웹훅 흐름
1. Jenkins가 빌드 완료 시 Connect의 HTTP 엔드포인트로 POST
2. Connect가 페이로드를 `playground.webhook.inbound` 토픽으로 포워딩 (여기까지가 Connect 역할)
3. `WebhookEventConsumer`가 토픽에서 소비 → key 기반 소스 라우팅 (JENKINS)
4. `JenkinsWebhookHandler`가 페이로드 파싱 → 멱등성 체크 → `PipelineEngine.resumeAfterWebhook()` 호출
5. CAS 상태 전이 → 다음 스텝 실행 or SAGA 보상 (Spring의 도메인 로직)

**Jenkins 웹훅 발송 전략**: 상세는 `docs/infra/02-jenkins-webhook.md` 참조.
이 PoC는 빌드 스크립트 내 curl로 시뮬레이션하지만, 실무에서는 RunListener 전역 리스너를 검토 중이다.
고객 파이프라인 커스텀과의 비간섭이 핵심 이유다.

#### 6. AsyncAPI (Springwolf)
`@AsyncPublisher` / `@AsyncListener` 어노테이션으로 비동기 API 문서 자동 생성.
`/springwolf/asyncapi-ui.html`에서 확인 가능.

### 파이프라인 실행 흐름

```
[Client] POST /api/tickets/{id}/pipeline/start
    → PipelineService.startPipeline()
        → 티켓 상태 DEPLOYING
        → PipelineExecution + Steps 생성 (DB)
        → PIPELINE_EXECUTION_STARTED 이벤트 발행 (Outbox)
        → 202 ACCEPTED 응답 + SSE trackingUrl

[OutboxPoller] → Kafka publish

[PipelineEventConsumer] 이벤트 수신
    → PipelineEngine.execute() (비동기 스레드풀)
        → Step 1: GIT_CLONE (Jenkins 커맨드 발행 → WAITING_WEBHOOK)
        → [Thread 해제]

[WebhookEventConsumer] Jenkins 콜백 수신
    → PipelineEngine.resumeAfterWebhook()
        → CAS 상태 전이
        → Step 2: BUILD → Step 3: DEPLOY ...
        → 성공: PIPELINE_EXECUTION_COMPLETED 이벤트
        → 실패: SAGA 보상 → FAILED 이벤트

[PipelineSseConsumer] 이벤트 수신 → SSE로 클라이언트 실시간 전달
[TicketStatusEventConsumer] 완료 이벤트 수신 → 티켓 상태 업데이트
```

## 인프라 (Docker Compose)

### docker-compose.yml (핵심)
- **Redpanda**: 메시지 브로커 + Schema Registry
- **Redpanda Console**: 토픽/메시지 모니터링 UI
- **Redpanda Connect**: 스트림 프로세서
- **PostgreSQL**: 메인 DB

### docker-compose.infra.yml (외부 도구)
- **Jenkins**: CI/CD 빌드 서버
- **GitLab**: 소스 코드 저장소
- **Nexus**: 아티팩트 저장소
- **Docker Registry**: 컨테이너 이미지 저장소

## DB 마이그레이션 (Flyway)

| 버전 | 테이블 | 설명 |
|------|--------|------|
| V1 | `ticket`, `ticket_source` | 티켓 및 소스 관리 |
| V2 | `pipeline_execution`, `pipeline_step` | 파이프라인 실행 이력 |
| V3 | `outbox_event` | Transactional Outbox |
| V4 | `processed_event` | 멱등성 보장 (중복 수신 차단) |
| V5 | `support_tool` | 외부 도구 연결 정보 |
| V6 | `outbox_event` | correlation_id 컬럼 추가 |
| V7 | `support_tool` | 기본 도구 시드 데이터 삽입 |
| V8 | `pipeline_step` | step_name 컬럼 길이 확장 |
| V9 | `connector_config` | 동적 Connect 스트림 설정 영속화 |

## 빌드 및 테스트

```bash
# Java 17 설정
export JAVA_HOME=$(/usr/libexec/java_home -v 17)

# 전체 빌드
./gradlew clean build

# 모듈별 빌드
./gradlew :common-kafka:build   # Kafka 인프라 모듈
./gradlew :app:build            # 앱 모듈

# 인프라 실행
docker compose up -d

# 애플리케이션 실행
./gradlew :app:bootRun
```

## 프로젝트 구조 (멀티모듈)

Gradle 멀티모듈 구조로 Kafka 인프라 코드(`common-kafka`)와 비즈니스 로직(`app`)을 분리했다.
`common-kafka`는 `java-library`로 빌드되어 다른 모듈에서 `implementation project(':common-kafka')`로 의존한다.

```
redpanda-playground/
├── settings.gradle          # include 'common-kafka', 'app'
├── build.gradle             # subprojects 공통 설정 (Spring Boot BOM)
├── common-kafka/            # Kafka 인프라 모듈 (재사용 가능)
│   ├── build.gradle         # java-library + avro plugin
│   └── src/main/
│       ├── java/com/study/playground/kafka/
│       │   ├── topic/
│       │   │   ├── Topics.java          # 토픽 이름 상수 6개
│       │   │   └── TopicConfig.java     # NewTopic @Bean 6개
│       │   ├── config/
│       │   │   ├── KafkaProducerConfig.java  # ProducerFactory + KafkaTemplate
│       │   │   └── KafkaErrorConfig.java     # ErrorHandler + DLT
│       │   ├── interceptor/
│       │   │   └── CloudEventsHeaderInterceptor.java
│       │   └── serialization/
│       │       ├── AvroSerializer.java
│       │       └── AvroSerializationException.java
│       └── avro/            # 모든 Avro 스키마 (.avsc)
│           ├── common/      # EventMetadata, PipelineStatus, SourceType
│           ├── pipeline/    # PipelineCommand, PipelineEvent 등
│           ├── audit/       # AuditEvent
│           ├── ticket/      # TicketCreatedEvent
│           └── webhook/     # WebhookEvent
├── app/                     # 비즈니스 로직 모듈
│   ├── build.gradle         # spring-boot + implementation project(':common-kafka')
│   └── src/main/java/com/study/playground/
│       ├── common/          # 공통 (outbox, idempotency, audit, exception, dto)
│       ├── adapter/         # 외부 시스템 어댑터 (Jenkins, Nexus, Registry)
│       ├── ticket/          # 티켓 도메인
│       │   ├── api/         # REST Controller
│       │   ├── domain/      # Entity, Enum
│       │   ├── dto/         # Request/Response DTO
│       │   ├── event/       # Kafka Consumer
│       │   ├── mapper/      # MyBatis Mapper
│       │   └── service/     # Business Logic
│       ├── pipeline/        # 파이프라인 도메인
│       │   ├── api/         # REST Controller + SSE
│       │   ├── domain/      # Entity, Enum
│       │   ├── dto/         # Response DTO
│       │   ├── engine/      # 실행 엔진 (PipelineEngine, SagaCompensator)
│       │   │   └── step/    # 스텝 구현체
│       │   ├── event/       # Kafka Producer/Consumer
│       │   ├── mapper/      # MyBatis Mapper
│       │   ├── service/     # Business Logic
│       │   └── sse/         # SSE Emitter Registry + Consumer
│       ├── webhook/         # 웹훅 도메인
│       └── supporttool/     # 지원 도구 도메인
├── frontend/                # React 프론트엔드
├── connect/                 # Redpanda Connect YAML 설정
├── docker/                  # Docker 설정 및 셋업 스크립트
├── docker-compose.yml       # 핵심 인프라
└── docker-compose.infra.yml # 외부 도구
```

### 모듈 경계 원칙

| 모듈 | 포함 | 이유 |
|------|------|------|
| **common-kafka** | 토픽 상수, NewTopic 빈, Producer/Error 설정, Interceptor, AvroSerializer, Avro 스키마 | 인프라/재사용 가능 — DB 무의존 |
| **app** | EventPublisher, OutboxPoller, DlqConsumer, 모든 Producer/Consumer, 비즈니스 서비스 | DB 의존 또는 비즈니스 로직 |

`common-kafka`는 `app`을 모르며, 역방향 의존은 금지한다. `@SpringBootApplication`이 `com.study.playground`를 스캔하므로
`common-kafka`의 `@Configuration`은 별도 `@Import` 없이 자동 발견된다.

## E2E 테스트 흐름

### 사전 조건

```bash
# 1. 인프라 실행
docker compose up -d
docker compose -f docker-compose.infra.yml up -d

# 2. 앱 실행
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
./gradlew :app:bootRun

# 3. 헬스체크
curl http://localhost:8080/actuator/health
```

### 성공 시나리오 (GIT 소스 → 3스텝 파이프라인)

```bash
# Step 1: 티켓 생성
curl -X POST http://localhost:8080/api/tickets \
  -H 'Content-Type: application/json' \
  -d '{"name":"E2E Test","description":"test","sources":[{"sourceType":"GIT","repoUrl":"https://github.com/test/repo","branch":"main"}]}'
# → 200 OK, status: DRAFT, id: N

# Step 2: 파이프라인 시작
curl -X POST http://localhost:8080/api/tickets/{id}/pipeline/start
# → 200 OK, status: PENDING, executionId: UUID, trackingUrl: /api/tickets/{id}/pipeline/events

# Step 3: 결과 확인 (DB)
# pipeline_execution: status=SUCCESS
# pipeline_step: GIT_CLONE(SUCCESS) → BUILD(SUCCESS) → DEPLOY(SUCCESS)
# ticket: status=DEPLOYED
```

**이벤트 흐름 (성공)**:

```
[Client] POST /api/tickets/{id}/pipeline/start
    │
    ▼
[PipelineService] 티켓 상태 → DEPLOYING, Execution+Steps 생성
    │  PIPELINE_EXECUTION_STARTED 이벤트 → outbox_event INSERT
    │
    ▼
[OutboxPoller] 500ms 폴링 → Kafka 발행 (playground.pipeline.commands)
    │
    ▼
[PipelineEventConsumer] 커맨드 수신 → PipelineEngine.execute() (비동기 스레드풀)
    │
    ├─ Step 1: GIT_CLONE → Jenkins 호출 (또는 Mock 폴백) → SUCCESS
    │    └─ PIPELINE_STEP_CHANGED 이벤트 발행
    │
    ├─ Step 2: BUILD → Jenkins 호출 (또는 Mock 폴백) → SUCCESS
    │    └─ PIPELINE_STEP_CHANGED 이벤트 발행
    │
    ├─ Step 3: DEPLOY → Jenkins 호출 (또는 Mock 폴백) → SUCCESS
    │    └─ PIPELINE_STEP_CHANGED 이벤트 발행
    │
    ▼
[PipelineEngine] 모든 스텝 완료 → Execution status=SUCCESS
    │  PIPELINE_EXECUTION_COMPLETED 이벤트 발행
    │
    ├─▶ [PipelineSseConsumer] → SSE로 클라이언트에 실시간 전달
    └─▶ [TicketStatusEventConsumer] → 티켓 상태 DEPLOYED로 업데이트
```

> **Jenkins 미연결 시**: `support_tool` 테이블에 활성 JENKINS가 없으면 Mock 폴백으로 실행된다.
> 실제 Jenkins 연동 시에는 Break-and-Resume(WAITING_WEBHOOK → 웹훅 콜백) 패턴이 작동한다.

### 실패 시나리오

#### 1. 존재하지 않는 티켓

```bash
curl -X POST http://localhost:8080/api/tickets/999/pipeline/start
# → 404 Not Found
# {"error":{"code":"COMMON_002","message":"티켓을 찾을 수 없습니다: 999","exposure":"PUBLIC"},"success":false}
```

#### 2. 소스가 없는 티켓

```bash
# 소스 없이 티켓 생성
curl -X POST http://localhost:8080/api/tickets \
  -H 'Content-Type: application/json' \
  -d '{"name":"No Source","description":"test"}'

# 파이프라인 시작 시도
curl -X POST http://localhost:8080/api/tickets/{id}/pipeline/start
# → 400 Bad Request
# {"error":{"code":"COMMON_001","message":"소스가 없는 티켓은 배포할 수 없습니다","exposure":"PUBLIC"},"success":false}
```

#### 3. 스텝 실행 중 실패 (SAGA 보상)

```
[PipelineEngine] Step 2 실패
    │
    ▼
[SagaCompensator] 완료된 Step 1을 역순 보상(compensate)
    │  각 StepExecutor.compensate() 호출
    │
    ▼
[PipelineEngine] Execution status=FAILED, error_message 기록
    │  PIPELINE_EXECUTION_COMPLETED(result=FAILED) 이벤트 발행
    │
    └─▶ [TicketStatusEventConsumer] → 티켓 상태 DEPLOY_FAILED로 업데이트
```

> 보상 실패 시 로그 기록 후 계속 진행한다 (수동 개입 필요). Webhook 타임아웃 시에도 SAGA 보상이 실행된다.
