# Redpanda Playground — 프로젝트 스펙

## 개발 목적

TPS(CI/CD 플랫폼) 실무에서 사용하는 이벤트 기반 아키텍처 패턴을 학습용으로 축소 구현한 PoC 프로젝트이다.
Redpanda(Kafka 호환 메시지 브로커)를 중심으로, 비동기 파이프라인 실행/웹훅 연동/SSE 실시간 알림 등
실무 수준의 분산 시스템 패턴을 직접 구현하고 검증하는 것이 목표이다.

## 기술 스택

| 영역 | 기술 | 버전 |
|------|------|------|
| **Language** | Java | 21 |
| **Framework** | Spring Boot | 3.4.0 |
| **ORM** | MyBatis | 3.0.3 |
| **DB** | PostgreSQL | latest (Bitnami Helm) |
| **Migration** | Flyway | (Spring Boot 관리) |
| **Message Broker** | Redpanda (Kafka 호환) | v25.x |
| **Serialization** | Apache Avro | 1.12.1 |
| **Schema Registry** | Redpanda 내장 | - |
| **Stream Processing** | Redpanda Connect | - |
| **AsyncAPI Doc** | Springwolf | 1.21.0 |
| **Frontend** | React 19 + Vite 6 + TanStack Query 5 | - |
| **Build** | Gradle (Groovy DSL) | 8.10 |
| **Test** | JUnit 5 + Mockito + ArchUnit | - |
| **Observability** | OpenTelemetry + Grafana + Loki + Tempo | - |
| **Infra** | K8s (kubeadm) + Helm | GCP 3-node |

> **Java 버전 주의**: 반드시 Java 21로 빌드/테스트해야 한다.
> `JAVA_HOME`을 Corretto 21 등으로 설정할 것. (Makefile이 자동 설정)

## 아키텍처

### 도메인 (6개)

| 도메인 | 모듈 | 패키지 | 역할 |
|--------|------|--------|------|
| **ticket** | `app` | `ticket/` | 배포 티켓 CRUD, 소스(GIT/NEXUS/HARBOR) 관리 |
| **pipeline** | `pipeline` | `pipeline/` | 파이프라인/Job 실행 엔진, SAGA 보상, SSE 스트리밍 |
| **pipeline-dag** | `pipeline` | `pipeline/dag/` | DAG 파이프라인 정의/실행, 의존성 그래프, 실패 정책 |
| **webhook** | `app` | `webhook/` | 외부 시스템(Jenkins) 웹훅 수신/처리 |
| **audit** | `app` | `common/audit/` | 감사 이벤트 발행 |
| **supporttool** | `app` | `supporttool/` | 지원 도구(Jenkins/Nexus/Harbor) 연결 관리 |
| **connector** | `app` | `connector/` | Redpanda Connect 스트림 동적 관리 |
| **preset** | `app` | `preset/` | 미들웨어 프리셋 (역할별 도구 조합) |

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
| `playground.pipeline.evt.completed` | `Topics.PIPELINE_EVT_COMPLETED` | DAG 실행 완료 이벤트 | Avro |
| `playground.ticket.events` | `Topics.TICKET_EVENTS` | 티켓 생성 이벤트 | Avro |
| `playground.webhook.inbound` | `Topics.WEBHOOK_INBOUND` | 외부 웹훅 수신 | JSON |
| `playground.audit.events` | `Topics.AUDIT_EVENTS` | 감사 이벤트 | Avro |
| `playground.dlq` | `Topics.DLQ` | Dead Letter Queue | - |

### 핵심 패턴

#### 1. Transactional Outbox
모든 이벤트는 `EventPublisher` → `outbox_event` 테이블 INSERT → `OutboxPoller`(500ms 폴링) → Kafka 발행.
DB 트랜잭션과 이벤트 발행의 원자성을 보장한다.

