# Redpanda Playground — 프로젝트 스펙

> **이 문서는 2026-03-22 기준으로 동결되었습니다.** 최신 스펙은 `specs/` 디렉토리를 참조하세요.
> - 도메인 스펙: `specs/domains/`
> - 아키텍처 스펙: `specs/architecture/`
> - Phase 계획/회고: `specs/phases/`
> - 아키텍처 결정: `specs/decisions/`

## 개발 목적

TPS(CI/CD 플랫폼) 실무에서 사용하는 이벤트 기반 아키텍처 패턴을 학습용으로 축소 구현한 PoC 프로젝트이다.
Redpanda(Kafka 호환 메시지 브로커)를 중심으로, 비동기 파이프라인 실행/웹훅 연동/SSE 실시간 알림 등
실무 수준의 분산 시스템 패턴을 직접 구현하고 검증하는 것이 목표이다.

## 현재 상태 (2026-03-22 기준)

Phase 3까지 완료되었고, K8s 전면 이관(Phase 2)을 거쳐 모든 인프라가 GCP 3-node K8s 클러스터 위에서 운영 중이다. DAG 엔진 고도화(Phase 3)로 크래시 복구, Job 재시도, 실패 정책, 파라미터 주입, 부분 재시작까지 구현이 끝난 상태이며, 프론트엔드는 React Flow 기반 실시간 DAG 시각화와 SAGA 보상 배너를 포함한다.

다음 단계로는 워크플로우 도메인 도입(Phase 4)과 CI/CD 어댑터 추상화(Phase 5)를 계획하고 있다. Phase 4에서는 업무코드 기반 워크플로우가 파이프라인을 참조하는 구조를 만들고, Phase 5에서는 Jenkins 외에 GoCD나 ArgoCD 같은 도구를 CiAdapter/CdAdapter 인터페이스로 교체 가능하게 할 예정이다.

## 기술 스택

| 영역 | 기술 | 버전 |
|------|------|------|
| **Language** | Java | 21 |
| **Framework** | Spring Boot | 3.4.0 |
| **ORM** | MyBatis | 3.0.3 |
| **DB** | PostgreSQL | 16-alpine (Bitnami Helm) |
| **Migration** | Flyway | (Spring Boot 관리) |
| **Message Broker** | Redpanda (Kafka 호환) | v25.x |
| **Serialization** | Apache Avro | 1.12.1 |
| **Schema Registry** | Redpanda 내장 | - |
| **Stream Processing** | Redpanda Connect | 4.43.0 |
| **AsyncAPI Doc** | Springwolf | 1.21.0 |
| **Frontend** | React 19 + Vite 6 + TypeScript 5.6 | - |
| **Frontend 상태관리** | TanStack Query 5 | ^5.60.0 |
| **DAG 시각화** | @xyflow/react (React Flow) + @dagrejs/dagre | ^12.10.1 / ^2.0.4 |
| **라우팅** | react-router-dom | ^7.1.0 |
| **CSS** | Tailwind CSS | ^4.2.1 |
| **패키지 매니저** | Yarn | 4.13.0 |
| **Build** | Gradle (Groovy DSL) | 8.10 |
| **Test** | JUnit 5 + Mockito + ArchUnit | - |
| **Observability** | OpenTelemetry + Grafana + Loki + Tempo | - |
| **Infra** | K8s (kubeadm) + Helm | GCP 3-node |

> **Java 버전 주의**: 반드시 Java 21로 빌드/테스트해야 한다.
> `JAVA_HOME`을 Corretto 21 등으로 설정할 것. (Makefile이 자동 설정)

---

## Phase별 구현 내역

### Phase 0: 미들웨어 프리셋

Phase 0의 목적은 파이프라인 실행에 필요한 외부 도구(Jenkins, GitLab, Nexus, Harbor)를 런타임에 등록하고 조합하는 기반을 만드는 것이었다.

**구현 기능**: SupportTool CRUD로 외부 도구의 URL/인증 정보를 DB에 저장하고, `ToolRegistry`가 활성 도구를 런타임에 해석하도록 했다. 도구 카테고리를 `CI_CD_TOOL`, `VCS`, `LIBRARY`, `CONTAINER_REGISTRY`로 분리하여 역할 기반 추상화를 적용했으며, `MiddlewarePreset`으로 역할별 도구 조합을 묶어 파이프라인이 프리셋을 참조하는 구조를 만들었다. Redpanda Connect 스트림을 DB에 영속화하고 런타임에 등록/삭제하는 Dynamic Connector 관리 기능도 이 단계에서 구현했다.

