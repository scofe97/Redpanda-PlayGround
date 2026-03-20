# Step 3: DAG 엔진 핵심 리뷰

## DagValidator — Kahn's Algorithm

**구현 분석:**

`validate()`는 BFS 기반 Kahn's algorithm으로 순환 탐지와 위상 정렬을 동시에 수행한다. DFS 대비 장점은 순환 발견 시 "남은 노드 = 순환에 포함된 노드"를 즉시 알 수 있다는 점이다.

검증 흐름: 진입 차수 계산 → 루트(in-degree=0) 큐 투입 → BFS → 처리 안 된 노드 = 순환 → 연결성 BFS

**양호한 점:**
- 존재하지 않는 Job ID 참조 시 명확한 에러 메시지
- 연결성 검증이 양방향 인접 리스트로 정확하게 수행됨
- 에러 메시지에 한글 포함: 사용자 친화적

**이슈:**

| # | 심각도 | 내용 |
|---|--------|------|
| ENG-1 | **MAJOR** | **`topologicalSort()`의 중복 연산**: `validate()`를 먼저 호출한 뒤 동일한 Kahn's algorithm을 다시 수행한다. 그래프 구축(inDegree, adjacency 계산)이 2번 반복된다. `validate()`가 정렬 결과를 반환하도록 리팩토링하면 제거 가능. |
| ENG-2 | MINOR | **`validateConnectivity()`의 미사용 파라미터**: `originalInDegree`가 전달되지만 메서드 내에서 사용되지 않는다. 삭제해야 한다. |

## DagExecutionState — record 설계

**구현 분석:**

`record`로 선언되어 "불변 스냅샷"이라고 문서화되어 있다. `initialize()` 팩토리 메서드로 초기 상태를 구성하고, `findReadyJobIds()`, `completedJobIdsInReverseTopologicalOrder()` 등 조회 메서드를 제공한다.

**이슈:**

| # | 심각도 | 내용 |
|---|--------|------|
| ENG-3 | **CRITICAL** | **불변성 위반**: Javadoc에 "불변 스냅샷"이라고 했지만, `completedJobIds`, `runningJobIds`, `failedJobIds`가 가변 `HashSet`이다. `DagExecutionCoordinator.onJobCompleted()`에서 `state.runningJobIds().remove(jobId)`, `state.completedJobIds().add(jobId)`로 직접 변이한다. record의 접근자가 내부 Set 참조를 그대로 노출하므로 외부에서 자유롭게 변경 가능하다. **두 가지 해결책**: (A) record를 포기하고 일반 class로 바꿔서 명시적 mutation 메서드 제공, (B) record를 유지하되 상태 변경 시 새 인스턴스를 생성 (현재 "새 인스턴스를 만든다"고 했지만 실제로는 같은 인스턴스를 변이). **(A)를 권장 — 현재 사용 패턴이 명확히 mutable이므로)** |

**양호한 점:**
- `findReadyJobIds()` 로직: 완료/실행/실패를 모두 제외하고 의존성 충족 여부를 정확하게 확인
- `completedJobIdsInReverseTopologicalOrder()`: SAGA 보상용 역방향 위상 정렬이 정확하게 구현됨
- `isAllDone()`: completed + failed == total 로 정확한 완료 판정

## DagExecutionCoordinator — 핵심 조율기

**동시성 모델 분석:**

```
ConcurrentHashMap<UUID, DagExecutionState> executionStates  // 상태 캐시
ConcurrentHashMap<UUID, ReentrantLock> executionLocks        // 실행당 Lock
ExecutorService jobExecutorPool                              // 고정 3 스레드
```

실행당 `ReentrantLock`으로 `onJobCompleted` → `dispatchReadyJobs`를 직렬화한다. 실행 간에는 독립적이므로 서로 블로킹하지 않는다.

**이슈:**

| # | 심각도 | 내용 |
|---|--------|------|
| ENG-4 | **MAJOR** | **Lock reentrance 복잡성**: `dispatchReadyJobs()`에서 `lock.isHeldByCurrentThread()`로 이미 lock을 들고 있는지 확인한다. `startExecution()`은 lock 없이 호출하고 `onJobCompleted()`는 lock을 들고 호출한다. 이 패턴은 lock을 명시적으로 관리하는 것보다 lock 범위를 호출자에서 통일하는 것이 안전하다. 현재는 동작하지만 향후 호출 경로가 추가되면 데드락 위험이 있다. |
| ENG-5 | **MAJOR** | **메모리 누수 가능성**: `executionStates`와 `executionLocks`는 `finalizeExecution()`에서만 정리된다. 앱 크래시나 비정상 종료 시 RUNNING 상태 실행이 메모리에 남는다. 주기적 클린업 스케줄러가 필요하다. Javadoc에 "앱 재시작 시 DB에서 RUNNING 실행을 조회하여 상태를 복원"이라고 했지만 구현이 없다. |
| ENG-6 | **MAJOR** | **`startExecution()`에서 Lock 없이 상태 초기화**: `executionStates.put()`, `executionLocks.put()` 후 `dispatchReadyJobs()`를 호출하는데, `dispatchReadyJobs()`가 lock을 획득한다. 초기화와 첫 dispatch 사이에 다른 스레드(예: 매우 빠른 webhook 콜백)가 `onJobCompleted()`를 호출하면 아직 state가 세팅되기 전일 수 있다. 실제로는 웹훅이 이렇게 빨리 도착할 가능성은 극히 낮지만, `startExecution()` 전체를 lock 안에서 수행하는 것이 안전하다. |

