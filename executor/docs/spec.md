# Executor v2 스펙

> 2026-04-09 기준 현재 구현은 이 문서보다 앞서 있다.
> 실제 코드는 `operator` 스키마 cross-schema 조회, Jenkins 인스턴스별 그룹 디스패치, `QUEUED` stale recovery, scheduler thread pool 분리, Avro retry-topic 재발행 경로를 포함한다.
> 또한 본문 초반의 "3초 폴링 스케줄러 제거" 설명은 현재 live 코드와 다르다.

## Context

Executor는 operator에서 전달받은 Job을 내부 테이블에 저장하고, 우선순위 기반으로 Jenkins에 즉시 실행 가능한지 판단한 뒤 실행하는 **실행 제어 계층**이다.

v1 대비 핵심 변경:
- **3초 폴링 스케줄러 제거** → 이벤트 드리븐(job 수신, start/end 컨슘 시점에만 디스패치 판단)
- **Jenkins 즉시 실행 가능 여부 판단** — 큐가 비어있고 유휴 executor가 있을 때만 디스패치
- **우선순위 기반 정렬** — PRIORITY + PRIORITY_DT 순서
- **멀티인스턴스 대비** — executor Pod 수평 확장 + 다중 Jenkins

---

## 1. 테이블 스키마 (TB_TPS_EX_003)

| # | 컬럼 | 타입 | Null | 기본값 | 키 | 설명 |
|---|------|------|------|--------|-----|------|
| 1 | JOB_EXCN_ID | VARCHAR(20) | N | - | PK, FK→EX_002 | Job 실행 건 식별자 (op의 job_execution과 1:1) |
| 2 | PIPELINE_EXCN_ID | VARCHAR(20) | Y | NULL | FK→EX_001 | 파이프라인 실행 건. 단건 실행 시 NULL |
| 3 | JOB_ID | VARCHAR(20) | N | - | FK→통합작업관리 | 실행 대상 Job 정의 (pipeline_job) |
| 4 | BUILD_NO | INT | Y | NULL | | Jenkins 빌드번호 (실행 후 채번) |
| 5 | EXCN_STTS | VARCHAR(16) | N | PENDING | | 실행 상태 |
| 6 | PRIORITY | INT | N | 1 | | 우선순위 (낮을수록 먼저). 동일 시 PRIORITY_DT 순 |
| 7 | PRIORITY_DT | DATETIME(6) | N | - | | 우선순위 기준 시각 (EX_001.REG_DT 비정규화) |
| 8 | RETRY_CNT | INT | N | 0 | | 재시도 횟수 |
| 9 | BGNG_DT | DATETIME(6) | Y | NULL | | 실행 시작일시 (JobListener start) |
| 10 | END_DT | DATETIME(6) | Y | NULL | | 실행 종료일시 (JobListener end) |
| 11 | LOG_FILE_YN | CHAR(1) | N | N | | 로그파일 적재 성공 여부 |
| 12 | REG_DT | DATETIME(6) | N | CURRENT_TIMESTAMP(6) | | 행 생성일시 |
| 13 | RGTR_ID | VARCHAR(10) | Y | NULL | | 등록자 |
| 14 | MDFCN_DT | DATETIME(6) | N | CURRENT_TIMESTAMP(6) | | 수정일시 |
| 15 | MDFR_ID | VARCHAR(10) | N | NULL | | 수정자 |

### 상태 전이

```
PENDING → QUEUED → RUNNING → (Jenkins 실제 상태와 동일)
```

| 상태 | 의미 | 진입 조건 |
|------|------|----------|
| PENDING | 수신 완료, 디스패치 대기 | op에서 CMD_JOB_DISPATCH 수신 시 |
| QUEUED | 실행 토픽 발행 완료, Jenkins 실행 대기 | 즉시 실행 가능 판단 후 CMD_JOB_EXECUTE 발행 |
| RUNNING | Jenkins에서 빌드 실행 중 | EVT_JOB_STARTED 컨슘 시 |
| SUCCESS | 빌드 성공 | EVT_JOB_COMPLETED (result=SUCCESS) |
| FAILURE | 빌드 실패 | EVT_JOB_COMPLETED (result=FAILURE) |
| UNSTABLE | 불안정 (테스트 실패 등) | EVT_JOB_COMPLETED (result=UNSTABLE) |
| ABORTED | 사용자 중단 | EVT_JOB_COMPLETED (result=ABORTED) |
| NOT_BUILT | 빌드되지 않음 | EVT_JOB_COMPLETED (result=NOT_BUILT) |
| NOT_EXECUTED | 조건 미충족 미실행 | EVT_JOB_COMPLETED (result=NOT_EXECUTED) |