**설계 결정**: 도구 정보를 `application.yml`이 아닌 DB에 저장한 이유는 앱 재시작 없이 도구를 교체할 수 있어야 하기 때문이다. TPS 실무에서는 팀마다 다른 Jenkins 인스턴스를 사용하므로, 프리셋이 이 다양성을 수용하는 핵심 추상화 계층이 된다. 프리셋을 교체하면 해당 프리셋을 참조하는 모든 파이프라인이 자동으로 새 도구를 사용하게 되어, 개별 파이프라인 수정이 불필요하다.

**해결한 기술 과제**: `auth_type` 컬럼 추가(V12)로 BASIC/PRIVATE_TOKEN/NONE 인증 방식을 통합했고, `category`+`implementation` 분리(V13)로 같은 역할의 서로 다른 구현체(JENKINS vs GoCD)를 지원하는 구조를 확보했다.

### Phase 1: Job 독립화 + DAG 파이프라인

Phase 1은 프로젝트의 핵심 전환점이다. 티켓에 종속된 고정 3-스텝 파이프라인(Clone→Build→Deploy)에서 벗어나, Job을 독립 엔티티로 분리하고 DAG 기반 파이프라인 정의를 도입했다.

**구현 기능**: `PipelineDefinition`이 Job들의 DAG를 정의하고, `PipelineJob`이 독립적으로 CRUD 가능한 엔티티가 되었다. `pipeline_job_mapping` 테이블로 다대다 관계를 관리하며, `pipeline_job_dependency`로 Job 간 의존 관계를 선언한다. `DagValidator`가 Kahn's Algorithm으로 순환 참조를 감지하고, `DagExecutionCoordinator`가 의존성 순서대로 병렬 실행을 관리한다.

Jenkins 연동도 이 단계에서 본격화했다. `jenkins_script` 컬럼(V25)으로 Job별 Jenkins Pipeline 스크립트를 저장하고, `JenkinsAdapter`가 Jenkins REST API로 동적 Job 생성/실행을 수행한다. `JenkinsReconciler`는 DB에 정의된 Job과 실제 Jenkins Job의 상태를 주기적으로 동기화하여 불일치를 해소한다.

**설계 결정**: Job을 파이프라인에서 분리한 이유는 같은 빌드 Job을 여러 파이프라인에서 재사용하기 위해서다. TPS에서는 빌드/배포/테스트 Job이 독립적으로 관리되고, 파이프라인은 이들의 조합과 실행 순서만 정의한다. `pipeline_job_mapping`으로 이 관계를 표현하면서도 Job 자체의 독립성을 보장할 수 있었다. 의존성을 파이프라인별로 범위 지정(V31)한 것은, 같은 Job이 파이프라인 A에서는 다른 의존 관계를 가질 수 있어야 하기 때문이다.

**해결한 기술 과제**: `pipeline_step`을 `pipeline_job_execution`으로 리네이밍(V20)하면서 레거시 코드와의 호환을 유지해야 했다. `ticket_id`를 nullable(V30)로 변경하여 Job 단독 실행을 가능하게 했고, `execution_order`도 nullable(V24)로 만들어 DAG에서는 의존성 그래프가 실행 순서를 결정하도록 했다.

### Phase 2: K8s 전면 이관 + 버그 수정

Phase 2에서는 로컬 Docker Compose로 운영하던 모든 인프라를 GCP K8s 클러스터로 이관했다. 이관 대상은 PostgreSQL, Kafka(Redpanda), Schema Registry, Connect 4개 서비스이며, Jenkins는 이미 K8s에서 운영 중이었다.

**이관 내역과 포트 매핑**:

| 서비스 | Docker 포트 | K8s NodePort | 네임스페이스 |
|--------|------------|-------------|-------------|
| PostgreSQL | 25432 | 30275 | rp-oss |
| Kafka (Redpanda) | 29092 | 31092 | rp-oss |
| Schema Registry | 28081 | 31081 | rp-oss |
| Connect | 4195 | 31195 | rp-oss |

DB 데이터 이관은 15개 테이블을 대상으로 `pg_dump`/`pg_restore`로 수행했다. 이관 후 Docker 컨테이너와 볼륨을 전부 삭제하여 로컬에 Docker 잔여물이 없는 상태를 만들었다.

