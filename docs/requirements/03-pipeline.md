# 파이프라인

## TPS 원본 — 마인드맵에서 무엇을 하는가

TPS에서 파이프라인은 Job들의 실행 체인이다. 파이프라인 관리는 구성관리(어떤 Job들을 조합할지)와 Job관리(각 Job의 상세 설정)로 나뉜다. Job관리에는 가이드형(빌드/배포/테스트 같은 미리 정의된 템플릿)과 자유형(사용자가 직접 정의)이 있다. 파이프라인 실행 시 req_id로 Redpanda 브로커를 경유하며, 파라미터를 자유롭게 설정할 수 있다.

파이프라인은 티켓에 종속되지 않는 독립 개념이다. 워크플로우가 참조할 수도 있고, 관리자가 직접 실행할 수도 있다.

---

## PoC 목표 — Playground에서 무엇을 검증하는가

1. **파이프라인 독립성**: 티켓/워크플로우 없이 파이프라인을 생성하고 실행하는 흐름을 구현한다
2. **Job 구성 관리**: 여러 Job을 순서대로 조합하는 구조를 만든다. 현재 고정 스텝(Clone→Build→Deploy) 대신, 사용자가 Job을 선택하고 순서를 정의할 수 있어야 한다
3. **가이드형/자유형 분기**: 가이드형은 빌드/배포 템플릿을 선택하면 기본 설정이 채워지고, 자유형은 사용자가 모든 것을 직접 구성한다
4. **미들웨어 프리셋 참조**: 파이프라인이 미들웨어 프리셋을 참조하여, 어떤 CI/CD 도구를 사용할지 간접 지정한다

---

## 현재 구현 상태

| 항목 | 상태 | 비고 |
|------|------|------|
| Pipeline 엔티티 | 구현됨 | 고정 스텝 구조 |
| PipelineStep → PipelineJobExecution | 구현됨 | V20에서 리네이밍 완료 |
| PipelineEngine | 구현됨 | SAGA Orchestrator, Break-and-Resume, DAG 병렬 실행 |
| 202 Accepted | 구현됨 | 비동기 실행 + 추적 |
| SSE 실시간 알림 | 구현됨 | SseEmitterRegistry |
| Job 독립 엔티티 | 구현됨 | 단독 CRUD + 실행, Pipeline과 분리 |
| Job→Jenkins 동적 생성 | 구현됨 | Outbox+Reconciler, per-Job 파이프라인 (`playground-job-{id}`) |
| Job 프리셋 필수 | 구현됨 | `presetId` @NotNull, 프론트에서도 필수 선택 |
| Jenkins 통신 | 구현됨 | 관리(CRUD): 직접 HTTP, 실행(빌드 트리거): Kafka→Connect→Jenkins |
| 가이드형/자유형 | 미구현 | 자유형(Jenkinsfile 직접 작성)만 구현, 가이드형 템플릿 미구현 |
| GoCD | 제거됨 | ToolImplementation enum + DB에서 삭제 (미구현 구현체) |
| 기본 프리셋 | 구현됨 | "default" 프리셋 (VCS=GitLab) |

---

## 요구사항

### REQ-03-001: 파이프라인 독립 엔티티 [P0]

파이프라인을 티켓에 종속되지 않는 독립 엔티티로 재설계한다.

- 파이프라인 생성 시 티켓 ID 불필요
- 파이프라인 ID, 이름, 설명, 생성자, 프리셋 참조, 상태
- 파이프라인 목록 조회 (필터: 이름, 상태, 프리셋)
- 파이프라인 삭제 (실행 중이 아닐 때만)

### REQ-03-001-1: Job 독립 엔티티 원칙 [P0]

Job은 파이프라인과 독립된 엔티티이다. Job 자체는 실행 순서(execution_order)를 갖지 않으며, 단독 실행이 가능하다. 실행 순서는 파이프라인에서 Job들을 조합하여 DAG를 구성할 때 비로소 결정된다.

- Job 생성 시 `execution_order` 없음 (nullable) — 단독 실행 가능
- 파이프라인에 Job을 매핑할 때 `pipeline_job_mapping` 테이블에서 순서/의존관계 정의
- Job 타입은 BUILD, DEPLOY 두 가지만 사용 (PoC 범위)
- 테이블 구조:
  - `pipeline_job`: Job 정의 (이름, 타입, 프리셋, 설정) — `execution_order` nullable
  - `pipeline_job_mapping`: 파이프라인↔Job 매핑 (execution_order, 의존관계)
  - `pipeline_job_dependency`: DAG 엣지 (job_id → depends_on_job_id)

### REQ-03-002: Job 구성 관리 [P0]

파이프라인에 포함될 Job의 구성을 관리한다. 하나의 파이프라인은 N개의 Job을 순서대로 가진다.