> PENDING~RUNNING은 executor 내부 상태, 이후 터미널 상태는 Jenkins 실제 상태와 1:1 매핑

---

## 2. 실행 흐름

### 2.1 전체 흐름

```
Op (간략)                    Executor (핵심)                        Jenkins
─────────                    ───────────────                        ───────
Job 실행 요청
 └→ Outbox → Kafka
    [CMD_JOB_DISPATCH]
                              ① JobReceiver
                                └→ EX_003 INSERT (WAITING)
                                └→ tryDispatch() 호출  ←── 트리거 ①

                              ② DispatchEvaluator.tryDispatch()
                                └→ WAITING 목록 조회 (PRIORITY, PRIORITY_DT 순)
                                └→ Jenkins 즉시 실행 가능? ────→ Jenkins API 조회
                                   ├─ YES → CMD_JOB_EXECUTE 발행, QUEUED 전환
                                   └─ NO  → 그대로 WAITING 유지

    [CMD_JOB_EXECUTE]
                              ③ JobExecuteConsumer
                                └→ Jenkins API 트리거 ──────→ buildWithParameters
                                └→ BUILD_NO 채번

                                                              빌드 시작
                                                              Jenkins JobListener
    [EVT_JOB_STARTED]  ←─────────────────────────────────── rpk produce
                              ④ JobStartedConsumer
                                └→ RUNNING 전환, BGNG_DT 기록
                                └→ tryDispatch() 호출  ←── 트리거 ②

                                                              빌드 종료
                                                              Jenkins JobListener
    [EVT_JOB_COMPLETED] ←──────────────────────────────── rpk produce
                              ⑤ JobCompletedConsumer
                                └→ SUCCESS/FAILED/ABORTED 전환, END_DT 기록
                                └→ tryDispatch() 호출  ←── 트리거 ③
```

### 2.2 디스패치 트리거 (3가지만)

스케줄러 없이, 다음 3가지 이벤트에서만 `tryDispatch()`를 호출한다:

| # | 트리거 | 이유 |
|---|--------|------|
| ① | Job 수신 (CMD_JOB_DISPATCH 컨슘) | 새 Job이 도착 → 빈 슬롯이 있으면 바로 실행 |
| ② | Job 시작 (EVT_JOB_STARTED 컨슘) | Jenkins가 빌드를 시작함 → 슬롯 상태 변경 확인 |
| ③ | Job 종료 (EVT_JOB_COMPLETED 컨슘) | 슬롯 하나 반납 → 대기 중인 Job 실행 가능 |

### 2.3 즉시 실행 가능 판단 (Jenkins API)

Jenkins API 문서 기준, 두 조건을 모두 만족해야 즉시 실행 가능:

```
즉시 실행 가능 = (queue.items.length == 0) AND (busyExecutors < totalExecutors)
```

**호출 순서**:
1. `GET /queue/api/json` → `items`가 비어있는지 확인
2. `GET /computer/api/json` → `busyExecutors < totalExecutors` 확인

**Jenkins 모드 자동 감지 + 슬롯 판단**:

`GET /computer/api/json?tree=computer[_class],busyExecutors,totalExecutors`로 자동 감지:

```
computer[]._class에 "kubernetes"가 포함된 노드가 있는가?

YES → K8S Dynamic Pod
  → maxExecutors는 앱 설정값(dynamic-k8s-dispatch-capacity)으로 보정

NO → VM / 정적 Agent
  → Jenkins API: totalExecutors를 최대 실행 개수로 사용
```

- 감지 결과는 Jenkins 인스턴스별로 **캐싱** (매번 호출 불필요)
- Purpose에 Jenkins만 연결하면 되고, mode 설정 불필요
- `_class` 예시: `org.csanchez.jenkins.plugins.kubernetes.KubernetesComputer`

**Jenkins API 호출 실패 시**:
- 정책 미정 (추후 결정). 현재는 실행하지 않고 PENDING 유지

---

## 3. 멀티인스턴스 대비

### 3.1 Executor Pod 수평 확장

여러 executor Pod가 동시에 `tryDispatch()`를 실행할 수 있으므로:
- **FOR UPDATE SKIP LOCKED**: WAITING Job 선택 시 동일 Job을 중복 디스패치하지 않음
- **낙관적 잠금(@Version)**: 상태 전이 시 동시 수정 방지