#### 2. Idempotent Consumer
`processed_event` 테이블에 `event_id`(ce_id) 단일 키로 중복 수신 차단.
Preemptive acquire 패턴: INSERT 먼저 시도, 실패하면 중복으로 판단.

#### 3. Break-and-Resume (Webhook)
Jenkins 빌드처럼 오래 걸리는 작업은 스레드를 해제하고 웹훅 콜백을 기다린다.
`PipelineEngine.executeFrom()` → `WAITING_WEBHOOK` → `resumeAfterWebhook()` 패턴.
CAS(Compare-and-Swap)로 타임아웃과의 경쟁 조건을 방지한다.

#### 4. SAGA Orchestration (보상 트랜잭션)
`PipelineEngine`이 SAGA Orchestrator 역할. 스텝 실패 시 `SagaCompensator`가
완료된 스텝을 역순으로 보상(compensate)한다.
- 각 `PipelineJobExecutor`는 `execute()` + `compensate()` 메서드 제공
- 보상 실패 시 로그 기록 후 계속 진행 (수동 개입 필요)
- Webhook 실패 시에도 보상 실행

#### 5. DAG Pipeline (Phase 3)
`PipelineDefinition`이 Job들의 DAG(유향 비순환 그래프)를 정의한다.
`DagExecutionCoordinator`가 의존성 순서대로 Job을 실행하며, `DagValidator`가 순환 참조를 감지한다.
- **설정 외부화**: `PipelineProperties`로 max-concurrent-jobs, webhook-timeout 등 관리
- **크래시 복구**: `@PostConstruct`에서 RUNNING 상태 Execution을 감지하고 재개
- **Job 재시도**: Exponential Backoff (2^n초, 최대 3회)
- **실패 정책**: `STOP_ALL` (기본), `SKIP_DOWNSTREAM`, `FAIL_FAST`
- **부분 재시작**: 실패한 Job부터 재실행 API (`POST /api/pipelines/{id}/executions/{execId}/restart`)
- **DAG 이벤트**: Avro 기반 `PIPELINE_EVT_COMPLETED` 토픽으로 실행 결과 발행
- **파라미터 주입**: Job별 `parameter_schema_json` 정의, 실행 시 `parameters_json` 전달 → `ParameterResolver`가 주입

#### 6. Redpanda Connect (Stream Processor) — 전송만 담당
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

#### 7. AsyncAPI (Springwolf)
`@AsyncPublisher` / `@AsyncListener` 어노테이션으로 비동기 API 문서 자동 생성.
`/springwolf/asyncapi-ui.html`에서 확인 가능.

### 파이프라인 실행 흐름

#### 티켓 기반 파이프라인 (레거시)

```
[Client] POST /api/tickets/{id}/pipeline/start
    → PipelineService.startPipeline()
        → 티켓 상태 DEPLOYING
        → PipelineExecution + JobExecutions 생성 (DB)
        → PIPELINE_EXECUTION_STARTED 이벤트 발행 (Outbox)
        → 202 ACCEPTED 응답 + SSE trackingUrl

[OutboxPoller] → Kafka publish

[PipelineEventConsumer] 이벤트 수신
    → PipelineEngine.execute() (비동기 스레드풀)
        → Job 1: GIT_CLONE (Jenkins 커맨드 발행 → WAITING_WEBHOOK)
        → [Thread 해제]

[WebhookEventConsumer] Jenkins 콜백 수신
    → PipelineEngine.resumeAfterWebhook()
        → CAS 상태 전이
        → Job 2: BUILD → Job 3: DEPLOY ...
        → 성공: PIPELINE_EXECUTION_COMPLETED 이벤트
        → 실패: SAGA 보상 → FAILED 이벤트

[PipelineSseConsumer] 이벤트 수신 → SSE로 클라이언트 실시간 전달
[TicketStatusEventConsumer] 완료 이벤트 수신 → 티켓 상태 업데이트
```

#### DAG 파이프라인 (Phase 3)