- Job 추가/제거/순서 변경
- 각 Job은 타입(BUILD/DEPLOY), 순서(order), 설정(config JSON)을 가짐
- Job 간 의존성: 순차 실행 (현재 SAGA 패턴 유지)
- Job 설정은 타입별로 다른 스키마 (04-build-job, 06-deploy-job 참조)

### REQ-03-003: 가이드형 Job 생성 [P1]

미리 정의된 템플릿으로 Job을 생성하는 가이드형 모드를 제공한다.

- BUILD 템플릿: Git URL + 브랜치 + Build Script → 기본 빌드 Job 설정 자동 생성
- DEPLOY 템플릿: 배포 대상(VM/K8S) + 환경 → 기본 배포 Job 설정 자동 생성
- 템플릿 선택 → 필수 파라미터 입력 → Job config 자동 구성
- 생성 후 사용자가 config를 수정할 수 있음

### REQ-03-004: 자유형 Job 생성 [P1]

사용자가 Job의 모든 설정을 직접 정의하는 자유형 모드를 제공한다.

- Job 타입 선택 (BUILD/DEPLOY)
- config JSON을 직접 작성하거나, UI 폼으로 입력
- 검증: 필수 필드 존재 여부, CI/CD 어댑터 호환성 확인

### REQ-03-005: 미들웨어 프리셋 참조 [P0] — 구현됨

Job이 미들웨어 프리셋을 필수로 참조한다. 프리셋을 통해 어떤 Jenkins, Harbor, ArgoCD를 사용할지 간접 지정한다.

- **Job 생성 시 프리셋 필수** (`presetId` @NotNull, 프론트 필수 선택)
- 프리셋 수정 시 해당 프리셋을 참조하는 모든 Job에 자동 반영
- 기본 프리셋: "default" (VCS=GitLab)
- GoCD 구현체 제거됨 (미구현으로 ToolImplementation enum + DB에서 삭제)

### REQ-03-006: 파이프라인 실행 파라미터 [P1]

파이프라인 실행 시 런타임 파라미터를 전달할 수 있게 한다.

- 실행 요청에 key-value 파라미터 맵 포함
- 파라미터는 Job config에서 `${PARAM_NAME}` 형태로 참조
- 파이프라인 정의 시 파라미터 스키마(이름, 타입, 기본값, 필수 여부) 설정 가능
- 실행 시 필수 파라미터 누락 → 400 에러

### REQ-03-007: 파이프라인 실행 이력 [P1]

파이프라인 실행 내역을 독립적으로 관리한다.

- 실행(Execution) ID, 파이프라인 ID, 트리거 소스(MANUAL/TICKET/WORKFLOW), 트리거 ID
- 각 Job 실행 상태(PENDING→RUNNING→COMPLETED/FAILED), 시작/종료 시간
- 실행 파라미터 스냅샷 (실행 당시의 파라미터 기록)

### REQ-03-007-1: Job = Jenkins 파이프라인 동적 생성 [P0]

Job 생성 시 사용자가 Jenkinsfile 스크립트를 직접 작성하고, 시스템이 Jenkins에 파이프라인을 자동 등록한다. DB가 Source of Truth이고 Jenkins config.xml은 파생물이다.

- Job 생성 폼에 Jenkinsfile 스크립트 입력 (Declarative/Scripted Pipeline)
- 생성 시 DB에 `jenkins_script` 저장 + Outbox 이벤트(`JENKINS_JOB_CREATE`) 발행
- OutboxPoller가 비동기로 Jenkins API(`/createItem`)를 호출하여 파이프라인 등록
- Jenkins 등록 완료 → `jenkins_status = ACTIVE`, 실패 → 재시도 후 `FAILED`
- `jenkins_status`가 `ACTIVE`가 아니면 Job 실행 거부
- Jenkins 파이프라인 이름 규칙: `playground-job-{jobId}`
- 삭제 시 Jenkins 파이프라인도 함께 삭제 (Outbox `JENKINS_JOB_DELETE`)
- 스크립트 수정 시 Jenkins config.xml 업데이트 (Outbox `JENKINS_JOB_UPDATE`)
- 안전망: JenkinsReconciler(60초 주기)가 DB(desired)와 Jenkins(actual) 드리프트를 보정
- Jenkins에 push 시 `<sandbox>true</sandbox>` 적용 (보안)
- FAILED 상태의 Job은 프론트에서 "재시도" 가능 (`POST /api/jobs/{id}/retry-provision`)

### REQ-03-008: 파이프라인 복제 [P2]

기존 파이프라인의 Job 구성을 복사하여 새 파이프라인을 생성한다.

- 원본 파이프라인 ID → 새 이름으로 복제
- Job 구성, 파라미터 스키마 복사 (프리셋 참조는 선택적)
- 복제 후 독립적으로 수정 가능