Jenkins webhook URL을 Docker 내부 DNS(`connect:4195`)에서 K8s 서비스 DNS(`connect.rp-oss.svc.cluster.local:4195`)로 변경했다. RunListener Groovy 스크립트의 콜백 URL을 갱신하는 것만으로 충분했는데, Connect가 K8s 내부에서 Jenkins와 같은 클러스터에 있으므로 ClusterIP로 통신이 가능하기 때문이다.

**수정한 버그 4건**:
- `JenkinsReconciler`가 FAILED 상태 Job을 복구하지 못하는 문제를 수정했다. 상태 체크 로직이 CREATED만 대상으로 했던 것을 FAILED도 포함하도록 변경했다.
- SSE 이벤트 전송 시 `ticketId`가 null인 DAG 실행에서 NPE가 발생하는 문제를 처리했다. `ticket_id` nullable 변경(V30)의 후속 조치로, SSE 전송 전에 ticketId null 체크를 추가했다.
- `TicketStatusEventConsumer`에서도 동일한 NPE가 발생했는데, DAG 파이프라인은 티켓 없이 실행되므로 ticketId가 없는 완료 이벤트를 무시하도록 수정했다.
- `JenkinsAdapter`의 Job 이름에 특수문자가 포함될 때 URL 인코딩이 누락되는 문제를 `URLEncoder.encode()`로 해결했다.

### Phase 3: DAG 엔진 고도화 (7개 Feature)

Phase 3의 목표는 DAG 엔진을 프로덕션 수준의 안정성으로 끌어올리는 것이었다. 7개 기능을 순차적으로 구현하면서 각각의 Flyway 마이그레이션과 테스트를 동반했다.

**1. 설정 외부화**: `PipelineProperties`(@ConfigurationProperties)로 `max-concurrent-jobs`, `webhook-timeout`, `outbox-poll-interval` 등을 `application.yml`에서 관리하도록 추출했다. 하드코딩된 매직 넘버를 제거하고 환경별 설정 오버라이드를 가능하게 한 것이다.

**2. 크래시 복구**: `@PostConstruct`에서 `pipeline_execution` 테이블의 RUNNING 상태 레코드를 조회하고, `DagExecutionCoordinator.resumeExecution()`으로 재개한다. 앱이 비정상 종료된 뒤 재시작하면 중단된 실행이 자동으로 이어지는데, 이미 완료된 Job은 건너뛰고 PENDING/RUNNING이었던 Job부터 재실행한다.

**3. Job 재시도**: Exponential Backoff(2^n초, 최대 3회)로 실패한 Job을 자동 재시도한다. `retry_count` 컬럼(V32)이 현재 재시도 횟수를 추적하며, 최대 횟수 초과 시 최종 FAILED로 처리한다. 재시도 간격은 1초 → 2초 → 4초로 증가하여 일시적 장애에 대한 복원력을 제공한다.

**4. 실패 정책**: `failure_policy` 컬럼(V33)으로 파이프라인별 실패 대응을 정의한다. `STOP_ALL`은 나머지 Job을 CANCELLED로 전이시키고, `SKIP_DOWNSTREAM`은 실패한 Job의 하위 의존성만 SKIPPED 처리하며, `FAIL_FAST`는 즉시 전체 실행을 FAILED로 종료한다. 기본값은 `STOP_ALL`이다.

**5. 부분 재시작**: `POST /api/pipelines/{id}/executions/{execId}/restart` API로 FAILED 실행에서 실패한 Job부터 재실행한다. 이미 SUCCESS인 Job은 건너뛰고, FAILED/CANCELLED Job과 그 하위 의존성을 PENDING으로 리셋한 뒤 실행을 재개하는 방식이다.

**6. DAG 이벤트**: `PIPELINE_EVT_COMPLETED` 토픽으로 실행 결과를 Avro 직렬화하여 발행한다. 외부 시스템이 파이프라인 완료를 구독할 수 있도록 한 것이며, `DagEventProducer`가 Outbox 패턴을 통해 발행의 원자성을 보장한다.

**7. 파라미터 주입**: Job별 `parameter_schema_json`(V34→V35에서 job으로 이동)으로 파라미터 스키마를 정의하고, 실행 시 `parameters_json`으로 값을 전달한다. `ParameterResolver`가 Jenkins 스크립트 내 `${PARAM}` 플레이스홀더를 치환하며, `EXECUTION_ID`/`STEP_ORDER` 같은 시스템 파라미터는 `buildWithParameters` 쿼리로 자동 전달되어 사용자에게 노출되지 않는다.