```
[Client] POST /api/pipelines/{id}/execute
    → PipelineDefinitionService.executePipeline()
        → DAG 검증 (순환 참조 탐지)
        → PipelineExecution + JobExecutions 생성 (의존성 순서)
        → DagExecutionCoordinator.startExecution() (비동기)

[DagExecutionCoordinator]
    → 의존성 없는 루트 Job부터 병렬 실행
    → Job 완료 시 → 후속 Job의 의존성 충족 여부 확인
    → 모든 의존성 충족 → 다음 Job 실행
    → Job 실패 시 → FailurePolicy에 따라 처리
        → STOP_ALL: 나머지 Job 취소
        → SKIP_DOWNSTREAM: 하위 Job 스킵
        → FAIL_FAST: 즉시 전체 실패
    → 전체 완료 → PIPELINE_EVT_COMPLETED 이벤트 발행 (Avro)
```

## 인프라 (K8s — kubeadm 3-node 클러스터)

GCP에 kubeadm으로 구축한 3대 K8s 클러스터에서 운영한다.
모든 인프라 서비스가 K8s로 이관 완료되었다.

### K8s 서비스 (네임스페이스별)

| 네임스페이스 | 서비스 | ClusterIP 포트 | NodePort | 비고 |
|-------------|--------|---------------|----------|------|
| **rp-oss** | Redpanda (Kafka) | 9092 | 31092 | Helm |
| **rp-oss** | Schema Registry | 8081 | 31081 | |
| **rp-oss** | Console | 8080 | 31880 | |
| **rp-oss** | Connect | 4195 | 31195 | webhook: 31197 |
| **rp-oss** | PostgreSQL | 5432 | 30275 | Bitnami Helm |
| **rp-jenkins** | Jenkins | 8080 | 31080 | Helm |
| **rp-mgm** | Grafana | 80 | 30000 | |
| **rp-mgm** | Alloy (OTLP) | 4317/4318 | 30317/30318 | |
| **ingress-nginx** | Ingress | 80/443 | 31292/31726 | |
| **argocd** | ArgoCD | - | 31134 | |

### 앱 연결 (`application-gcp.yml`)

| 서비스 | NodePort | 비고 |
|--------|----------|------|
| Kafka | 31092 | K8s NodePort |
| Schema Registry | 31081 | K8s NodePort |
| PostgreSQL | 30275 | K8s NodePort |
| Connect | 31195 | K8s NodePort |
| OTel (Alloy) | 4318 | K8s (Server 3) |

> `/etc/hosts`에 `34.47.83.38 redpanda-0` 추가 필요 (Kafka advertised listener가 `redpanda-0` 호스트명 반환)

### 로컬 개발 (Docker Compose)

로컬 개발 시에는 `infra/docker/local/` 하위의 Docker Compose 파일을 사용한다.

```bash
make infra        # Core: Redpanda, Console, Connect
make db           # PostgreSQL
make nexus        # Nexus + Registry
make monitoring   # Grafana, Loki, Tempo, Alloy, Prometheus
```

## DB 마이그레이션 (Flyway)

V1~V35까지 35개 마이그레이션. 주요 변경점만 기술한다.

### 기반 테이블 (V1~V9)

| 버전 | 테이블 | 설명 |
|------|--------|------|
| V1 | `ticket`, `ticket_source` | 티켓 및 소스 관리 |
| V2 | `pipeline_execution`, `pipeline_step` | 파이프라인 실행 이력 |
| V3 | `outbox_event` | Transactional Outbox |
| V4 | `processed_event` | 멱등성 보장 (중복 수신 차단) |
| V5 | `support_tool` | 외부 도구 연결 정보 |
| V9 | `connector_config` | 동적 Connect 스트림 설정 |

### 도구 카테고리 분리 (V12~V15)

| 버전 | 설명 |
|------|------|
| V12 | `auth_type` 추가 (BASIC, PRIVATE_TOKEN, NONE) |
| V13 | `category` + `implementation` 분리 (CI_CD_TOOL, VCS, LIBRARY 등) |
| V14 | `middleware_preset`, `preset_entry` 테이블 생성 |
| V15 | 기존 `tool_type` 컬럼 제거 |