**양호한 점:**
- `executeJob()`에서 webhook 대기 시 스레드를 반환하는 Break-and-Resume 패턴이 잘 구현됨
- `skipPendingJobs()`: 실패 시 미실행 Job을 SKIPPED로 처리하여 상태가 명확
- `compensateDag()`: 역방향 위상 순서로 보상하고, 보상 실패 시 COMPENSATION_FAILED로 기록 + 로그
- `onJobFailed()`는 `onJobCompleted(success=false)`로 위임하여 코드 중복 제거

## JobExecutorRegistry — JobType → Executor 매핑

**구현 분석:**

생성자에서 `Map.of()`로 정적 매핑을 구성한다. `asStepTypeMap()`은 SagaCompensator 호환용.

```java
BUILD → JenkinsCloneAndBuildStep
DEPLOY → JenkinsDeployStep
IMPORT → JenkinsCloneAndBuildStep  // BUILD와 동일 실행기
ARTIFACT_DOWNLOAD → NexusDownloadStep
IMAGE_PULL → RegistryImagePullStep
```

**이슈:**

| # | 심각도 | 내용 |
|---|--------|------|
| ENG-7 | MINOR | `IMPORT → JenkinsCloneAndBuildStep`이 BUILD와 동일한 실행기를 사용한다. IMPORT는 "외부 저장소에서 빌드 산출물을 임포트"하는 것인데, GIT_CLONE + BUILD와 같은 실행기를 쓰는 것이 맞는지 확인 필요. 현재는 placeholder로 보인다. |

## PipelineEngine 수정 — DAG 라우팅

**구현 분석:**

```java
public void execute(PipelineExecution execution) {
    if (execution.getPipelineDefinitionId() != null) {
        dagCoordinator.startExecution(execution);  // DAG 모드
    } else {
        // 기존 순차 모드
        executionMapper.updateStatus(...);
        executeFrom(execution, 0, System.currentTimeMillis());
    }
}
```

`resumeAfterWebhook()`에서도 `dagCoordinator.isManaged(executionId)` 체크로 DAG 모드를 분기한다.

**양호한 점:**
- 분기 조건이 단순하고 명확 (null 체크)
- 기존 순차 모드 코드에 변경 없음
- CAS 패턴(`updateStatusIfCurrent`)으로 webhook 콜백과 타임아웃 체커 간 경쟁 방지

**이슈:**
- 없음. 하위 호환이 깔끔하게 구현됨.

## WebhookTimeoutChecker 수정

**구현 분석:**

```java
if (dagCoordinator.isManaged(step.getExecutionId())) {
    Long jobId = dagCoordinator.findJobIdByStepOrder(...);
    dagCoordinator.onJobFailed(executionId, stepOrder, jobId);
    return;  // DAG coordinator가 나머지 처리
}
// 기존 순차 모드: sagaCompensator.compensate() + executionMapper.updateStatus()
```

**양호한 점:**
- CAS 후 DAG 분기가 올바르게 배치됨
- TraceContext 복원으로 분산 추적 연결 유지

**이슈:**
- 없음. 기존 패턴을 잘 확장함.

## 퀴즈

**Q1**: `DagExecutionCoordinator`가 `SagaCompensator`를 직접 사용하지 않고 `compensateDag()` 메서드를 자체 구현한 이유는?

> 기존 `SagaCompensator.compensate()`는 순차 실행의 `failedStepOrder`를 기준으로 "그 이전 스텝"을 역순 보상한다. DAG에서는 순서가 선형이 아니므로 "역방향 위상 순서"로 보상해야 한다. 보상 로직 자체(executor.compensate + 상태 업데이트)는 동일하지만 대상 선정 로직이 다르다.

**Q2**: `MAX_CONCURRENT_JOBS = 3`을 상수로 하드코딩한 이유는?

> Jenkins containerCap과 1:1 매칭이다. containerCap을 초과하면 Jenkins 빌드 큐에 쌓이기만 하고 실행되지 않으므로, 스레드 풀을 그 이상으로 잡는 것은 의미가 없다. 향후 containerCap이 변경되면 설정 외부화(application.yml)가 필요하다.