### 3.2 다중 Jenkins 인스턴스

- Job이 어떤 Jenkins에서 실행되는지는 **Job 정의(pipeline_job) → Purpose → PurposeEntry(CI_CD_TOOL) → SupportTool**로 결정
- 동적 Pod Jenkins는 `executor.dynamic-k8s-dispatch-capacity` 설정값으로 슬롯 상한 관리
- 디스패치 시 해당 Jenkins 인스턴스의 가용 슬롯을 개별 확인

---

## 4. Kafka 토픽

| 토픽 | 방향 | 발행자 | 소비자 | 용도 |
|------|------|--------|--------|------|
| `EXECUTOR_CMD_JOB_DISPATCH` | Op→Exec | Op(간략) | JobReceiver | Job 수신 |
| `EXECUTOR_CMD_JOB_EXECUTE` | Exec 내부 | DispatchEvaluator | JobExecuteConsumer | Jenkins 트리거 명령 |
| `EXECUTOR_EVT_JOB_STARTED` | Jenkins→Exec | Jenkins JobListener(rpk) | JobStartedConsumer | 빌드 시작 알림 |
| `EXECUTOR_EVT_JOB_COMPLETED` | Jenkins→Exec | Jenkins JobListener(rpk) | JobCompletedConsumer | 빌드 종료 알림 |

---

## 5. 핵심 컴포넌트

### 5.1 JobReceiver
- `CMD_JOB_DISPATCH` 소비
- 멱등성 체크 (JOB_EXCN_ID 중복 방지)
- EX_003 INSERT (WAITING)
- `tryDispatch()` 호출

### 5.2 DispatchEvaluator
- WAITING 상태 Job 조회 (ORDER BY PRIORITY ASC, PRIORITY_DT ASC)
- Jenkins별 가용 슬롯 확인 (Jenkins API + app 레벨)
- 실행 가능한 Job에 대해 CMD_JOB_EXECUTE 발행 + QUEUED 전환
- `FOR UPDATE SKIP LOCKED`로 멀티인스턴스 안전 보장

### 5.3 JobExecuteConsumer
- `CMD_JOB_EXECUTE` 소비
- Jenkins API `buildWithParameters` 호출
- `nextBuildNumber` 트릭으로 BUILD_NO 채번
- 트리거 실패 시 retry 또는 FAILED 전환

### 5.4 JobStartedConsumer
- `EVT_JOB_STARTED` 소비 (Jenkins Groovy rpk produce)
- RUNNING 전환 + BGNG_DT 기록
- `tryDispatch()` 호출 (슬롯 변동 반영)

### 5.5 JobCompletedConsumer
- `EVT_JOB_COMPLETED` 소비 (Jenkins Groovy rpk produce)
- SUCCESS/FAILED/ABORTED 전환 + END_DT 기록
- `tryDispatch()` 호출 (슬롯 반납 → 대기 Job 실행)

---

## 6. Jenkins API 사용 요약

| 목적 | API | 필드 |
|------|-----|------|
| 큐 비어있는지 | `GET /queue/api/json` | `items.length == 0` |
| 유휴 executor 있는지 | `GET /computer/api/json` | `busyExecutors < totalExecutors` |
| 빌드 트리거 | `POST /{job}/buildWithParameters` | Location 헤더 → queueId |
| 빌드번호 획득 | `GET /{job}/api/json` | `nextBuildNumber` |
| Pre-trigger Guard | `GET /{job}/api/json` + `GET /{job}/lastBuild/api/json` | `inQueue`, `building` |
| 인증 | Basic Auth + CSRF Crumb | `GET /crumbIssuer/api/json` |

---

## 7. Op/Jenkins 측 (간략 구성)

### Op (operator-stub) — 최소 구현
- REST API로 파이프라인/단독 Job 실행 트리거
- `CMD_JOB_DISPATCH` Outbox 발행
- `EVT_JOB_STARTED/COMPLETED` 소비하여 상태 갱신
- 현재 코드 기반으로 스키마만 맞춤

### Jenkins JobListener — Groovy/rpk
- Jenkinsfile의 `post { always { ... } }` 또는 Shared Library에서 `rpk produce`
- start: 빌드 시작 시 `EVT_JOB_STARTED` 토픽에 executionJobId, buildNo 발행
- end: 빌드 종료 시 `EVT_JOB_COMPLETED` 토픽에 executionJobId, result, duration 발행

