# 파이프라인 도메인

## 개요

파이프라인은 Job들의 DAG 실행 체인이다. 티켓에 종속되지 않는 독립 엔티티로, Job들을 조합하여 병렬/순차 실행한다. SAGA Choreography로 보상 처리를 하며, Jenkins와 Outbox+Reconciler 패턴으로 연동한다.

파이프라인과 Job의 관계는 "조합 vs 정의"로 나뉜다. Job은 단독으로 존재할 수 있으며 여러 파이프라인에서 재사용된다. 파이프라인은 Job들을 어떤 순서와 의존 관계로 실행할지만 정의한다.

---

## 현재 상태 (2026-03-22 기준)

| 항목 | 상태 | 비고 |
|------|------|------|
| Pipeline 독립 엔티티 | 완료 | 티켓 ID 없이 생성/실행 가능 |
| Job 독립 엔티티 | 완료 | 단독 CRUD + 실행, pipeline과 분리 |
| DAG 의존성 그래프 | 완료 | Kahn's Algorithm, DagValidator |
| 병렬 실행 | 완료 | DagExecutionCoordinator, 스레드 풀 |
| SAGA 보상 | 완료 | 역방향 위상 순서 보상 |
| 크래시 복구 | 완료 | @PostConstruct resumeExecution() |
| Job 재시도 | 완료 | Exponential Backoff, retry_count (V32) |
| 실패 정책 | 완료 | failure_policy (V33): STOP_ALL/SKIP_DOWNSTREAM/FAIL_FAST |
| 부분 재시작 | 완료 | POST /api/pipelines/{id}/executions/{execId}/restart |
| 파라미터 주입 | 완료 | ParameterResolver, ${PARAM} 치환 (V34→V35) |
| 실행 컨텍스트 | 완료 | context_json (V36), ARTIFACT_URL 자동 전달 |
| 실행 이력 UI | 완료 | /executions 페이지, ExecutionCard 공유 컴포넌트 |
| Connect 단일포트 | 완료 | 4195 공유 HTTP, 경로 prefix 구분 |
| 설정 외부화 | 완료 | PipelineProperties @ConfigurationProperties |
| Jenkins 동적 Job 생성 | 완료 | Outbox+Reconciler, playground-job-{id} |
| 가이드형 Job 템플릿 | 미구현 | BUILD/DEPLOY 자동 구성 템플릿 |
| 파이프라인 복제 | 미구현 | Job 구성 복사 기능 |

---

## 요구사항

### REQ-03-001: 파이프라인 독립 엔티티 [P0] — 완료

파이프라인을 티켓에 종속되지 않는 독립 엔티티로 설계한다. 워크플로우나 관리자가 직접 실행할 수 있으며, Job들의 DAG 실행 체인을 정의한다.

**수용 기준**:
- [x] 파이프라인 생성 시 티켓 ID 불필요 (`ticket_id` nullable, V30)
- [x] 파이프라인 ID, 이름, 설명, 상태 관리
- [x] 파이프라인 목록 조회 (이름/상태 필터)
- [x] 파이프라인 삭제 (실행 중이 아닐 때만)

**구현 참조**: `pipeline.domain.Pipeline`, `pipeline.api.PipelineController`

**Phase**: 1

---

### REQ-03-001-1: Job 독립 엔티티 원칙 [P0] — 완료

Job은 파이프라인과 독립된 엔티티이다. 단독으로 CRUD 및 실행이 가능하며, 실행 순서는 파이프라인에서 DAG를 구성할 때 비로소 결정된다. 같은 Job을 여러 파이프라인에서 재사용할 수 있다는 점이 핵심 설계 원칙이다.

**수용 기준**:
- [x] Job 생성 시 `execution_order` 없음 (nullable, V24) — 단독 실행 가능
- [x] `pipeline_job_mapping` 테이블에서 파이프라인↔Job 순서/의존 관계 정의
- [x] Job 타입 BUILD, DEPLOY 두 가지 지원
- [x] `pipeline_job_dependency` 테이블로 DAG 엣지 관리 (파이프라인별 범위 지정, V31)

**구현 참조**: `pipeline.domain.PipelineJob`, `pipeline.domain.PipelineJobMapping`, `pipeline.domain.PipelineJobDependency`

**Phase**: 1

---

### REQ-03-002: Job 구성 관리 [P0] — 완료

파이프라인에 포함될 Job의 구성을 관리한다. 하나의 파이프라인은 N개의 Job을 가지며, 각 Job은 타입과 config JSON으로 정의된다.

