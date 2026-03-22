# Phase 3: DAG 엔진 고도화 리뷰

> **상태**: ✅ 전체 구현 완료 (2026-03-20~22)
> **관련 커밋**: `1c6af72` (Phase 2 고도화), `1aa9ca1` (Phase 3 백엔드), `6c90d2d` (Phase 3 프론트엔드), `e10725a` (Nexus 로직)

Phase 2에서 구현한 DAG 엔진의 프로덕션 견고성과 기능 완성도를 높이는 작업이다. 7개 Feature를 의존 순서대로 구현했다. 모든 DAG 관련 클래스는 `pipeline.dag.*` 서브패키지에 위치한다.

## 구현 순서와 의존 관계

```
#2 설정 외부화 ← 모든 feature의 기반
  ↓
#1 크래시 복구   #4 Stale 정리 강화   #3 Job 재시도
  ↓                                    ↓
#6 실패 정책 ← DagExecutionState 확장 필요
  ↓
#5 부분 재시작 ← #6의 SKIP 로직 활용
  ↓
#7 DAG 이벤트 ← 독립적, 마지막
```

---

## Feature #2: 설정 외부화

> ✅ **구현 완료**

하드코딩된 상수 4개를 `application.yml`의 `pipeline.*` 프로퍼티로 추출했다.

**새 파일:** `pipeline.config.PipelineProperties.java` — `@ConfigurationProperties(prefix = "pipeline")` record. compact canonical constructor에서 기본값 검증을 수행하고, 무인자 생성자로 폴백을 제공한다.

**변경 내용:**
- `PipelineConfig.java` — `@EnableConfigurationProperties` 추가, `jobExecutorPool` 빈이 `props.maxConcurrentJobs()`를 사용하도록 변경. `retryScheduler` 빈 추가.
- `DagExecutionCoordinator.java` — `MAX_CONCURRENT_JOBS` 상수 제거, `PipelineProperties` 주입.
- `WebhookTimeoutChecker.java` — `TIMEOUT_MINUTES` 상수 제거, `PipelineProperties` 주입.
- `application.yml` — `pipeline:` 섹션 추가 (max-concurrent-jobs: 3, webhook-timeout-minutes: 5, job-max-retries: 2, stale-execution-timeout-minutes: 30).

**설계 결정:** record를 사용한 이유는 불변성 보장과 boilerplate 제거다. Spring Boot 3.x에서 record 기반 `@ConfigurationProperties`를 정식 지원한다.

---

## Feature #1: 크래시 복구 (@PostConstruct)

> ✅ **구현 완료**

앱 재시작 시 DB의 RUNNING 실행을 자동 재개한다.

**변경 내용:**
- `DagExecutionCoordinator.java` — `@PostConstruct recoverRunningExecutions()` 추가.
- `PipelineExecutionMapper.java/.xml` — `findByStatus(String status)` 추가.

**복구 로직:**
1. DB에서 `status=RUNNING` 실행 조회
2. DAG 모드(`pipelineDefinitionId != null`)만 처리
3. job execution 상태로 `DagExecutionState` 재구성
4. RUNNING/WAITING_WEBHOOK 상태 job → FAILED 전환 (webhook 유실 가정)
5. 실패가 있으면 `handleFailure()`, 없으면 ready job 재dispatch

