# 이벤트 아키텍처

모든 도메인 간 통신은 Kafka(Redpanda) 토픽을 통한 비동기 이벤트로 이루어진다. 동기 호출은 같은 모듈 내부에서만 허용되며, 도메인 경계를 넘는 순간 반드시 이벤트를 경유한다.

## Kafka 토픽 목록

토픽 이름은 `common-kafka` 모듈의 `Topics.java`에 상수로 정의되어 있다. 코드에서 문자열 리터럴을 직접 쓰지 않고 `Topics.PIPELINE_CMD_EXECUTION` 형태로 참조한다.

| 토픽 | 상수 | 파티션 | 보존 | 키 | 직렬화 | 용도 |
|------|------|--------|------|----|--------|------|
| `playground.pipeline.commands.execution` | `PIPELINE_CMD_EXECUTION` | 3 | 7일 | executionId | Avro | 파이프라인 실행 명령 (레거시 엔진 트리거) |
| `playground.pipeline.commands.jenkins` | `PIPELINE_CMD_JENKINS` | 3 | 7일 | jobName | Avro | Jenkins 빌드 트리거 커맨드 |
| `playground.pipeline.events.step-changed` | `PIPELINE_EVT_STEP_CHANGED` | 3 | 7일 | executionId | Avro | 개별 스텝 상태 전이 이벤트 |
| `playground.pipeline.events.completed` | `PIPELINE_EVT_COMPLETED` | 3 | 7일 | executionId | Avro | 파이프라인 전체 완료/실패 이벤트 |
| `playground.pipeline.events.dag-job` | `PIPELINE_EVT_DAG_JOB` | 3 | 7일 | executionId | Avro | DAG Job 단위 상태 이벤트 |
| `playground.ticket.events` | `TICKET_EVENTS` | 3 | 7일 | ticketId | Avro | 티켓 생성/수정 이벤트 |
| `playground.webhook.inbound` | `WEBHOOK_INBOUND` | 2 | 3일 | webhookSource | JSON | Connect가 수신한 외부 웹훅 포워딩 |
| `playground.audit.events` | `AUDIT_EVENTS` | 1 | 30일 | userId | Avro | 감사 추적 이벤트 |
| `playground.dlq` | `DLQ` | 1 | 30일 | - | - | Dead Letter Queue (처리 실패 메시지) |

## Avro 스키마 관리

모든 Avro 토픽은 Schema Registry에서 스키마를 관리한다. 스키마 진화 정책은 **BACKWARD 호환성**이다. 새 필드 추가 시 반드시 default 값을 지정해야 하며, 기존 필드 삭제나 타입 변경은 허용되지 않는다.

| 원칙 | 내용 |
|------|------|
| 진화 정책 | BACKWARD — 새 스키마로 작성한 메시지를 이전 컨슈머가 읽을 수 있어야 함 |
| 필드 추가 | default 값 필수 |
| 필드 삭제 | 금지 (별도 major 버전 계획 필요) |
| 타입 변경 | 금지 |
| 등록 위치 | `common-kafka` 모듈 `src/main/avro/` |

## Redpanda Connect 파이프라인

Connect는 **전송(transport)만** 담당한다. HTTP 수신 → Kafka 포워딩, Kafka → Jenkins REST API 호출 같은 프로토콜 브릿지 역할에 한정되며, 도메인 로직·DB 접근·상태 전이는 일절 수행하지 않는다.

Connect는 단일 포트 **4195**로 모든 HTTP 입력을 수신한다. 경로 프리픽스로 파이프라인을 구분한다.

| 파이프라인 | 방향 | 입력 | 출력 | HTTP 경로 |
|------------|------|------|------|-----------|
| `jenkins-webhook` | HTTP → Kafka | POST 요청 (Jenkins 빌드 완료 콜백) | `playground.webhook.inbound` 토픽 | `/jenkins-webhook/webhook/jenkins` |
| `gitlab-webhook` | HTTP → Kafka | POST 요청 (GitLab 이벤트) | `playground.webhook.inbound` 토픽 | `/gitlab-webhook/webhook/gitlab` |
| `pipeline-commands` | Kafka → HTTP | `playground.pipeline.commands.jenkins` 토픽 | Jenkins REST API (`buildWithParameters`) | - |

### Connect vs Spring 역할 분리

```
[Jenkins] --HTTP POST--> [Connect: 4195] --토픽--> [Spring: 비즈니스 로직]
                          (단순 포워딩)               (DB, 상태머신, SAGA)
```

Spring은 Kafka Consumer로 메시지를 소비한 후, 도메인 로직을 실행한다. 두 계층이 명확히 분리되어 있으므로 Connect YAML 변경이 Spring 코드에 영향을 주지 않는다.