**8. 실행 컨텍스트 (Job 간 데이터 전달)**: `context_json`(V36) 컬럼이 Job 간 공유 데이터를 저장한다. BUILD Job 완료 시 GAV 좌표와 preset의 Nexus URL로 아티팩트 URL을 구성하여 `ARTIFACT_URL_{jobId}` 키로 저장한다. Nexus URL은 configJson이 아닌 Job의 preset(개발지원도구)에서 `LIBRARY` 카테고리 도구 URL을 조회한다(V37 시드). DEPLOY Job이 `dependsOnJobIds`로 BUILD Job에 의존하면, 해당 BUILD의 `ARTIFACT_URL`이 자동으로 `executionContext.ARTIFACT_URL`에 주입되어 `${ARTIFACT_URL}` 플레이스홀더가 자동 치환된다. 사용자는 DAG 의존성만 설정하면 되고, jobId나 플레이스홀더 문법을 알 필요가 없다. DEPLOY Job이 단독 실행되는 경우에는 `parameterSchema`에 `ARTIFACT_URL`을 정의하여 사용자가 직접 입력한다. 다중 BUILD의 경우 첫 번째 BUILD 의존성의 아티팩트가 사용된다.

**9. 프론트엔드 UI 완성**: 전체 실행 이력 조회 페이지(`/executions`)를 추가했다. 모든 파이프라인의 실행 기록을 한 곳에서 조회할 수 있으며, 상태별/파이프라인별 필터링을 지원한다. `PipelineExecutionPanel`에서 `ExecutionCard` 컴포넌트를 공유 컴포넌트로 추출하여 이력 페이지와 파이프라인 상세 페이지에서 재사용한다.

**10. Connect 단일포트 전환**: Redpanda Connect의 webhook 수신 포트를 4196(GitLab)/4197(Jenkins) 별도 포트에서 공유 HTTP 포트(4195) 하나로 통합했다. Streams 모드(`streams --chilled`)에서는 `http_server` input의 `address` 필드가 무시되고 `-o` 플래그로 지정한 공유 HTTP 서버 포트에 모든 경로가 등록되는 것이 Redpanda Connect의 설계이다. 추가로 Streams 모드에서 `http_server` input에 `address`를 생략하면 경로가 스트림 이름을 prefix로 받는다. 즉 `jenkins-webhook` 스트림의 `/webhook/jenkins` 경로는 공유 서버에서 `/jenkins-webhook/webhook/jenkins`로 등록된다. 따라서 Jenkins webhook URL은 `connect:4195/jenkins-webhook/webhook/jenkins`, GitLab webhook URL은 `connect:4195/gitlab-webhook/webhook/gitlab`이 된다. K8s 이관 시 별도 포트/NodePort(31196, 31197)를 할당했으나 실제로는 불필요했으므로 제거했다. webhook 구분은 포트가 아닌 경로로 한다.

---

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
- **파라미터 주입**: Job별 `parameter_schema_json` 정의, 실행 시 `parameters_json` 전달 → `ParameterResolver`가 `${PARAM}` 플레이스홀더 치환
- **시스템 파라미터**: `EXECUTION_ID`, `STEP_ORDER`는 `buildWithParameters` 쿼리로 전달. Jenkins에 파라미터 선언 없이 환경변수(`env.X`)로 접근. 사용자에게 노출되지 않음

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

**Jenkins 웹훅 발송 전략**: RunListener 전역 리스너(Groovy init script)가 모든 빌드 완료 시 자동으로 Connect에 콜백.
개별 Pipeline 스크립트에 `post.always` webhook 블록이 불필요하므로, 사용자 스크립트와 비간섭이 보장된다.
`EXECUTION_ID`가 포함된 빌드만 콜백을 전송하고, 없으면 무시한다.

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

---

## 문서 인덱스

`docs/` 디렉토리에 프로젝트의 설계 문서, 코드 리뷰, 패턴 카탈로그, 요구사항을 체계적으로 정리해 두었다. 문서 진입점은 `docs/README.md`이며, 아래는 전체 구조와 각 문서의 역할이다.

### 디렉토리 트리