### REQ-03-009: Job 의존성 그래프 [P0]

파이프라인 내 Job 간의 의존 관계를 DAG(Directed Acyclic Graph)로 정의한다. 순환 의존성을 가진 파이프라인은 생성/수정 시점에 거부되어야 한다.

- 각 Job은 `dependsOn` 목록으로 선행 Job을 지정한다
- DAG 검증: 저장 시점에 Kahn's algorithm으로 순환 탐지
- 연결성 검증: 모든 Job이 루트에서 도달 가능해야 한다 (단절 그래프 거부)
- dependsOn이 비어 있는 Job은 루트 Job으로 간주하여 즉시 실행 가능

### REQ-03-010: 병렬 Job 실행 [P0]

의존성이 충족된 Job들을 동시에 실행한다. Jenkins containerCap(3)에 맞춰 최대 3개 Job을 동시 실행한다.

- 위상 정렬(topological sort)로 실행 라운드를 결정한다
- 같은 라운드의 Job은 스레드 풀에서 병렬로 디스패치된다
- 실행 중인 Job이 containerCap에 도달하면 다음 ready Job은 대기한다
- 실패 격리: 하나의 Job이 실패하면 나머지 실행 중 Job은 완료를 기다리되, 새 Job은 디스패치하지 않는다
- 모든 Job 완료(성공/실패) 후 전체 결과를 판정한다

### REQ-03-011: 부분 파이프라인 복구 [P0]

DAG 실행 중 일부 Job이 실패했을 때 SAGA 역방향 위상 순서로 보상을 수행한다.

- 보상 순서: 완료된 Job을 역방향 위상 순서(leaf → root)로 보상한다
- 병렬 실행된 Job의 보상도 역순으로 직렬 처리한다 (보상 간 부수효과 충돌 방지)
- PENDING/SKIPPED 상태의 Job은 보상 대상에서 제외한다
- 보상 자체가 실패하면 COMPENSATION_FAILED로 기록하고 수동 개입을 요청한다

---

## 설계 고민 포인트

### 기존 PipelineStep → Job 전환

현재 PipelineStep(Clone/Build/Deploy)은 고정된 열거형이다. Job 구성 관리를 도입하면 Step 대신 Job이 동적으로 추가/제거된다. 마이그레이션 전략은 두 가지다:

1. **PipelineStep을 Job으로 리네이밍**: 기존 코드를 최소 변경. Step 열거형을 제거하고 Job 타입(BUILD/DEPLOY)으로 교체
2. **별도 Job 엔티티 추가**: PipelineStep은 실행 추적용으로 남기고, Job은 구성 정의용으로 분리

(1)이 단순하다. PipelineEngine의 SAGA 패턴은 유지하되, 스텝 목록이 하드코딩이 아닌 DB에서 로드되는 구조로 변경한다.

### 프리셋 참조 vs 도구 직접 지정

파이프라인이 "Jenkins-A를 사용한다"고 직접 지정하면 도구 교체 시 파이프라인을 하나씩 수정해야 한다. 프리셋을 참조하면 프리셋만 수정하면 되므로 UC-5(미들웨어 교체)가 자연스럽다. 단, 프리셋 없이 파이프라인을 실행하는 경우(테스트 등)를 위해 "실행 시 프리셋 override" 옵션도 필요하다.

### Jenkins 통신 경로: 관리 vs 실행

Jenkins와의 통신에는 두 가지 경로가 있다. 관리(CRUD)와 실행(빌드 트리거)이 각각 다른 메커니즘을 사용하는데, 이는 각 경로의 요구사항이 다르기 때문이다.

**관리 API (Job 생성/수정/삭제)** — Spring Boot가 `JenkinsAdapter`(RestTemplate)로 Jenkins REST API를 직접 호출한다. Outbox 패턴이 재시도와 내구성을 보장하므로 Kafka를 경유할 필요가 없다. JenkinsReconciler(60초 주기)가 DB와 Jenkins 간 드리프트를 보정하는 안전망 역할을 한다.

**실행 API (빌드 트리거)** — Spring Boot가 Kafka 토픽(`commands.jenkins`)에 커맨드를 발행하면, Redpanda Connect(`jenkins-command.yaml`)가 이를 소비하여 Jenkins를 호출한다. Jenkins 일시 장애 시에도 커맨드가 유실되지 않고 재처리된다.

### 가이드형과 자유형의 경계

가이드형은 결국 자유형의 "프리필드된 버전"이다. 내부적으로는 같은 Job config를 생성하되, 가이드형 API가 사용자 입력을 config로 변환하는 역할을 한다. 따라서 가이드형 전용 엔티티를 만들 필요 없이, 가이드형 API → Job config 생성 → 저장이라는 서비스 레이어 로직으로 충분하다.