### DAG 파이프라인 (V17~V31)

| 버전 | 설명 |
|------|------|
| V17 | `pipeline_definition` 테이블 생성 |
| V18 | `pipeline_job`, `pipeline_job_dependency` 테이블 생성 |
| V20 | `pipeline_step` → `pipeline_job_execution` 리네이밍 |
| V23 | Job을 Pipeline에서 분리 (독립 엔티티), `pipeline_job_mapping` 생성 |
| V25 | `jenkins_script`, `jenkins_status` 추가 (동적 Jenkins Job 생성) |
| V30 | `ticket_id` nullable (Job 단독 실행) |
| V31 | 의존성을 파이프라인별로 범위 지정 |

### Phase 3 기능 (V32~V35)

| 버전 | 설명 |
|------|------|
| V32 | `retry_count` 추가 (Job 재시도) |
| V33 | `failure_policy` 추가 (STOP_ALL/SKIP_DOWNSTREAM/FAIL_FAST) |
| V34 | `parameter_schema_json`, `parameters_json` 추가 |
| V35 | 파라미터 스키마를 definition에서 job으로 이동 |

## 빌드 및 테스트

```bash
# Makefile 사용 (JAVA_HOME 자동 설정)
make build           # 전체 빌드 (테스트 제외)
make test            # 테스트 실행
make backend         # Spring Boot 실행 (GCP 프로필)
make backend-local   # Spring Boot 실행 (로컬 프로필)

# 직접 실행
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./gradlew clean build
./gradlew :app:bootRun                          # 로컬
SPRING_PROFILES_ACTIVE=gcp ./gradlew :app:bootRun  # GCP
```

## 프로젝트 구조 (멀티모듈)

Gradle 4-모듈 구조로 공통 코드(`common`), Kafka 인프라(`common-kafka`),
파이프라인 엔진(`pipeline`), 비즈니스 로직(`app`)을 분리했다.

```
redpanda-playground/
├── settings.gradle          # include 'common', 'common-kafka', 'pipeline', 'app'
├── build.gradle             # subprojects 공통 설정 (Spring Boot BOM, Java 21)
├── Makefile                 # 빌드/실행/인프라 명령어
├── common/                  # 공통 모듈
│   └── src/main/java/com/study/playground/
│       └── common/          # 공통 DTO, 예외, MyBatis 핸들러
├── common-kafka/            # Kafka 인프라 모듈 (재사용 가능)
│   ├── build.gradle         # java-library + avro plugin
│   └── src/main/
│       ├── java/com/study/playground/kafka/
│       │   ├── topic/       # Topics.java (상수), TopicConfig.java (@Bean)
│       │   ├── config/      # Producer, Error 설정
│       │   ├── interceptor/ # CloudEvents 헤더
│       │   ├── outbox/      # Outbox 도메인 (EventPublisher, OutboxPoller)
│       │   └── serialization/ # AvroSerializer
│       └── avro/            # Avro 스키마 (.avsc)
├── pipeline/                # 파이프라인 엔진 모듈
│   ├── build.gradle
│   └── src/main/java/com/study/playground/pipeline/
│       ├── adapter/         # JenkinsAdapter (REST 호출)
│       ├── api/             # JobController, PipelineSseController
│       ├── config/          # PipelineConfig, PipelineProperties
│       ├── dag/             # DAG 파이프라인
│       │   ├── api/         # PipelineDefinitionController
│       │   ├── domain/      # PipelineDefinition, PipelineJob, FailurePolicy
│       │   ├── dto/         # Request/Response DTO
│       │   ├── engine/      # DagExecutionCoordinator, DagValidator
│       │   ├── event/       # DagEventProducer
│       │   ├── mapper/      # MyBatis Mapper
│       │   └── service/     # PipelineDefinitionService
│       ├── domain/          # PipelineExecution, PipelineJobExecution
│       ├── dto/             # JobRequest/Response
│       ├── engine/          # PipelineEngine, SagaCompensator, ParameterResolver
│       │   └── step/        # 스텝 구현체 (Jenkins, Nexus, Registry, Deploy)
│       ├── event/           # Kafka Producer/Consumer
│       ├── jenkins/         # JenkinsOutboxHandler, JenkinsReconciler
│       ├── mapper/          # MyBatis Mapper
│       ├── port/            # ToolRegistryPort (역전된 의존성)
│       ├── service/         # JobService
│       └── sse/             # SSE Emitter Registry + Consumer
├── app/                     # 비즈니스 로직 모듈 (Spring Boot 메인)
│   ├── build.gradle         # spring-boot + implementation project(':pipeline')
│   └── src/main/java/com/study/playground/
│       ├── adapter/         # GitLab, Nexus, Registry 어댑터
│       ├── ticket/          # 티켓 도메인
│       ├── webhook/         # 웹훅 도메인
│       ├── supporttool/     # 지원 도구 도메인
│       ├── connector/       # Connect 스트림 관리
│       ├── preset/          # 미들웨어 프리셋
│       └── PlaygroundApplication.java
├── frontend/                # React 프론트엔드
├── infra/                   # 인프라 (Docker, K8s, ArgoCD, 문서)
│   ├── docker/local/        # 로컬 Docker Compose 파일
│   ├── k8s/                 # Kubernetes Helm 설정
│   └── docs/                # 인프라 문서
└── http/                    # HTTP 요청 파일 (.http)
```