**보수적 접근의 이유:** 크래시 시점에 Jenkins가 빌드를 완료했더라도 webhook이 유실되면 결과를 알 수 없다. FAILED 처리 후 부분 재시작 API(Feature #5)로 재개하는 게 안전하다.

---

## Feature #4: Stale 실행 정리 강화

> ✅ **구현 완료**

`cleanupStaleExecutions()`를 확장하여 `staleExecutionTimeoutMinutes` 초과 RUNNING 실행을 자동 FAILED 처리한다.

기존에는 메모리에서 `isAllDone()` 상태만 정리했지만, 이제 DB에서 장시간 RUNNING 상태인 실행도 감지한다. 비종료 상태의 모든 job을 FAILED로 마킹하고 실행을 FAILED 처리한다.

---

## Feature #3: Job 재시도 (Exponential Backoff)

> ✅ **구현 완료**

동기 실행 실패 시 `maxRetries`까지 자동 재시도한다.

**DB 변경:** `V32__add_retry_count_to_job_execution.sql` — `retry_count INTEGER NOT NULL DEFAULT 0` 컬럼 추가.

**변경 내용:**
- `PipelineJobExecution.java` — `retryCount` 필드 추가.
- `PipelineJobExecutionMapper.java/.xml` — `incrementRetryCount()` 추가, SELECT/INSERT에 `retry_count` 반영.
- `DagExecutionCoordinator.executeJob()` — catch 블록에 재시도 로직. `ScheduledExecutorService`로 exponential backoff delay 적용.
- `PipelineConfig.java` — `retryScheduler` 빈 추가.

**핵심 규칙:**
- 동기 실행 예외만 재시도한다. webhook timeout은 Jenkins가 아직 실행 중일 수 있으므로 재시도 불가.
- delay = 2^retryCount 초 (1s, 2s, 4s).
- 초과 시 FAILED → 보상 흐름 진입.
- 재시도 전 `incrementRetryCount()`로 DB에 횟수를 기록하고, 상태를 PENDING으로 되돌린다.

---

## Feature #6: 실패 정책 (Failure Policy)

> ✅ **구현 완료**

파이프라인 정의별로 실패 시 동작을 제어한다.

**DB 변경:** `V33__add_failure_policy_to_pipeline_definition.sql` — `failure_policy VARCHAR(30) NOT NULL DEFAULT 'STOP_ALL'` 컬럼 추가.

**새 파일:** `pipeline.dag.domain.FailurePolicy.java` — `STOP_ALL`, `SKIP_DOWNSTREAM`, `FAIL_FAST` 3가지 정책.

**변경 내용:**
- `PipelineDefinition.java` — `failurePolicy` 필드 추가.
- `DagExecutionState.java` — `skippedJobIds` Set, `allDownstream()` 메서드, `failurePolicy` 필드, `isAllDone()`에 skipped 포함, `isTerminated()`, `failedJobIds()` 접근자 추가.
- `DagExecutionCoordinator.java` — `handleFailure()`에서 정책별 분기: `handleStopAll()`, `handleSkipDownstream()`, `handleFailFast()`.
- `PipelineDefinitionMapper.xml` — `failure_policy` 컬럼 매핑.

**SKIP_DOWNSTREAM 핵심:** 실패한 Job의 전이적 하위(successor graph BFS)만 SKIP한다. RUNNING 중인 Job은 건드리지 않고, 완료 후 `onJobCompleted()`에서 재평가한다. 독립 브랜치는 계속 실행된다. diamond DAG에서 한 브랜치가 실패해도 다른 브랜치가 계속 진행될 수 있어 전체 실행 시간을 절약한다.

### SAGA 보상 처리 상세

실패 정책이 보상 흐름을 트리거하는 과정과, 현재 구현의 동작 범위 및 한계를 정리한다.

#### 보상 흐름

`finalizeExecution()`에서 `state.hasFailure()`가 true이면 보상 흐름에 진입한다.

```
finalizeExecution(executionId, state)
  ├── state.hasFailure() == true
  │     ├── completedJobIdsInReverseTopologicalOrder()
  │     │     BFS 위상 정렬 → 역순 (leaf → root)
  │     │     대상: completedJobIds 전체 (필터링 없음)
  │     │
  │     └── compensateDag(execution, reverseJobIds, state)
  │           for each jobId in reverseJobIds:
  │             1. jobExecution 조회 (status == SUCCESS인 것만 진행)
  │             2. executor.compensate(execution, je) 호출
  │             3. 상태를 COMPENSATED로 변경 + 이벤트 발행
  │             4. 실패 시 COMPENSATION_FAILED 기록 + MANUAL INTERVENTION 로그
  │
  └── state.hasFailure() == false
        → SUCCESS 처리 (보상 없음)
```

`compensateDag()` 코드 (`DagExecutionCoordinator.java:668-697`):

```java
for (Long jobId : reverseJobIds) {
    var je = jobExecutionMapper.findByExecutionIdAndJobOrder(execution.getId(), jobOrder);
    if (je == null || je.getStatus() != JobExecutionStatus.SUCCESS) continue;

    try {
        executor.compensate(execution, je);           // default no-op
        jobExecutionMapper.updateStatus(je.getId()
                , JobExecutionStatus.COMPENSATED.name()
                , "Compensated after DAG saga rollback", ...);
    } catch (Exception e) {
        // COMPENSATION_FAILED → MANUAL INTERVENTION REQUIRED
    }
}
```

#### 보상 범위: 어떤 Job이 보상 대상이 되는가

현재 구현은 **`completedJobIds` 전체**를 보상 대상으로 취급한다. Job 단위로 보상 필요 여부를 구분하는 메커니즘은 없다.

**예시: `1→2→3→4→5` 체인에서 Job 4 실패 시**

```
Job 1: SUCCESS → COMPENSATED (executor.compensate() 호출, default no-op)
Job 2: SUCCESS → COMPENSATED (executor.compensate() 호출, default no-op)
Job 3: SUCCESS → COMPENSATED (executor.compensate() 호출, default no-op)
Job 4: FAILED  → 그대로 유지
Job 5: SKIPPED → PENDING이었으므로 SKIPPED 처리
```

보상 순서는 역방향 위상 순서(3→2→1)를 따른다. 실제 `PipelineJobExecutor.compensate()`는 default no-op이므로 부수효과 롤백은 일어나지 않고, DB 상태만 COMPENSATED로 변경된다.

**FailurePolicy별 보상 범위 차이:**

| 정책 | RUNNING job 처리 | PENDING job 처리 | 보상 대상 |
|------|-----------------|-----------------|----------|
| STOP_ALL | 완료 대기 | 전부 SKIP | 모든 completed (성공한 것) |
| SKIP_DOWNSTREAM | 유지 | 실패 job 하위만 SKIP | 모든 completed (독립 브랜치 포함) |
| FAIL_FAST | 유지 | 전부 즉시 SKIP | 모든 completed |

SKIP_DOWNSTREAM에서 독립 브랜치가 성공하면 해당 브랜치 Job도 completed에 포함되어 보상 대상이 된다. 보상이 필요 없는 읽기 전용 Job이라도 COMPENSATED로 마킹된다.

#### executor.compensate()의 no-op 설계

`PipelineJobExecutor` 인터페이스의 `compensate()`는 default no-op으로 제공된다:

```java
default void compensate(PipelineExecution execution
        , PipelineJobExecution jobExecution) throws Exception {
    // 기본값은 no-op — 부수효과를 되돌려야 하는 Job에서 오버라이드
}
```

이 설계의 의도는 "읽기 전용이거나 멱등한 Job은 기본 구현을 그대로 사용하고, 부수효과를 되돌려야 하는 Job만 오버라이드한다"는 것이다. 그러나 no-op 여부와 무관하게 **모든 completed job이 COMPENSATED 상태로 전환**된다는 점에서, UI에서는 실제 롤백이 일어나지 않은 Job도 "보상 처리됨"으로 표시된다.

#### 보상 실패 처리

`executor.compensate()` 호출 중 예외가 발생하면:
1. 해당 Job은 `FAILED` 상태로 전환 (COMPENSATED가 아님)
2. 로그 메시지: `"COMPENSATION_FAILED: " + e.getMessage()`
3. `[DAG-SAGA] Compensation FAILED for job: {} - MANUAL INTERVENTION REQUIRED` 경고 로그
4. **자동 재시도 없음** — 수동 개입 필요

현재는 보상 실패 시 알림(Slack, 이메일 등)이 없으므로, 운영 환경에서는 로그 모니터링이 필수다.

#### 현재 한계와 개선 방향

**한계:**
- per-job 보상 필요 여부를 구분할 수 없다. BUILD(빌드 아티팩트 생성)와 같이 부수효과가 있는 Job과, 읽기 전용 검증 Job이 동일하게 COMPENSATED 처리된다.
- UI에서 "보상 처리됨" 표시가 실제 롤백 여부를 반영하지 못한다.
- 보상 범위를 제한할 수 없다(예: "이 Job은 보상 불필요" 설정).

**개선안:**

| 방안 | 설명 | 장점 | 단점 |
|------|------|------|------|
| A. `CompensationPolicy` enum | Job 정의에 `NONE`/`CUSTOM` 설정. NONE이면 compensate() 호출 안 하고 SUCCESS 유지 | DB 수준 설정, 유연함 | 스키마 변경 필요 |
| B. `isCompensatable()` 메서드 | executor 인터페이스에 `default boolean isCompensatable() { return false; }` 추가. true인 executor만 보상 | 코드만 변경, 스키마 불변 | Job 타입 단위 제어 (인스턴스 단위 불가) |
| C. 혼합 | B로 executor 기본 판단 + A로 per-job 오버라이드 | 최대 유연성 | 복잡도 증가 |

현재 단계에서는 모든 Job이 BUILD 타입이고 Jenkins에 위임하므로 실질적 차이가 없다. Job 타입이 다양해지면(DEPLOY, IMPORT, ARTIFACT_DOWNLOAD 등) 개선이 필요하다.

---

## Feature #5: 부분 재시작

> ✅ **구현 완료**

FAILED 실행에서 SUCCESS job은 건너뛰고 FAILED/PENDING만 재실행한다.

**새 API:** `POST /api/pipelines/{definitionId}/executions/{executionId}/restart`

**변경 내용:**
- `PipelineDefinitionController.java` — restart 엔드포인트 추가.
- `PipelineDefinitionService.java` — `restart()` 메서드: 이전 실행의 job execution을 조회하여 SUCCESS는 결과 복사, 나머지는 PENDING으로 새 실행 생성.
- `PipelineJobExecutionMapper.xml` — `insertBatch`에서 `'PENDING'` 하드코딩 → `#{je.status}` 변경 (기존 호출은 PENDING 설정하므로 역호환).
- `DagExecutionCoordinator.startExecution()` — 이미 SUCCESS인 job을 `state.markCompleted()`로 사전 등록.

**동작 원리:** 새 실행이 시작되면 DAG 엔진이 의존성을 평가할 때 이미 SUCCESS인 job은 완료된 것으로 간주하여 건너뛰고, 의존성이 충족된 PENDING job부터 dispatch한다.

---

## Feature #7: DAG 실행 이벤트 (Avro)

> ✅ **구현 완료**

Job dispatch/completion 시 상세 이벤트를 발행한다. 프론트엔드 DAG 실시간 시각화용.

**새 Avro 스키마:**
- `DagJobDispatchedEvent.avsc` — executionId, jobId, jobName, jobType, jobOrder, dispatchedAt
- `DagJobCompletedEvent.avsc` — + status, durationMs, retryCount, logSnippet

**변경 내용:**
- `Topics.java` — `PIPELINE_EVT_DAG_JOB` 토픽 추가.
- `TopicConfig.java` — `pipelineEvtDagJobTopic()` 빈 추가.
- `PipelineEventProducer.java` — `publishDagJobDispatched()`, `publishDagJobCompleted()` 메서드 추가.
- `DagExecutionCoordinator.java` — `dispatchReadyJobs()`에서 디스패치 이벤트, `onJobCompleted()`에서 완료 이벤트 발행.

**logSnippet 제한:** 500자로 truncate한다. 전체 로그는 기존 `PIPELINE_STEP_CHANGED` 이벤트에 포함되므로 DAG 이벤트에는 스냅샷만 보낸다.

---

## 파일 변경 요약

| 구분 | 새 파일 | 수정 파일 |
|------|---------|-----------|
| #2 설정 | `PipelineProperties.java` | `PipelineConfig`, `DagExecutionCoordinator`, `WebhookTimeoutChecker`, `application.yml` |
| #1 복구 | — | `DagExecutionCoordinator`, `PipelineExecutionMapper.java/.xml` |
| #4 Stale | — | `DagExecutionCoordinator` |
| #3 재시도 | `V32__*.sql` | `PipelineJobExecution`, `PipelineJobExecutionMapper.java/.xml`, `DagExecutionCoordinator`, `PipelineConfig` |
| #6 정책 | `FailurePolicy.java`, `V33__*.sql` | `PipelineDefinition`, `DagExecutionState`, `DagExecutionCoordinator`, `PipelineDefinitionMapper.xml` |
| #5 재시작 | — | `PipelineDefinitionController`, `PipelineDefinitionService`, `DagExecutionCoordinator`, `PipelineJobExecutionMapper.xml` |
| #7 이벤트 | `DagJobDispatchedEvent.avsc`, `DagJobCompletedEvent.avsc` | `PipelineEventProducer`, `Topics`, `TopicConfig`, `DagExecutionCoordinator` |

**핵심 변경 파일:**
- `pipeline.dag.engine.DagExecutionCoordinator` — 7개 중 6개 feature 영향. 크래시 복구, 재시도, 실패 정책 분기, stale 정리, DAG 이벤트 발행, 설정 외부화 적용.
- `pipeline.dag.engine.DagExecutionState` — skippedJobIds, failurePolicy, allDownstream(), isTerminated() 추가.
- `pipeline.dag.event.DagEventProducer` — Feature #7에서 신규 추가. DAG Job dispatch/completion 이벤트 발행 전담.

---

## Phase 2 리뷰 이슈 해결 현황

| 이슈 | 설명 | 해결 |
|------|------|------|
| ENG-3 | DagExecutionState record → class 전환 | Phase 2 후반에 class로 변경 완료. Phase 3에서 skippedJobIds, failurePolicy 등 확장. |
| 크래시 복구 부재 | 앱 재시작 시 RUNNING 실행 유실 | `@PostConstruct recoverRunningExecutions()` 구현. |
| 하드코딩 상수 | MAX_CONCURRENT_JOBS, TIMEOUT_MINUTES | `PipelineProperties` record로 외부화. |

## 검증 항목

1. `./gradlew test` — 기존 + 신규 테스트 전체 통과 ✅
2. 크래시 복구: 실행 중 kill → 재시작 → RUNNING 실행 자동 재개 ✅ 구현 완료
3. Job 재시도: 실패 시뮬레이션 → 재시도 후 성공 or maxRetry 초과 시 보상 ✅ 구현 완료
4. 설정 외부화: `application.yml` 값 변경 → 반영 확인 ✅ 구현 완료
5. 부분 재시작: FAILED 실행 → restart API → SUCCESS job 건너뛰기 ✅ 구현 완료
6. 실패 정책: SKIP_DOWNSTREAM — diamond DAG에서 한 브랜치 실패 시 다른 브랜치 계속 ✅ 구현 완료
7. DAG 이벤트: dispatch/complete 시 Avro 이벤트 발행 확인 ✅ 구현 완료, Grafana Pipeline Tracker 대시보드 연동
8. E2E: `http/job-pipeline-e2e.http` 시나리오
   - BUILD → Nexus 업로드: ✅ 성공
   - DEPLOY 아티팩트 다운로드: ⚠️ 부분 성공 — `buildConfigXml()` 의 `parameterDefinitions`에 `EXECUTION_ID`/`STEP_ORDER`만 정의되어 `configJson` 키가 Jenkins에 전달되지 않음

---

## 후속 과제

- `buildConfigXml()` 동적 파라미터 정의 수정 — `configJson` 키를 Jenkins `parameterDefinitions`에 포함하여 DEPLOY Job이 아티팩트 경로를 올바르게 수신하도록 수정
- Server 3 OTel/Loki 연결 확인