**수용 기준**:
- [x] Job 추가/제거/순서 변경
- [x] 각 Job은 타입(BUILD/DEPLOY), 설정(config JSON) 보유
- [x] Job 설정은 타입별로 다른 스키마 (04-build-job, 06-deploy-job 참조)
- [x] `pipeline_job_mapping`으로 다대다 관계 표현

**구현 참조**: `pipeline.service.PipelineJobService`, `pipeline.api.PipelineJobController`

**Phase**: 1

---

### REQ-03-003: 가이드형 Job 생성 [P1] — 미구현

미리 정의된 템플릿으로 Job을 생성하는 가이드형 모드를 제공한다. 가이드형은 자유형의 "프리필드된 버전"으로, 내부적으로 같은 Job config를 생성하되 서비스 레이어에서 사용자 입력을 config로 변환한다.

**수용 기준**:
- [ ] BUILD 템플릿: Git URL + 브랜치 + Build Script → Job config 자동 생성
- [ ] DEPLOY 템플릿: 배포 대상(VM/K8S) + 환경 → Job config 자동 생성
- [ ] 템플릿 선택 → 필수 파라미터 입력 → config 자동 구성 UI
- [ ] 생성 후 사용자가 config를 자유롭게 수정 가능

**구현 참조**: (미구현)

**Phase**: 1

---

### REQ-03-004: 자유형 Job 생성 [P1] — 완료

사용자가 Job의 모든 설정을 직접 정의하는 자유형 모드를 제공한다. Jenkinsfile 스크립트를 직접 작성하고, 시스템이 Jenkins에 파이프라인을 자동 등록한다.

**수용 기준**:
- [x] Job 생성 폼에 Jenkinsfile 스크립트 직접 입력 (Declarative/Scripted Pipeline)
- [x] Job 타입 선택 (BUILD/DEPLOY)
- [x] 생성 시 `jenkins_script` DB 저장 + Outbox 이벤트(`JENKINS_JOB_CREATE`) 발행
- [x] Jenkins 등록 완료 → `jenkins_status = ACTIVE`, `ACTIVE` 아닐 때 실행 거부

**구현 참조**: `pipeline.adapter.jenkins.JenkinsAdapter`, `pipeline.outbox.OutboxPoller`

**Phase**: 1

---

### REQ-03-005: 미들웨어 프리셋 참조 [P0] — 완료

Job이 미들웨어 프리셋을 필수로 참조한다. 프리셋을 통해 어떤 Jenkins, Harbor, ArgoCD를 사용할지 간접 지정하므로, 프리셋만 교체하면 Job 설정 변경 없이 도구를 교체할 수 있다.

**수용 기준**:
- [x] Job 생성 시 프리셋 필수 (`presetId` @NotNull, 프론트 필수 선택)
- [x] 기본 프리셋 "default" (VCS=GitLab) 제공
- [x] GoCD 구현체 제거 (ToolImplementation enum + DB에서 삭제)
- [x] 프리셋 수정 시 해당 프리셋 참조 Job에 자동 반영

**구현 참조**: `middleware.domain.Preset`, `pipeline.domain.PipelineJob.presetId`

**Phase**: 1

---

### REQ-03-006: 실행 파라미터 ${PARAM} 주입 [P1] — 완료

파이프라인 실행 시 런타임 파라미터를 전달한다. Job config의 `${PARAM_NAME}` 플레이스홀더가 실행 시 실제 값으로 치환된다. Phase 3에서 구현되었다.

**수용 기준**:
- [x] Job별 `parameter_schema_json`(V35)으로 파라미터 스키마 정의 (이름, 타입, 기본값, 필수)
- [x] 실행 요청에 `parameters_json`으로 key-value 값 전달
- [x] `ParameterResolver`가 Jenkins 스크립트 내 `${PARAM}` 치환
- [x] 필수 파라미터 누락 시 400 에러 반환

**구현 참조**: `pipeline.service.ParameterResolver`, `pipeline.domain.PipelineExecution.parametersJson`

**Phase**: 3

---

### REQ-03-007: 실행 이력 조회 [P1] — 완료

파이프라인 실행 내역을 독립적으로 관리하고 조회한다. Phase 3에서 전용 UI 페이지가 추가되었다.