### 모듈 의존성

```
common ← common-kafka ← pipeline ← app
```

| 모듈 | 포함 | 이유 |
|------|------|------|
| **common** | 공통 DTO, 예외, MyBatis 타입 핸들러 | 전 모듈 공유 |
| **common-kafka** | 토픽 상수, Kafka 설정, AvroSerializer, Outbox, Avro 스키마 | Kafka 인프라 — DB 무의존 |
| **pipeline** | 파이프라인 엔진, DAG, Job, SAGA, SSE, Jenkins 연동 | 파이프라인 도메인 독립 |
| **app** | 티켓, 웹훅, 도구, 프리셋, 어댑터, Spring Boot 메인 | 나머지 비즈니스 로직 |

`pipeline`은 `app`을 모르며, `ToolRegistryPort` 인터페이스로 역전된 의존성을 사용한다.

## E2E 테스트 흐름

### 사전 조건 (GCP 환경)

```bash
# 1. GCP 서버 접속
gcloud compute ssh dev-server --zone=asia-northeast3-a

# 2. K8s 서비스 확인
kubectl get pods -n rp-oss
kubectl get pods -n rp-jenkins

# 3. 로컬에서 앱 실행 (GCP 프로필)
make backend
# 또는
SPRING_PROFILES_ACTIVE=gcp ./gradlew :app:bootRun

# 4. 헬스체크
curl http://localhost:8070/actuator/health
```

### 사전 조건 (로컬 환경)

```bash
# 1. 인프라 실행
make infra-all

# 2. 앱 실행
make backend-local

# 3. 헬스체크
curl http://localhost:8070/actuator/health
```

### 성공 시나리오 (GIT 소스 → 3-Job 파이프라인)

```bash
# Step 1: 티켓 생성
curl -X POST http://localhost:8070/api/tickets \
  -H 'Content-Type: application/json' \
  -d '{"name":"E2E Test","description":"test","sources":[{"sourceType":"GIT","repoUrl":"https://github.com/test/repo","branch":"main"}]}'
# → 200 OK, status: DRAFT, id: N

# Step 2: 파이프라인 시작
curl -X POST http://localhost:8070/api/tickets/{id}/pipeline/start
# → 200 OK, status: PENDING, executionId: UUID, trackingUrl: /api/tickets/{id}/pipeline/events

# Step 3: 결과 확인 (DB)
# pipeline_execution: status=SUCCESS
# pipeline_job_execution: GIT_CLONE(SUCCESS) → BUILD(SUCCESS) → DEPLOY(SUCCESS)
# ticket: status=DEPLOYED
```