```
docs/
├── README.md                          # 종합 가이드 (전체 흐름 이해)
├── architecture.md                    # DB 설계, ERD, 핵심 기능 흐름
├── domain-guide.md                    # 도메인 온보딩 (Ticket→Pipeline→Webhook)
├── patterns.md                        # 아키텍처 패턴 카탈로그 (14개)
├── demo-script.md                     # 데모 시나리오 스크립트
├── app/
│   └── review/
│       ├── 01-structure-overview.md   # app 모듈 구조
│       ├── 02-db-migration.md         # DB 마이그레이션 리뷰
│       ├── 03-service-layer-and-connector.md  # 서비스/커넥터 리뷰
│       └── 04-preset-domain.md        # 프리셋 도메인 리뷰
├── pipeline/
│   ├── pipeline-flow.md               # 파이프라인 실행 흐름도
│   ├── dag/
│   │   ├── README.md                  # DAG 엔진 종합 가이드
│   │   ├── 01-concepts.md            # DAG 개념과 설계
│   │   ├── 02-code-walkthrough.md    # 코드 워크스루
│   │   └── 03-separation-analysis.md # 분리 분석
│   └── review/
│       ├── 05-phase2-structure-overview.md  # Phase 2 전체 구조
│       ├── 06-phase2-db-migration.md        # Phase 2 DB 마이그레이션
│       ├── 07-phase2-dag-engine.md          # DAG 엔진 핵심 리뷰
│       └── 08-phase3-dag-hardening.md       # Phase 3 고도화 리뷰
├── frontend/
│   ├── 01-architecture.md             # 기술 스택, 훅, SSE + TanStack Query
│   ├── 02-ui-styling-guide.md         # Google Stitch UI 디자인 가이드
│   └── 03-dag-frontend.md            # DAG 프론트엔드 (Static/Live 모드)
├── patterns/
│   ├── 01-202-accepted.md
│   ├── 02-saga-orchestrator.md
│   ├── 03-transactional-outbox.md
│   ├── 04-sse-realtime.md
│   ├── 05-break-and-resume.md
│   ├── 06-redpanda-connect.md
│   ├── 07-topic-message-design.md
│   ├── 08-adapter-fallback.md
│   ├── 09-idempotency.md
│   ├── 10-dynamic-connector.md
│   ├── 11-e2e-trace-propagation.md
│   ├── 12-crash-recovery.md
│   ├── 13-failure-policy.md
│   └── 14-job-retry.md
└── requirements/
    ├── README.md                      # 요구사항 인덱스 + 현황 대시보드
    ├── 01-ticket.md                   # 티켓 관리
    ├── 02-workflow.md                 # 워크플로우
    ├── 03-pipeline.md                 # 파이프라인
    ├── 04-build-job.md                # 빌드 Job
    ├── 05-test-job.md                 # 테스트 Job (PoC 생략)
    ├── 06-deploy-job.md               # 배포 Job
    ├── 07-artifact-management.md      # 결과물 관리
    ├── 08-middleware.md               # 미들웨어
    ├── 09-deploy-environment.md       # 배포 환경
    └── 10-user-and-notification.md    # 사용자/알림
```

### 문서 분류 테이블

| 분류 | 문서 | 내용 |
|------|------|------|
| **프로젝트 공통** | `architecture.md` | DB 설계(ERD, Flyway), 핵심 기능 흐름, 이벤트 설계, 동시성 보장 |
| | `domain-guide.md` | 도메인 온보딩 가이드 (Ticket → Pipeline → Webhook 순서) |
| | `patterns.md` | 14개 아키텍처 패턴 카탈로그와 적용 사례 |
| | `demo-script.md` | 데모 시나리오 스크립트 (티켓 생성→파이프라인→결과 확인) |
| **app 모듈** | `app/review/01~04` | Phase 0~1의 app 모듈 코드 리뷰 (구조, DB, 서비스, 프리셋) |
| **pipeline 모듈** | `pipeline/pipeline-flow.md` | 파이프라인 실행 흐름도 + 토픽/커넥터 상세 |
| | `pipeline/dag/01~03` | DAG 엔진 개념, 코드 워크스루, 분리 분석 |
| | `pipeline/review/05~08` | Phase 2~3 코드 리뷰 (구조, DB, 엔진, 고도화) |
| **프론트엔드** | `frontend/01~03` | 아키텍처, UI 스타일링, DAG 시각화 (Static/Live 모드) |
| **패턴 상세** | `patterns/01~14` | 각 패턴별 독립 문서 (구현 코드 + 설계 근거) |
| **요구사항** | `requirements/01~10` | TPS 요구사항 재해석 (10개 도메인, 우선순위별) |

---

## 인프라 (K8s — kubeadm 3-node 클러스터)

GCP에 kubeadm으로 구축한 3대 K8s 클러스터에서 운영한다.
모든 인프라 서비스가 K8s로 이관 완료되었다.