**수용 기준**:
- [x] 실행 ID, 파이프라인 ID, 트리거 소스, 시작/종료 시간 기록
- [x] 각 Job 실행 상태(PENDING→RUNNING→COMPLETED/FAILED) 추적
- [x] `/executions` 페이지: 전체 실행 이력 조회, 상태별/파이프라인별 필터링
- [x] `ExecutionCard` 공유 컴포넌트로 이력 페이지와 상세 페이지에서 재사용

**구현 참조**: `pipeline.domain.PipelineExecution`, `frontend/src/pages/ExecutionsPage`

**Phase**: 1 (기록), 3 (UI)

---

### REQ-03-007-1: Job = Jenkins 파이프라인 동적 생성 [P0] — 완료

Job 생성 시 사용자가 Jenkinsfile 스크립트를 작성하면, 시스템이 Jenkins에 파이프라인을 자동 등록한다. DB가 Source of Truth이고 Jenkins config.xml은 파생물이다.

**수용 기준**:
- [x] Jenkins 파이프라인 이름 규칙: `playground-job-{jobId}`
- [x] Outbox 이벤트(`JENKINS_JOB_CREATE/UPDATE/DELETE`)로 Jenkins와 비동기 동기화
- [x] `JenkinsReconciler`(60초 주기)가 DB와 Jenkins 간 드리프트를 보정
- [x] FAILED 상태 Job에서 `POST /api/jobs/{id}/retry-provision`으로 재시도 가능

**구현 참조**: `pipeline.adapter.jenkins.JenkinsAdapter`, `pipeline.reconciler.JenkinsReconciler`

**Phase**: 1

---

### REQ-03-008: 파이프라인 복제 [P2] — 미구현

기존 파이프라인의 Job 구성을 복사하여 새 파이프라인을 생성한다. 원본과 독립적으로 수정 가능한 복사본을 만드는 기능이다.

**수용 기준**:
- [ ] 원본 파이프라인 ID + 새 이름으로 복제 요청
- [ ] Job 구성, 파라미터 스키마 복사 (프리셋 참조는 선택적)
- [ ] 복제된 파이프라인은 원본과 독립적으로 수정 가능
- [ ] 복제 API: `POST /api/pipelines/{id}/clone`

**구현 참조**: (미구현)

**Phase**: 2

---

### REQ-03-009: DAG 의존성 그래프 [P0] — 완료

파이프라인 내 Job 간의 의존 관계를 DAG(Directed Acyclic Graph)로 정의한다. 순환 의존성은 생성/수정 시점에 검증하여 거부한다.

**수용 기준**:
- [x] 각 Job은 `dependsOn` 목록으로 선행 Job을 지정
- [x] `DagValidator`가 저장 시점에 Kahn's Algorithm으로 순환 탐지
- [x] `dependsOn`이 비어 있는 Job은 루트 Job으로 간주하여 즉시 실행
- [x] 의존성은 파이프라인별로 범위 지정 (V31)

**구현 참조**: `pipeline.service.DagValidator`, `pipeline.domain.PipelineJobDependency`

**Phase**: 1

---

### REQ-03-010: 병렬 Job 실행 [P0] — 완료

의존성이 충족된 Job들을 동시에 실행한다. Jenkins containerCap(3)에 맞춰 최대 3개 Job을 동시 실행한다.

**수용 기준**:
- [x] 위상 정렬(topological sort)로 실행 라운드 결정
- [x] 같은 라운드의 Job은 스레드 풀에서 병렬 디스패치
- [x] 실행 중인 Job이 containerCap 도달 시 다음 ready Job 대기
- [x] 하나의 Job 실패 시 실행 중 Job은 완료를 기다리되, 새 Job은 미디스패치

**구현 참조**: `pipeline.service.DagExecutionCoordinator`

**Phase**: 1

---

### REQ-03-011: SAGA 보상 [P0] — 완료

DAG 실행 중 일부 Job이 실패했을 때 역방향 위상 순서로 보상을 수행한다. 완료된 Job들의 사이드이펙트를 순서대로 되돌린다.

**수용 기준**:
- [x] 보상 순서: 완료된 Job을 역방향 위상 순서(leaf → root)로 보상
- [x] 병렬 실행된 Job의 보상도 역순으로 직렬 처리 (보상 간 부수효과 충돌 방지)
- [x] PENDING/SKIPPED 상태의 Job은 보상 대상에서 제외
- [x] 보상 실패 시 `COMPENSATION_FAILED`로 기록하고 수동 개입 요청

**구현 참조**: `pipeline.service.DagExecutionCoordinator`, `pipeline.domain.CompensationState`

**Phase**: 1