---

## 8. 아키텍처: Hexagonal (Port/Adapter)

### 8.1 설계 원칙

| 원칙 | 설명 |
|------|------|
| **순수 도메인 모델** | domain 패키지에 JPA 어노테이션 없음. 프레임워크 독립적인 POJO |
| **JPA Entity = Infrastructure** | `@Entity` 클래스는 infrastructure/persistence에 위치 |
| **Port = 도메인이 선언하는 인터페이스** | 도메인이 외부에 "이런 기능이 필요하다"고 선언. in-port(유스케이스), out-port(저장소/메시징) |
| **Adapter = Infrastructure가 Port를 구현** | JPA Repository, Kafka Consumer/Producer가 Port를 구현 |
| **Application = 도메인 서비스 오케스트레이션** | 직접 비즈니스 로직을 갖지 않고, 도메인 서비스들을 조합하여 유스케이스 완성 |

### 8.2 의존 방향

```
api (inbound adapter)
  → application (use case orchestration)
    → domain (pure model + port interfaces + domain service)
      ← infrastructure (outbound adapter: implements out-port)

외부 → api → application → domain ← infrastructure ← 외부
```

- domain은 어떤 것도 import하지 않음 (JPA, Kafka, Spring 제외 — 순수 Java)
- infrastructure가 domain의 port를 구현 (역의존)
- application은 port 인터페이스를 통해 infrastructure와 소통

### 8.3 dispatch 패키지 구조 (실행 컨트롤러)

```
dispatch/
├── api/                                    ── Inbound Adapter ──
│   └── DispatchController.java              REST API (Job 조회)
│
├── domain/                                 ── 순수 도메인 ──
│   ├── model/
│   │   ├── ExecutionJob.java                순수 도메인 모델 (JPA 없음)
│   │   └── ExecutionJobStatus.java          상태 enum + 전이 검증
│   ├── service/
│   │   └── DispatchService.java             도메인 로직 (우선순위 판단, 상태 전이)
│   └── port/
│       ├── in/
│       │   ├── ReceiveJobUseCase.java       Job 수신 유스케이스
│       │   └── EvaluateDispatchUseCase.java tryDispatch 유스케이스
│       └── out/
│           ├── LoadExecutionJobPort.java     Job 조회 (out-port)
│           ├── SaveExecutionJobPort.java     Job 저장 (out-port)
│           ├── PublishExecuteCommandPort.java CMD_JOB_EXECUTE 발행 (out-port)
│           └── CheckSlotAvailablePort.java   Jenkins 슬롯 확인 (out-port, runner.infra가 구현)
│
├── application/                            ── 유스케이스 오케스트레이션 ──
│   ├── ReceiveJobService.java               ReceiveJobUseCase 구현 (도메인 서비스 조합)
│   └── DispatchEvaluatorService.java        EvaluateDispatchUseCase 구현
│
└── infrastructure/                         ── Outbound Adapter ──
    ├── persistence/
    │   ├── ExecutionJobEntity.java           JPA Entity (@Entity, @Table)
    │   ├── ExecutionJobJpaRepository.java    Spring Data JPA (FOR UPDATE SKIP LOCKED)
    │   ├── ExecutionJobPersistenceAdapter.java LoadExecutionJobPort + SaveExecutionJobPort 구현
    │   └── ExecutionJobMapper.java           Entity ↔ Domain Model 매핑
    └── messaging/
        ├── JobDispatchConsumer.java          Kafka Consumer (inbound, CMD_JOB_DISPATCH)
        └── ExecuteCommandPublisher.java     PublishExecuteCommandPort 구현 (outbound)
```

### 8.4 runner 패키지 구조 (실행 수행기) — 향후 구현

```
runner/
├── domain/                                 (향후 추가 문서 기반으로 정의)
├── application/                            (향후 추가 문서 기반으로 구현)
└── infrastructure/
    ├── jenkins/
    │   └── JenkinsClient.java               dispatch의 CheckSlotAvailablePort 구현
    └── messaging/
        ├── JobExecuteConsumer.java
        ├── JobStartedConsumer.java
        └── JobCompletedConsumer.java
```

### 8.5 패키지 간 의존

```
dispatch.api          → dispatch.application (in-port 호출)
dispatch.application  → dispatch.domain (model + service + port)
dispatch.infrastructure.persistence → dispatch.domain.port.out (구현)
dispatch.infrastructure.messaging   → dispatch.application (Consumer → UseCase 호출)
                                    → dispatch.domain.port.out (Publisher 구현)

runner.infrastructure.jenkins → dispatch.domain.port.out (CheckSlotAvailablePort 구현)
```