## Observability — 로그 마커 규칙

Grafana 대시보드 정규식은 아래 마커 패턴을 기준으로 로그를 파싱한다. **마커명 또는 필드 순서를 변경하면 Grafana regexp도 함께 수정해야 한다.**

| 마커 | 발행 클래스 | 용도 | Grafana 패널 |
|------|-------------|------|--------------|
| `[StepChanged]` | `PipelineEventProducer`, `DagEventProducer` | Job 상태 변경 시 발행 | Timeline 패널 |
| `[ExecutionCompleted]` | `PipelineEventProducer` | 파이프라인 전체 완료/실패 시 발행 | Status 패널 |
| `[DagJobDispatched]` | `DagEventProducer` | DAG Job 실행 시작 시 발행 | Logs 패널 |

로그 예시:
```
INFO [StepChanged] executionId=abc123 stepOrder=2 status=SUCCESS jobName=BUILD
INFO [ExecutionCompleted] executionId=abc123 status=COMPLETED totalJobs=3
INFO [DagJobDispatched] executionId=abc123 jobId=42 jobName=DEPLOY depends=[]
```

## 이벤트 흐름

### 티켓 기반 파이프라인 실행 흐름 (레거시)

클라이언트가 `POST /api/tickets/{id}/pipeline/start`를 호출하면, `PipelineService`가 DB에 실행 레코드를 생성하고 `PIPELINE_CMD_EXECUTION` 토픽에 명령을 발행한다(Outbox 경유). 응답은 202 ACCEPTED이며 SSE trackingUrl을 포함한다.

`OutboxPoller`(500ms)가 `outbox_event` 테이블을 폴링해 Kafka에 실제 발행한다. `PipelineEventConsumer`가 명령을 소비하면 `PipelineEngine.execute()`가 비동기 스레드풀에서 실행된다. Jenkins 빌드가 필요한 스텝은 `PIPELINE_CMD_JENKINS` 토픽에 커맨드를 발행하고 스레드를 해제한 뒤 웹훅 콜백을 기다린다(`WAITING_WEBHOOK` 상태).

Jenkins 빌드가 완료되면 Connect가 HTTP 콜백을 `WEBHOOK_INBOUND` 토픽으로 포워딩한다. `WebhookEventConsumer`가 소비해 `PipelineEngine.resumeAfterWebhook()`을 호출하면, CAS(Compare-and-Swap)로 상태를 전이하고 다음 스텝을 실행한다. 전체 성공 시 `PIPELINE_EVT_COMPLETED` 이벤트를 발행하고, 실패 시 `SagaCompensator`가 완료된 스텝을 역순으로 보상한다.

### DAG 파이프라인 실행 흐름 (Phase 3)

```
클라이언트  →  POST /api/pipelines/{id}/execute
               ↓
         DAG 검증 (순환 참조 탐지, DagValidator)
               ↓
         PipelineExecution + JobExecutions 생성 (DB)
               ↓
         DagExecutionCoordinator.startExecution() [비동기]
               ↓
         의존성 없는 루트 Job 병렬 실행
               ↓
         Job 완료 → 후속 Job 의존성 충족 여부 확인
               ↓
         모든 Job 완료 → PIPELINE_EVT_COMPLETED 발행 (Avro)
```

Job 실패 시 `FailurePolicy`에 따라 처리 방식이 달라진다.

| 실패 정책 | 동작 |
|-----------|------|
| `STOP_ALL` (기본) | 실행 중인 나머지 Job을 모두 취소 |
| `SKIP_DOWNSTREAM` | 실패한 Job의 하위 Job만 스킵, 독립 브랜치는 계속 진행 |
| `FAIL_FAST` | 즉시 전체 파이프라인 실패 처리 |

### Outbox 패턴 (모든 이벤트 발행 공통)

모든 이벤트 발행은 DB 트랜잭션과 원자성을 보장하기 위해 Outbox 패턴을 사용한다. `EventPublisher`가 `outbox_event` 테이블에 INSERT하고, `OutboxPoller`가 500ms마다 미발행 레코드를 Kafka에 전송한다. Kafka 발행 성공 후 레코드를 발행 완료 처리한다. 이 구조 덕분에 DB 커밋과 Kafka 발행이 동시에 실패하는 일이 없다.

### 멱등성 보장

`processed_event` 테이블에 `event_id`(ce_id)를 단일 키로 저장해 중복 소비를 차단한다. 컨슈머는 처리 전 INSERT를 먼저 시도하고(Preemptive acquire), 중복 키 예외가 발생하면 이미 처리된 이벤트로 간주하고 스킵한다.