---

### REQ-03-012: 설정 외부화 [P0] — 완료

하드코딩된 매직 넘버를 제거하고 `application.yml`에서 환경별로 오버라이드할 수 있도록 설정을 외부화한다.

**수용 기준**:
- [x] `PipelineProperties`(@ConfigurationProperties)로 설정값 일괄 관리
- [x] `max-concurrent-jobs`: 최대 동시 실행 Job 수
- [x] `webhook-timeout`: Jenkins 웹훅 응답 대기 시간
- [x] `outbox-poll-interval`: Outbox 폴링 주기

**구현 참조**: `pipeline.config.PipelineProperties`

**Phase**: 3

---

### REQ-03-013: 크래시 복구 [P0] — 완료

앱이 비정상 종료된 뒤 재시작하면 중단된 실행을 자동으로 재개한다. 이미 완료된 Job은 건너뛰고 미완료 Job부터 실행을 이어간다.

**수용 기준**:
- [x] `@PostConstruct`에서 `pipeline_execution` 테이블의 RUNNING 상태 레코드 조회
- [x] `DagExecutionCoordinator.resumeExecution()`으로 실행 재개
- [x] 이미 SUCCESS인 Job은 건너뛰고 PENDING/RUNNING 상태부터 재실행
- [x] 복구 대상 실행이 없으면 아무 동작 없이 정상 기동

**구현 참조**: `pipeline.service.PipelineRecoveryService`, `pipeline.service.DagExecutionCoordinator.resumeExecution()`

**Phase**: 3

---

### REQ-03-014: Job 재시도 [P0] — 완료

일시적 장애에 대한 복원력을 높이기 위해 실패한 Job을 Exponential Backoff 방식으로 자동 재시도한다. 최대 재시도 횟수 초과 시 최종 FAILED로 처리한다.

**수용 기준**:
- [x] 최대 3회 재시도, 간격은 2^n초 (1초 → 2초 → 4초)
- [x] `retry_count` 컬럼(V32)이 현재 재시도 횟수를 추적
- [x] 최대 횟수 초과 시 최종 FAILED 처리
- [x] 재시도 중 상태는 RUNNING 유지, 재시도 간 횟수 증가 기록

**구현 참조**: `pipeline.service.JobRetryService`, `pipeline.domain.PipelineJobExecution.retryCount`

**Phase**: 3

---

### REQ-03-015: 실패 정책 [P0] — 완료

파이프라인별로 Job 실패 시 대응 방식을 정의한다. 기본값은 `STOP_ALL`로, 나머지 모든 Job을 CANCELLED로 전이시킨다.

**수용 기준**:
- [x] `failure_policy` 컬럼(V33)으로 파이프라인별 실패 정책 저장
- [x] `STOP_ALL`: 나머지 Job을 모두 CANCELLED로 전이 (기본값)
- [x] `SKIP_DOWNSTREAM`: 실패한 Job의 하위 의존성만 SKIPPED 처리
- [x] `FAIL_FAST`: 즉시 전체 실행을 FAILED로 종료

**구현 참조**: `pipeline.domain.FailurePolicy`, `pipeline.service.DagExecutionCoordinator`

**Phase**: 3

---

### REQ-03-016: 부분 재시작 [P1] — 완료

FAILED 상태의 실행에서 이미 성공한 Job을 건너뛰고 실패한 Job부터 재실행한다. 전체를 처음부터 다시 실행하는 것보다 효율적이다.

**수용 기준**:
- [x] API: `POST /api/pipelines/{id}/executions/{execId}/restart`
- [x] SUCCESS인 Job은 건너뛰고, FAILED/CANCELLED Job을 PENDING으로 리셋
- [x] PENDING으로 리셋된 Job의 하위 의존성도 함께 PENDING 리셋
- [x] 리셋 완료 후 `DagExecutionCoordinator.resumeExecution()`으로 재개

**구현 참조**: `pipeline.service.PipelineRestartService`, `pipeline.api.PipelineExecutionController`

**Phase**: 3

---

### REQ-03-017: DAG 이벤트 발행 [P0] — 완료

파이프라인 실행 완료 시 외부 시스템이 구독할 수 있도록 이벤트를 발행한다. Outbox 패턴으로 발행의 원자성을 보장한다.

**수용 기준**:
- [x] 파이프라인 실행 완료 시 `PIPELINE_EVT_COMPLETED` 토픽에 이벤트 발행
- [x] Avro 직렬화로 스키마 호환성 보장
- [x] `DagEventProducer`가 Outbox 패턴을 통해 발행 (DB 커밋과 원자적)
- [x] 실패/성공 모두 이벤트 발행, 결과 필드로 구분