- dispatch.domain은 **어떤 외부 패키지도 참조하지 않음** (순수)
- CheckSlotAvailablePort는 dispatch.domain.port.out에 선언, runner.infrastructure가 구현
- 도메인 간 의존 없음 — Port를 통한 간접 연결만 존재

### 8.6 변경 대상 파일 (기존 → 신규)

| 기존 | 변경 | 이유 |
|------|------|------|
| `dispatch/domain/ExecutionJob.java` | `dispatch/domain/model/ExecutionJob.java` | JPA 제거 → 순수 모델 |
| `dispatch/domain/ExecutionJobStatus.java` | `dispatch/domain/model/ExecutionJobStatus.java` | 위치 이동 |
| `dispatch/domain/ExecutionJobRepository.java` | 삭제 → `domain/port/out/` + `infrastructure/persistence/` | Port/Adapter 분리 |
| `dispatch/application/DispatchEvaluator.java` | `dispatch/application/DispatchEvaluatorService.java` | 오케스트레이션으로 전환 |
| `dispatch/application/JobReceiveService.java` | `dispatch/application/ReceiveJobService.java` | UseCase 구현체로 전환 |
| (없음) | `dispatch/domain/service/DispatchService.java` | 도메인 로직 분리 |
| (없음) | `dispatch/infrastructure/persistence/*` | JPA Entity + Adapter + Mapper |
| (없음) | `dispatch/infrastructure/messaging/ExecuteCommandPublisher.java` | out-port 구현 |

---

## 9. 개발방법론 문서 적재

| 문서 | 위치 | 내용 |
|------|------|------|
| Hexagonal Architecture 가이드 | `.claude/skills/redpanda-playground/references/architecture/hexagonal-guide.md` | Port/Adapter 패턴 원칙, 패키지 구조 규칙, 의존 방향 |
| Executor 아키텍처 | `executor/docs/architecture.md` | executor 모듈 구체 적용 (dispatch/runner 구조) |

---

## 10. 타임아웃 감지 (UNKNOWN 상태)

이벤트 드리븐 설계의 유일한 예외. RUNNING 상태에서 일정 시간 EVT_JOB_COMPLETED가 오지 않는 Job을 대상으로 **별도 스케줄러**가 동작한다.

**흐름**:
1. 주기적 스케줄러(예: 60초)가 RUNNING 상태 + `BGNG_DT`가 임계값 초과인 Job 조회
2. 해당 Job의 Jenkins API(`GET /{job}/{buildNo}/api/json`)로 빌드 상태 검증 시도
3. 검증 성공 → 실제 상태(SUCCESS/FAILED/ABORTED)로 전환
4. 검증 실패(Jenkins 응답 불가) → UNKNOWN 전환

---

## 10. Jenkins Crumb 인증 전략

**캐싱 + 실패 시 재발급** 방식 채택.

```
JenkinsClient 내부:
  crumbCache: Map<jenkinsInstanceId, CrumbSession>

  요청 시:
    1. cache hit → 캐싱된 crumb+cookie로 요청
    2. 403 "No valid crumb" → cache invalidate → crumb 재발급 → 재시도 1회
    3. cache miss → crumb 발급 → 캐싱 → 요청
```

**최적화**: API Token 인증 시 crumb이 불필요한 Jenkins 설정이 있다. 첫 요청에서 crumb 없이 시도하고, 403이 오면 crumb 모드로 전환하여 이후 캐싱. 불필요한 `/crumbIssuer` 호출을 제거할 수 있다.

---

## 11. 미결 사항

| # | 질문 | 영향 |
|---|------|------|
| 1 | ABORTED 상태 — 사용자 중단 API를 executor에서 제공하는지, op에서 제공하는지 | executor API 범위 |
| 2 | LOG_FILE_YN — 로그 적재는 executor가 하는지, 별도 서비스가 하는지 | 컴포넌트 경계 |

---

## 10. 검증 방법

1. `./gradlew :executor:bootRun`
2. `curl -X POST localhost:8072/api/operator/jobs/execute?jobName=executor-test` (op-stub)
3. Redpanda Console에서 토픽 메시지 확인
4. `curl localhost:8071/api/executor/jobs` — 상태 추적
5. Jenkins 빌드 시작/종료 시 EVT 토픽 발행 확인