### K8s 서비스 (네임스페이스별)

| 네임스페이스 | 서비스 | ClusterIP 포트 | NodePort | 비고 |
|-------------|--------|---------------|----------|------|
| **rp-oss** | Redpanda (Kafka) | 9092 | 31092 | Helm |
| **rp-oss** | Schema Registry | 8081 | 31081 | |
| **rp-oss** | Console | 8080 | 31880 | |
| **rp-oss** | Connect | 4195 | 31195 | 단일포트 (webhook 경로 기반) |
| **rp-oss** | PostgreSQL | 5432 | 30275 | Bitnami Helm |
| **rp-oss** | Nexus | 8081 | 31280 | Plain manifest |
| **rp-jenkins** | Jenkins | 8080 | 31080 | Helm |
| **rp-mgm** | Grafana | 80 | 30000 | |
| **rp-mgm** | Alloy (OTLP) | 4317/4318 | 30317/30318 | |
| **rp-gitlab** | GitLab CE | 8181 | 31480 | Helm |
| **ingress-nginx** | Ingress | 80/443 | 31292/31726 | |
| **argocd** | ArgoCD | - | 31134 | |

> dev-server의 control-plane taint 제거됨 (GitLab 스케줄링을 위해). 모든 노드에 Pod 배치 가능.

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

V1~V38까지 38개 마이그레이션. 주요 변경점만 기술한다.

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
make backend         # Operator 실행 (GCP 프로필)
make backend-local   # Spring Boot 실행 (로컬 프로필)
make executor        # Executor 실행 (GCP 프로필)

# 직접 실행
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./gradlew clean build
./gradlew :operator:bootRun                          # 로컬
SPRING_PROFILES_ACTIVE=gcp ./gradlew :operator:bootRun  # GCP
SPRING_PROFILES_ACTIVE=gcp ./gradlew :executor:bootRun
```

## 프로젝트 구조 (멀티모듈)

Gradle 4-모듈 구조로 공통 코드(`common`), Kafka 인프라(`common-kafka`),
운영자 역할 통합 모듈(`operator`), 실행 제어 모듈(`executor`)를 사용한다.

```
redpanda-playground/
├── settings.gradle          # include 'common', 'common-kafka', 'operator', 'executor'
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
├── operator/                # 통합 Spring Boot 메인 모듈
│   ├── build.gradle
│   └── src/main/java/com/study/playground/operator/
│       ├── project/         # 프로젝트 도메인
│       ├── purpose/         # 목적/프리셋 도메인
│       ├── supporttool/     # 지원 도구 도메인
│       ├── pipeline/        # 파이프라인/Job 관리 도메인
│       ├── operatorjob/     # 실행 디스패치/완료 수신용 operator 역할
│       ├── common/          # operator 내부 공통 설정
│       └── OperatorApplication.java
├── executor/                # Job 실행 제어 모듈
│   ├── build.gradle
│   └── src/main/java/com/study/playground/executor/
│       ├── execution/       # 실행 도메인/애플리케이션/인프라
│       ├── config/          # Kafka, scheduler, properties
│       └── ExecutorApplication.java
├── frontend/                # React 프론트엔드
├── infra/                   # 인프라 (Docker, K8s, ArgoCD, 문서)
│   ├── docker/local/        # 로컬 Docker Compose 파일
│   ├── k8s/                 # Kubernetes Helm 설정
│   └── docs/                # 인프라 문서
└── http/                    # HTTP 요청 파일 (.http)
```

### 모듈 의존성

```
common ← common-kafka
                     ├── operator
                     └── executor
```

| 모듈 | 포함 | 이유 |
|------|------|------|
| **common** | 공통 DTO, 예외, MyBatis 타입 핸들러 | 전 모듈 공유 |
| **common-kafka** | 토픽 상수, Kafka 설정, AvroSerializer, Outbox, Avro 스키마 | Kafka 인프라 — DB 무의존 |
| **operator** | project/purpose/supporttool/pipeline/operatorjob + Spring Boot 메인 | 기존 app/pipeline/operator-stub 통합 |
| **executor** | 실행 큐, Jenkins 트리거, 상태 전이, 재시도/복구 | 실행 제어 전담 모듈 |

`executor`는 `operator` 스키마를 cross-schema로 조회하지만 Gradle 모듈 의존성은 분리한다.

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
SPRING_PROFILES_ACTIVE=gcp ./gradlew :operator:bootRun

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