**구현 참조**: `pipeline.event.DagEventProducer`, `pipeline.event.PipelineCompletedEvent`

**Phase**: 3

---

### REQ-03-018: 파라미터 주입 [P0] — 완료

Job 정의에 파라미터 스키마를 선언하고, 실행 시 전달된 값으로 Jenkins 스크립트 내 플레이스홀더를 치환한다. 시스템 파라미터(`EXECUTION_ID`, `STEP_ORDER`)는 자동 전달된다.

**수용 기준**:
- [x] `parameter_schema_json`(V35, job 테이블)으로 스키마 정의 (이름, 타입, 기본값, 필수)
- [x] 실행 시 `parameters_json`으로 실제 값 전달
- [x] `ParameterResolver`가 Jenkins 스크립트 내 `${PARAM}` 플레이스홀더 치환
- [x] `EXECUTION_ID`/`STEP_ORDER`는 `buildWithParameters` 쿼리로 자동 전달 (사용자 비노출)

**구현 참조**: `pipeline.service.ParameterResolver`, `pipeline.adapter.jenkins.JenkinsAdapter`

**Phase**: 3

---

### REQ-03-019: 실행 컨텍스트 [P0] — 완료

BUILD Job이 생성한 아티팩트 정보를 DEPLOY Job이 자동으로 받아쓸 수 있도록 Job 간 공유 데이터를 실행 컨텍스트에 저장한다. 사용자는 DAG 의존성만 설정하면 되고, jobId나 플레이스홀더 문법을 알 필요가 없다.

**수용 기준**:
- [x] `context_json` 컬럼(V36)에 Job 간 공유 데이터 저장
- [x] BUILD 완료 시 GAV + Nexus URL → `ARTIFACT_URL_{jobId}` 키로 저장
- [x] DEPLOY Job이 `dependsOnJobIds`로 BUILD에 의존하면 `ARTIFACT_URL` 자동 주입
- [x] DEPLOY 단독 실행 시 `parameterSchema`에 `ARTIFACT_URL` 정의로 사용자 직접 입력

**구현 참조**: `pipeline.service.ExecutionContextService`, `pipeline.domain.PipelineExecution.contextJson`

**Phase**: 3

---

### REQ-03-020: 실행 이력 UI [P1] — 완료

모든 파이프라인의 실행 기록을 한 곳에서 조회할 수 있는 전용 페이지를 제공한다. `ExecutionCard`를 공유 컴포넌트로 추출하여 여러 페이지에서 재사용한다.

**수용 기준**:
- [x] `/executions` 페이지: 전체 실행 이력 통합 조회
- [x] 상태별 필터링 (PENDING/RUNNING/COMPLETED/FAILED)
- [x] 파이프라인별 필터링
- [x] `ExecutionCard` 공유 컴포넌트: 이력 페이지 + 파이프라인 상세 페이지에서 재사용

**구현 참조**: `frontend/src/pages/ExecutionsPage`, `frontend/src/components/ExecutionCard`

**Phase**: 3

---

### REQ-03-021: Connect 단일포트 [P0] — 완료

Redpanda Connect의 webhook 수신 포트를 4195 단일 HTTP 포트로 통합한다. 포트 구분 대신 경로(path prefix)로 Jenkins/GitLab 웹훅을 구별한다.

**수용 기준**:
- [x] 4195 포트 단일 공유 HTTP 서버 (기존 4196/4197 별도 포트 제거)
- [x] Jenkins webhook URL: `connect:4195/jenkins-webhook/webhook/jenkins`
- [x] GitLab webhook URL: `connect:4195/gitlab-webhook/webhook/gitlab`
- [x] Streams 모드에서 스트림 이름이 경로 prefix로 자동 등록되는 Connect 설계 활용

**구현 참조**: `infra/connect/streams/jenkins-webhook.yaml`, `infra/connect/streams/gitlab-webhook.yaml`

**Phase**: 3

---

## 설계 결정

### Job 독립화

Job을 파이프라인과 분리한 이유는 같은 빌드 Job을 여러 파이프라인에서 재사용하기 위해서다. TPS에서는 빌드/배포/테스트 Job이 독립적으로 관리되고, 파이프라인은 이들의 조합과 실행 순서만 정의한다. `pipeline_job_mapping`으로 다대다 관계를 표현하면서도 Job 자체의 독립성을 보장한다.