### DAG 파이프라인 시나리오

```bash
# Step 1: Job 생성
curl -X POST http://localhost:8070/api/jobs \
  -H 'Content-Type: application/json' \
  -d '{"jobName":"clone-job","jobType":"GIT_CLONE","presetId":1}'

# Step 2: Pipeline Definition 생성 (DAG)
curl -X POST http://localhost:8070/api/pipelines \
  -H 'Content-Type: application/json' \
  -d '{"name":"E2E Pipeline","jobs":[{"jobId":1,"executionOrder":1}],"dependencies":[]}'

# Step 3: 파이프라인 실행
curl -X POST http://localhost:8070/api/pipelines/{id}/execute

# Step 4: 실패 시 부분 재시작
curl -X POST http://localhost:8070/api/pipelines/{id}/executions/{execId}/restart
```

**이벤트 흐름 (성공)**:

```
[Client] POST /api/tickets/{id}/pipeline/start
    │
    ▼
[PipelineService] 티켓 상태 → DEPLOYING, Execution+JobExecutions 생성
    │  PIPELINE_EXECUTION_STARTED 이벤트 → outbox_event INSERT
    │
    ▼
[OutboxPoller] 500ms 폴링 → Kafka 발행 (playground.pipeline.commands)
    │
    ▼
[PipelineEventConsumer] 커맨드 수신 → PipelineEngine.execute() (비동기 스레드풀)
    │
    ├─ Job 1: GIT_CLONE → Jenkins 호출 (또는 Mock 폴백) → SUCCESS
    │    └─ PIPELINE_STEP_CHANGED 이벤트 발행
    │
    ├─ Job 2: BUILD → Jenkins 호출 (또는 Mock 폴백) → SUCCESS
    │    └─ PIPELINE_STEP_CHANGED 이벤트 발행
    │
    ├─ Job 3: DEPLOY → Jenkins 호출 (또는 Mock 폴백) → SUCCESS
    │    └─ PIPELINE_STEP_CHANGED 이벤트 발행
    │
    ▼
[PipelineEngine] 모든 Job 완료 → Execution status=SUCCESS
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
curl -X POST http://localhost:8070/api/tickets/999/pipeline/start
# → 404 Not Found
# {"error":{"code":"COMMON_002","message":"티켓을 찾을 수 없습니다: 999","exposure":"PUBLIC"},"success":false}
```

#### 2. 소스가 없는 티켓

```bash
# 소스 없이 티켓 생성
curl -X POST http://localhost:8070/api/tickets \
  -H 'Content-Type: application/json' \
  -d '{"name":"No Source","description":"test"}'

# 파이프라인 시작 시도
curl -X POST http://localhost:8070/api/tickets/{id}/pipeline/start
# → 400 Bad Request
# {"error":{"code":"COMMON_001","message":"소스가 없는 티켓은 배포할 수 없습니다","exposure":"PUBLIC"},"success":false}
```

#### 3. Job 실행 중 실패 (SAGA 보상)

```
[PipelineEngine] Job 2 실패
    │
    ▼
[SagaCompensator] 완료된 Job 1을 역순 보상(compensate)
    │  각 PipelineJobExecutor.compensate() 호출
    │
    ▼
[PipelineEngine] Execution status=FAILED, error_message 기록
    │  PIPELINE_EXECUTION_COMPLETED(result=FAILED) 이벤트 발행
    │
    └─▶ [TicketStatusEventConsumer] → 티켓 상태 DEPLOY_FAILED로 업데이트
```

> 보상 실패 시 로그 기록 후 계속 진행한다 (수동 개입 필요). Webhook 타임아웃 시에도 SAGA 보상이 실행된다.