### DAG vs Sequential

`pipeline_job_dependency`로 의존 관계를 선언하고, Kahn's Algorithm 위상 정렬로 실행 라운드를 결정한다. 같은 라운드의 Job은 병렬로 실행되므로 고정 순서보다 실행 시간이 단축된다. 의존성은 파이프라인별로 범위 지정(V31)하여, 같은 Job이 파이프라인 A와 B에서 다른 의존 관계를 가질 수 있다.

### Jenkins 통신 이중 경로

Jenkins와의 통신은 관리와 실행 두 가지 경로를 사용하며, 각 경로의 요구사항이 다르기 때문에 다른 메커니즘을 선택했다.

- **관리 API (Job 생성/수정/삭제)**: Spring Boot가 `JenkinsAdapter`(RestTemplate)로 Jenkins REST API를 직접 호출한다. Outbox 패턴이 재시도와 내구성을 보장하므로 Kafka를 경유할 필요가 없다. `JenkinsReconciler`(60초 주기)가 DB와 Jenkins 간 드리프트를 보정하는 안전망 역할을 한다.
- **실행 API (빌드 트리거)**: Spring Boot가 Kafka 토픽(`commands.jenkins`)에 커맨드를 발행하면, Redpanda Connect(`jenkins-command.yaml`)가 이를 소비하여 Jenkins를 호출한다. Jenkins 일시 장애 시에도 커맨드가 유실되지 않고 재처리된다.

---

## 도메인 간 관계

| 참조 도메인 | 관계 | 설명 |
|------------|------|------|
| 08-middleware | 프리셋 참조 (`presetId`) | Job이 어떤 Jenkins/Harbor를 사용할지 프리셋을 통해 간접 지정 |
| 04-build-job | Job 타입별 설정 | BUILD 타입 Job의 config JSON 스키마 |
| 06-deploy-job | Job 타입별 설정 | DEPLOY 타입 Job의 config JSON 스키마 |
| Connect | 웹훅 수신 | `jenkins-webhook`(4195/jenkins-webhook/webhook/jenkins), `gitlab-webhook`(4195/gitlab-webhook/webhook/gitlab) |

---

### REQ-03-022: BUILD_REQUIRED 배포 Job 매핑 검증 [P0] — 미구현

`deployMode = BUILD_REQUIRED`인 배포 Job을 파이프라인에 추가할 때, `requiredBuildJobId`로 지정된 빌드 Job이 동일 파이프라인에 포함되어 있는지 검증한다. 검증 통과 시 DAG 의존성을 자동 생성하고, 빌드 Job 제거 시 의존하는 배포 Job이 있으면 제거를 거부한다.

**수용 기준**:
- [ ] BUILD_REQUIRED 배포 Job 매핑 시 `requiredBuildJobId`가 동일 파이프라인 `pipeline_job_mapping`에 존재하는지 검증
- [ ] 미포함 시 400 에러 반환
- [ ] 포함 시 `pipeline_job_dependency` 자동 생성 (빌드 → 배포)
- [ ] 빌드 Job 제거 시, 해당 빌드를 참조하는 BUILD_REQUIRED 배포 Job이 있으면 제거 거부 (400 에러)

**구현 참조**: (미구현). 검증 로직은 `PipelineJobService` 또는 `PipelineJobMappingService`에 추가.

**Phase**: 4

---

## 미해결 사항

- **BUILD_REQUIRED 매핑 검증 (REQ-03-022)**: 배포 Job의 `requiredBuildJobId`와 파이프라인 Job 매핑 간 무결성 검증. `06-deploy-job.md` REQ-06-009와 연계.
- **가이드형 Job 템플릿 (REQ-03-003)**: BUILD/DEPLOY 템플릿으로 자동 config 구성. 자유형만 지원하므로 사용자가 Jenkinsfile을 직접 작성해야 한다.
- **파이프라인 복제 (REQ-03-008)**: Job 구성을 그대로 복사하여 새 파이프라인 생성. 반복적인 파이프라인 설정 작업을 줄이는 편의 기능이다.
- **CiAdapter/CdAdapter 인터페이스 추상화**: 현재 Jenkins에 강하게 결합된 구조. `JenkinsAdapter`를 `CiAdapter` 인터페이스 뒤로 추상화하면 GitLab CI 등 다른 CI 도구를 추가할 때 기존 서비스 로직을 건드리지 않아도 된다.
