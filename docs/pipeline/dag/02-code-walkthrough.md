# 코드 워크스루 — DAG 엔진 핵심 메서드

DAG 엔진의 핵심 메서드 11개를 실제 코드와 함께 설명한다. 상태 머신(DagExecutionState), 오케스트레이터(DagExecutionCoordinator), 도메인 클래스 순서로 진행한다.

---

## 1. 상태 머신 — DagExecutionState

### 1.1 initialize()

`DagExecutionState.java:69-99`

```java
public static DagExecutionState initialize(
        List<PipelineJob> jobList
        , Map<Long, Integer> jobIdToJobOrder
        , FailurePolicy failurePolicy) {
    var jobs = new LinkedHashMap<Long, PipelineJob>();
    var deps = new HashMap<Long, Set<Long>>();
    var successors = new HashMap<Long, Set<Long>>();

    for (var job : jobList) {
        jobs.put(job.getId(), job);
        deps.put(job.getId(), job.getDependsOnJobIds() != null
                ? new HashSet<>(job.getDependsOnJobIds())
                : new HashSet<>());
        successors.put(job.getId(), new HashSet<>());
    }

    // 후속 그래프 구축
    for (var entry : deps.entrySet()) {
        for (Long depId : entry.getValue()) {
            successors.get(depId).add(entry.getKey());
        }
    }

    return new DagExecutionState(
            Collections.unmodifiableMap(jobs)
            , Collections.unmodifiableMap(deps)
            , Collections.unmodifiableMap(successors)
            , Collections.unmodifiableMap(jobIdToJobOrder)
            , failurePolicy != null ? failurePolicy : FailurePolicy.STOP_ALL
    );
}
```

**핵심 로직.** Job 목록을 순회하며 의존성 그래프(deps)와 후속 그래프(successors)를 동시에 구축한다. 의존성 그래프를 먼저 완성한 뒤 역방향을 구축하는 2-pass 분리 방식이 정확성 검증에 유리하기 때문에 이 구조를 택했다.

**설계 결정.** `Collections.unmodifiableMap`으로 감싸서 초기화 이후 그래프 구조 변경을 차단한다. 가변 상태(completedJobIds 등)는 별도 필드로 분리하여 구조적 불변 필드와 런타임 가변 필드의 경계를 생성자 수준에서 강제한다.

**주의할 점.** `dependsOnJobIds`가 null일 수 있으므로 빈 HashSet으로 방어한다. DB에서 의존성을 로드하지 않은 채 호출하면 모든 Job이 루트로 간주되어 동시 디스패치되는 문제가 발생할 수 있다.

---

### 1.2 findReadyJobIds()

`DagExecutionState.java:153-169`

```java
public List<Long> findReadyJobIds() {
    var ready = new ArrayList<Long>();
    for (var entry : dependencyGraph.entrySet()) {
        Long jobId = entry.getKey();
        if (completedJobIds.contains(jobId)
                || runningJobIds.contains(jobId)
                || failedJobIds.contains(jobId)
                || skippedJobIds.contains(jobId)) {
            continue;
        }
        if (completedJobIds.containsAll(entry.getValue())) {
            ready.add(jobId);
        }
    }
    return ready;
}
```

**핵심 로직.** 종료된 Job을 걸러낸 뒤, 선행 의존성이 전부 completedJobIds에 포함된 것만 ready로 판정한다. `containsAll`이 핵심 조건이며, 의존성이 빈 Set인 루트 Job은 항상 이 조건을 통과한다.

**설계 결정.** "성공한 Job만 completed에, 실패한 Job은 failedJobIds에" 넣는 분리 구조 덕분에 단일 조건으로 ready 판정이 가능하다. 실패한 Job을 completed에 포함시켰다면 하류가 실패한 선행 위에서 실행되는 버그가 생길 수 있다.

**주의할 점.** 반드시 ReentrantLock 안에서 호출해야 한다. 그렇지 않으면 completedJobIds와 runningJobIds 사이의 일관성이 깨져 같은 Job을 중복 디스패치할 위험이 있다.

---

### 1.3 allDownstream()

`DagExecutionState.java:208-224`

```java
public Set<Long> allDownstream(Long failedJobId) {
    var downstream = new LinkedHashSet<Long>();
    var queue = new LinkedList<Long>();
    queue.add(failedJobId);

    while (!queue.isEmpty()) {
        Long current = queue.poll();
        Set<Long> succs = successorGraph.getOrDefault(current, Set.of());
        for (Long succ : succs) {
            if (!downstream.contains(succ) && !runningJobIds.contains(succ)) {
                downstream.add(succ);
                queue.add(succ);
            }
        }
    }
    return downstream;
}
```

**핵심 로직.** BFS로 실패한 Job의 후속 그래프를 탐색하여 전이적 하류를 수집한다. 시작점(failedJobId)은 결과에 포함되지 않고, RUNNING 상태 Job도 제외한다.

**설계 결정.** RUNNING Job을 제외하는 이유는 Jenkins 같은 외부 시스템 실행 중인 작업을 강제 중단하면 정합성이 깨질 수 있기 때문이다. 해당 Job 완료 시 onJobCompleted에서 재평가하는 지연 판단 방식을 택했다.

**주의할 점.** diamond DAG(A->B, A->C, B->D, C->D)에서 B가 실패하면 D는 downstream에 포함되지만 C는 포함되지 않는다. C가 성공하더라도 D는 이미 SKIP되어 합류 지점 이후 전체가 차단된다.

---

### 1.4 completedJobIdsInReverseTopologicalOrder()

`DagExecutionState.java:227-268`

```java
public List<Long> completedJobIdsInReverseTopologicalOrder() {
    var inDegree = new HashMap<Long, Integer>();
    for (Long jobId : completedJobIds) {
        inDegree.put(jobId, 0);
    }
    for (Long jobId : completedJobIds) {
        Set<Long> succs = successorGraph.getOrDefault(jobId, Set.of());
        for (Long succ : succs) {
            if (completedJobIds.contains(succ)) {
                inDegree.merge(succ, 1, Integer::sum);
            }
        }
    }
    // ... Kahn's BFS 위상 정렬 ...
    Collections.reverse(sorted);
    return sorted;
}
```

**핵심 로직.** 성공한 Job들만으로 서브그래프를 구성한 뒤 Kahn's algorithm으로 위상 정렬하고 뒤집어 leaf->root 순서를 반환한다. SAGA 보상 시 "나중에 실행된 것부터 먼저 롤백"하는 순서를 보장하는 역할이다.

**설계 결정.** completedJobIds 서브셋에서만 정렬하는 이유는 실패하거나 스킵된 Job은 보상 대상이 아니기 때문이다. 부분 실행된 DAG에서도 올바른 역순을 산출할 수 있다.

**주의할 점.** 보상 순서가 역위상순을 따르지 않으면 "DB INSERT -> 메시지 발행" 같은 의존 관계에서 부정합이 생긴다. 메시지를 먼저 철회하고 DB를 삭제해야 하며, 이 메서드가 그 순서를 보장한다.

---

## 2. 오케스트레이터 — DagExecutionCoordinator

### 2.1 startExecution()

`DagExecutionCoordinator.java:177-234`

```java
public void startExecution(PipelineExecution execution) {
    var executionId = execution.getId();
    executionMapper.updateStatus(executionId, PipelineStatus.RUNNING.name(), null, null);

    var jobs = jobMapper.findByDefinitionId(execution.getPipelineDefinitionId());
    for (var job : jobs) {
        job.setDependsOnJobIds(jobMapper.findDependsOnJobIds(
                execution.getPipelineDefinitionId(), job.getId()));
    }
    dagValidator.validate(jobs);
    // ... jobIdToJobOrder, failurePolicy 로드 생략 ...

    // Lock-before-state 패턴
    var lock = new ReentrantLock();
    executionLocks.put(executionId, lock);
    lock.lock();
    try {
        var state = DagExecutionState.initialize(jobs, jobIdToJobOrder, policy);
        executionStates.put(executionId, state);

        // 부분 재시작: 이미 SUCCESS인 job을 완료로 사전 등록
        for (var je : jobExecutions) {
            if (je.getJobId() != null && je.getStatus() == JobExecutionStatus.SUCCESS) {
                state.markCompleted(je.getJobId());
            }
        }
        dispatchReadyJobs(execution);
    } finally {
        lock.unlock();
    }
}
```

**핵심 로직.** DB에서 Job 목록, 의존성, failurePolicy를 로드한 뒤 DAG 검증을 통과시키고, ReentrantLock을 먼저 등록한 다음 lock 안에서 상태 초기화와 첫 디스패치를 수행한다.

**설계 결정.** Lock-before-state 패턴을 택한 이유는 ConcurrentHashMap에 state를 넣는 순간부터 webhook 콜백이 접근할 수 있기 때문이다. lock 등록 전에 state를 넣으면 경합 조건이 발생하므로, lock을 먼저 등록하고 잡은 상태에서 state를 넣는 순서를 강제한다.

**주의할 점.** 부분 재시작 시 이전 실행의 FAILED Job은 사전 등록하지 않는다. FAILED를 completed로 등록하면 하류가 의존성 충족으로 오판하여 실행되는 문제가 생기므로 SUCCESS만 등록해야 한다.

---

### 2.2 onJobCompleted()

`DagExecutionCoordinator.java:246-306`

```java
public void onJobCompleted(UUID executionId, int jobOrder, Long jobId, boolean success) {
    var lock = executionLocks.get(executionId);
    if (lock == null) { return; }
    lock.lock();
    try {
        var state = executionStates.get(executionId);
        if (state == null) { return; }

        state.removeRunning(jobId);
        if (success) { state.markCompleted(jobId); }
        else { state.markFailed(jobId); }

        publishDagJobCompletedEvent(executionId, jobId, jobOrder, state, success);

        if (state.isAllDone()) { finalizeExecution(executionId, state); return; }
        if (!success || state.hasFailure()) {
            var execution = executionMapper.findById(executionId);
            if (execution != null) handleFailure(executionId, state, execution);
            return;
        }
        var execution = executionMapper.findById(executionId);
        if (execution != null) dispatchReadyJobs(execution);
    } finally { lock.unlock(); }
}
```

**핵심 로직.** lock을 잡고 상태를 전이시킨 뒤 3분기로 나뉜다. allDone이면 finalize, 실패가 있으면 handleFailure, 모두 정상이면 새 ready Job을 디스패치한다.

**설계 결정.** "성공이지만 기존 실패가 있는" 케이스를 별도 처리하는 이유는 SKIP_DOWNSTREAM 정책 때문이다. B 실패 후 C 성공 콜백에서 독립 브랜치의 ready Job을 디스패치해야 하며, handleFailure(SKIP_DOWNSTREAM)가 이를 담당한다.

**주의할 점.** lock/state가 null인 early return은 finalizeExecution에서 상태 정리 후 늦게 도착한 콜백을 무시하기 위한 방어 코드이다.

---

### 2.3 dispatchReadyJobs()

`DagExecutionCoordinator.java:393-436`

```java
private void dispatchReadyJobs(PipelineExecution execution) {
    var executionId = execution.getId();
    var lock = executionLocks.get(executionId);

    boolean needsLock = !lock.isHeldByCurrentThread();
    if (needsLock) lock.lock();
    try {
        var state = executionStates.get(executionId);
        if (state == null) return;
        var readyJobIds = state.findReadyJobIds();
        if (readyJobIds.isEmpty()) return;

        int available = props.maxConcurrentJobs() - state.runningCount();
        if (available <= 0) return;

        var toDispatch = readyJobIds.subList(0, Math.min(readyJobIds.size(), available));
        for (Long jobId : toDispatch) {
            state.markRunning(jobId);
            var job = state.jobs().get(jobId);
            var jobOrder = state.jobIdToJobOrder().get(jobId);
            jobExecutorPool.submit(() -> executeJob(execution, job, jobOrder));
        }
    } finally {
        if (needsLock) lock.unlock();
    }
}
```

**핵심 로직.** ready Job 목록을 구한 뒤 maxConcurrentJobs 제한 내에서 스레드 풀에 제출한다. `needsLock` 패턴으로 이미 lock을 잡은 호출자(startExecution, onJobCompleted)에서의 이중 lock을 방지한다.

**설계 결정.** ReentrantLock이 reentrant하므로 무조건 lock()을 호출해도 데드락은 없지만, finally에서 unlock 호출을 조건부로 하지 않으면 lock count가 꼬일 수 있다. needsLock 패턴으로 lock/unlock 대칭을 보장하는 쪽이 디버깅에 유리하다.

**주의할 점.** maxConcurrentJobs가 0 이하면 모든 Job이 영원히 대기하게 된다. 설정 검증에서 사전에 걸러져야 할 문제이다.

---

### 2.4 executeJob()

`DagExecutionCoordinator.java:438-507`

```java
private void executeJob(PipelineExecution execution, PipelineJob job, Integer jobOrder) {
    // ... JobExecution 조회, 파라미터 전달, RUNNING 전환 ...
    try {
        var executor = jobExecutorRegistry.getExecutor(job.getJobType());
        executor.execute(execution, je);

        // Break-and-Resume: webhook 대기
        if (je.isWaitingForWebhook()) {
            jobExecutionMapper.updateStatus(je.getId(), JobExecutionStatus.WAITING_WEBHOOK.name()
                    , "Waiting for Jenkins webhook callback...", LocalDateTime.now());
            return; // 스레드 반환 — webhook 도착 시 onJobCompleted()에서 재개
        }

        // 동기 완료
        jobExecutionMapper.updateStatus(je.getId(), JobExecutionStatus.SUCCESS.name(), je.getLog(), LocalDateTime.now());
        onJobCompleted(executionId, jobOrder, job.getId(), true);
    } catch (Exception e) {
        int currentRetry = je.getRetryCount();
        if (currentRetry < props.jobMaxRetries()) {
            long delaySeconds = 1L << currentRetry; // 지수 백오프: 1s, 2s, 4s
            retryScheduler.schedule(
                    () -> executeJob(execution, job, jobOrder)
                    , delaySeconds, TimeUnit.SECONDS);
            return;
        }
        onJobCompleted(executionId, jobOrder, job.getId(), false);
    }
}
```

**핵심 로직.** 타입별 실행기를 호출하고 세 갈래로 분기한다. webhook 대기면 스레드를 반환하고(Break-and-Resume), 동기 완료면 onJobCompleted를 호출하며, 예외 시 `1L << currentRetry` 지수 백오프로 재시도를 스케줄링한다.

**설계 결정.** Break-and-Resume 패턴을 채택한 이유는 Jenkins 빌드처럼 수 분~수 시간 걸리는 외부 작업에 스레드를 점유시키면 풀이 고갈되기 때문이다. webhook 도착 시 onJobCompleted()가 DAG를 이어간다.

**주의할 점.** 재시도는 동기 실행 예외에만 적용되며, webhook 기반 실패(Jenkins 빌드 실패)에는 적용되지 않는다. 지수 백오프의 최대 지연은 `2^(maxRetries-1)` 초이므로 maxRetries가 크면 지연이 급격히 늘어날 수 있다.

---

### 2.5 handleFailure() + 3가지 정책

`DagExecutionCoordinator.java:512-573`

```java
private void handleFailure(UUID executionId, DagExecutionState state, PipelineExecution execution) {
    switch (state.failurePolicy()) {
        case STOP_ALL -> handleStopAll(executionId, state);
        case SKIP_DOWNSTREAM -> handleSkipDownstream(executionId, state, execution);
        case FAIL_FAST -> handleFailFast(executionId, state);
    }
}
```

**STOP_ALL**: 새 디스패치를 중단하고, running Job이 모두 끝나면 PENDING을 SKIP한 뒤 finalize한다. **SKIP_DOWNSTREAM**: `allDownstream()`으로 실패 Job의 하류만 SKIP하고, 독립 브랜치는 계속 진행시킨다. **FAIL_FAST**: 즉시 모든 PENDING을 SKIP 처리하되, RUNNING은 완료를 기다린다.

**설계 결정.** 세 정책 모두 RUNNING Job을 강제 중단하지 않는다. Jenkins 빌드처럼 외부 시스템 작업을 안전하게 중단하는 것이 불가능한 경우가 많기 때문이다. STOP_ALL과 FAIL_FAST의 차이는 PENDING SKIP 시점인데, FAIL_FAST가 즉시 SKIP하므로 finalize 판정이 더 빠르다.

**주의할 점.** handleSkipDownstream에서 failedJobIds()를 방어적 복사하는 이유는 루프 안에서 skippedJobIds가 변경되기 때문이다. 여러 실패 Job의 downstream이 겹치면 `isTerminated` 체크로 중복 SKIP을 방지한다.

---

### 2.6 compensateDag()

`DagExecutionCoordinator.java:619-648`

```java
private void compensateDag(
        PipelineExecution execution, List<Long> reverseJobIds, DagExecutionState state) {
    var jobExecutors = jobExecutorRegistry.asJobTypeMap();

    for (Long jobId : reverseJobIds) {
        var jobOrder = state.jobIdToJobOrder().get(jobId);
        if (jobOrder == null) continue;
        var je = jobExecutionMapper.findByExecutionIdAndJobOrder(execution.getId(), jobOrder);
        if (je == null || je.getStatus() != JobExecutionStatus.SUCCESS) continue;

        try {
            var executor = jobExecutors.get(je.getJobType());
            if (executor != null) { executor.compensate(execution, je); }
            jobExecutionMapper.updateStatus(je.getId(), JobExecutionStatus.COMPENSATED.name()
                    , "Compensated after DAG saga rollback", LocalDateTime.now());
        } catch (Exception e) {
            log.error("[DAG-SAGA] Compensation FAILED for job: {} - MANUAL INTERVENTION REQUIRED",
                    je.getJobName(), e);
            jobExecutionMapper.updateStatus(je.getId(), JobExecutionStatus.FAILED.name()
                    , "COMPENSATION_FAILED: " + e.getMessage(), LocalDateTime.now());
        }
    }
}
```

**핵심 로직.** 역위상순 목록을 받아 각 Job의 compensate()를 호출한다. SUCCESS 상태인 JobExecution만 보상 대상이며, 실패 시 COMPENSATION_FAILED 메시지를 남긴다.

**설계 결정.** 보상 실패 시 전체 보상 체인을 중단하지 않고 다음 Job의 보상을 계속 시도한다. 보상의 보상은 재귀적으로 복잡해지므로 운영자에게 위임("MANUAL INTERVENTION REQUIRED")하는 것이 현실적이다.

**주의할 점.** executor가 null이어도 COMPENSATED로 마킹한다. 보상 로직이 없는 Job 타입(알림 전송 등)을 허용하기 위한 의도적 선택이지만, 보상이 필요한 Job에서 executor 등록을 빠뜨리면 보상이 누락되는 위험이 있다.

---

### 2.7 recoverRunningExecutions()

`DagExecutionCoordinator.java:78-168`

```java
@PostConstruct
public void recoverRunningExecutions() {
    var runningExecutions = executionMapper.findByStatus(PipelineStatus.RUNNING.name());
    for (var execution : runningExecutions) {
        if (execution.getPipelineDefinitionId() == null) continue; // 순차 모드 건너뜀
        // ... Job, 의존성, failurePolicy 로드 ...
        lock.lock();
        try {
            var state = DagExecutionState.initialize(jobs, jobIdToJobOrder, policy);
            for (var je : jobExecutions) {
                if (je.getJobId() == null) continue;
                switch (je.getStatus()) {
                    case SUCCESS -> state.markCompleted(je.getJobId());
                    case FAILED, COMPENSATED -> state.markFailed(je.getJobId());
                    case SKIPPED -> state.markSkipped(je.getJobId());
                    case RUNNING, WAITING_WEBHOOK -> {
                        // webhook 유실 가정 → FAILED 처리
                        jobExecutionMapper.updateStatus(je.getId()
                                , JobExecutionStatus.FAILED.name()
                                , "Failed during crash recovery (interrupted)"
                                , LocalDateTime.now());
                        state.markFailed(je.getJobId());
                    }
                    case PENDING -> { /* 그대로 둠 */ }
                }
            }
            if (state.isAllDone()) finalizeExecution(executionId, state);
            else if (state.hasFailure()) handleFailure(executionId, state, execution);
            else dispatchReadyJobs(execution);
        } finally { lock.unlock(); }
    }
}
```

**핵심 로직.** `@PostConstruct`로 앱 시작 시 실행되며, DB의 RUNNING 실행을 조회하여 메모리 상태를 재구성한다. RUNNING/WAITING_WEBHOOK Job은 webhook 유실을 가정하여 FAILED로 전환하고, 재구성 후 allDone/hasFailure/정상 3분기로 처리를 이어간다.

**설계 결정.** RUNNING을 FAILED로 전환하는 보수적 접근을 택한 이유는 크래시 시점에 실행 중이던 Job의 실제 결과를 알 수 없기 때문이다. 성공을 가정하고 하류를 실행하면 중복 실행이나 데이터 부정합이 발생할 수 있으므로, 필요 시 부분 재시작 API로 수동 재개하는 편이 안전하다.

**주의할 점.** 복구 중 예외가 발생하면 해당 실행만 FAILED로 마킹하고 다음으로 넘어간다. 전체 복구 실패로 앱 시작이 블로킹되는 것을 방지하기 위함이다. `pipelineDefinitionId`가 null인 실행은 기존 순차 모드이므로 건너뛴다.

---

## 3. 도메인 클래스

### 3.1 FailurePolicy enum

`FailurePolicy.java:9-28`

```java
public enum FailurePolicy {
    /** 기본값. 실패 시 새 Job 디스패치를 중단하고,
     *  이미 실행 중인 Job의 완료를 기다린 뒤 SAGA 보상을 실행한다. */
    STOP_ALL,

    /** 실패한 Job의 전이적 하위(successor graph BFS)만 SKIP하고,
     *  다른 독립 브랜치는 계속 실행한다. diamond DAG에서 유용하다. */
    SKIP_DOWNSTREAM,

    /** 실패 즉시 모든 PENDING Job을 SKIP 처리한다.
     *  이미 RUNNING 중인 Job은 완료를 기다린 뒤 최종 판정한다. */
    FAIL_FAST
}
```

STOP_ALL은 가장 안전한 기본값으로 SAGA 보상으로 일관성을 복원한다. SKIP_DOWNSTREAM은 diamond DAG처럼 독립 브랜치가 존재하는 구조에서 한 브랜치 실패가 다른 브랜치에 영향을 주지 않도록 할 때 유용하다. FAIL_FAST는 빠른 피드백이 중요한 CI/CD 시나리오에서 쓸 수 있지만 STOP_ALL보다 복구 가능성이 떨어진다.

---

### 3.2 PipelineJob

`PipelineJob.java:24-64`

```java
@Getter @Setter
public class PipelineJob {
    private Long id;
    private Long pipelineDefinitionId;
    private String jobName;
    private PipelineJobType jobType;
    private Integer executionOrder;
    private Long presetId;
    private String configJson;
    private String jenkinsScript;
    private String parameterSchemaJson;

    /** 이 Job이 의존하는 선행 Job ID 목록 (DAG 엣지).
     *  pipeline_job_dependency 테이블에서 로드된다. */
    private List<Long> dependsOnJobIds;
}
```

PipelineJob은 DAG의 노드이며, `dependsOnJobIds`가 엣지 역할을 한다. 그래프 이론의 인접 리스트 표현과 동일한 구조로, 각 노드가 자신의 선행 노드 목록을 보유한다. 이 필드는 pipeline_job_dependency 테이블에서 로드되며 PipelineJob 테이블 자체에는 컬럼이 없다.

Job과 Step(실행 기록)을 분리한 이유는 같은 파이프라인 정의를 반복 실행할 때 Job 정의는 공유하되 실행 결과는 독립적으로 추적해야 하기 때문이다.

---

### 3.3 PipelineDefinition + collectParameterSchemas()

`PipelineDefinition.java:40-45`

```java
public List<ParameterSchema> collectParameterSchemas() {
    if (jobs == null || jobs.isEmpty()) return List.of();
    return jobs.stream()
            .flatMap(j -> j.parameterSchemas().stream())
            .toList();
}
```

파이프라인에 속한 모든 Job의 파라미터 스키마를 `flatMap`으로 평탄화하여 하나의 리스트로 합산한다. 파이프라인 실행 시 사용자에게 "이 파이프라인을 실행하려면 어떤 파라미터가 필요한가"를 한 번에 보여주기 위한 용도이다.

PipelineDefinition과 PipelineExecution의 분리는 "설계도 vs 시공 기록"에 비유할 수 있다. failurePolicy는 정의 수준에서 설정하므로 실행마다 바꿀 수 없고, 정책을 변경하려면 정의 자체를 수정해야 한다.

> **설계 이력.** DagExecutionState는 초기에 Java record로 구현되었으나, completedJobIds·runningJobIds·failedJobIds·skippedJobIds처럼 런타임에 변경되는 상태 집합을 record의 불변 필드로 관리하면 매번 새 인스턴스를 생성해야 하는 문제가 있었다. 이를 해결하기 위해 class로 전환하고 가변 상태 집합만 별도 필드로 분리했다. 구조적 불변 필드(dependencyGraph, successorGraph 등)는 `Collections.unmodifiableMap`으로 보호하고, 가변 상태 집합에 대한 접근은 ReentrantLock으로 직렬화하여 스레드 안전성을 유지했다.

---

## 4. ParameterResolver — 파라미터 검증과 치환

파이프라인 실행 시 사용자 파라미터를 Job 설정에 주입하는 3단계 처리기다.

### 4.1 validate(schemas, userParams)

ParameterSchema 목록과 사용자 파라미터를 대조하여 누락된 필수 파라미터를 탐지하고 기본값을 적용한다. 로직은 다음과 같다.

1. 각 스키마의 `required` 필드를 확인한다
2. 사용자 파라미터에 해당 키가 없으면:
   - `defaultValue`가 있으면 자동 적용
   - `required=true`이고 기본값도 없으면 `IllegalArgumentException` 발생
3. 검증을 통과한 최종 파라미터 맵을 반환한다

실행 전에 파라미터 누락을 감지하므로, Jenkins Job이 시작된 뒤에야 파라미터 오류를 발견하는 상황을 방지한다.

### 4.2 resolve(template, params)

configJson이나 jenkinsScript 문자열에서 `${PARAM_NAME}` 패턴을 정규식으로 찾아 실제 값으로 치환한다.

- 정규식: `\$\{([A-Za-z0-9_]+)\}`
- 매칭된 키가 params에 존재하면 치환, 없으면 원본 유지
- 보안: 파라미터 이름을 `[A-Za-z0-9_]`로 제한하여 경로 순회나 인젝션을 차단한다

### 4.3 merge(systemParams, userParams)

시스템 파라미터(EXECUTION_ID, STEP_ORDER)와 사용자 파라미터를 병합한다. 시스템 파라미터가 우선하므로, 사용자가 EXECUTION_ID를 임의로 덮어쓸 수 없다.

---

## 5. DagEventProducer — DAG 이벤트 발행

DAG 실행 상태를 Avro 이벤트로 직렬화하여 Kafka에 발행한다. Grafana Pipeline Tracker 대시보드와 프론트엔드 LiveDagGraph가 이 이벤트를 실시간으로 소비한다.

### 5.1 publishDagJobDispatched(executionId, jobId, jobName, jobType, jobOrder)

Job이 RUNNING 상태로 전환될 때 호출된다. DagJobDispatchedEvent Avro 레코드를 생성하고 `Topics.PIPELINE_EVT_DAG_JOB` 토픽에 executionId를 파티션 키로 발행한다. 동일 실행의 이벤트가 같은 파티션에 모이므로 소비자가 순서대로 처리할 수 있다.

### 5.2 publishDagJobCompleted(executionId, jobId, jobName, jobType, jobOrder, status, durationMs, retryCount, logSnippet)

Job 실행이 완료(성공 또는 실패)되면 호출된다. status, 소요 시간(durationMs), 재시도 횟수(retryCount), 로그 스니펫(500자 이내)을 포함한다. logSnippet은 전체 로그가 아닌 마지막 500자를 잘라낸 것으로, 이벤트 크기를 제한하면서도 디버깅에 필요한 최소 정보를 전달하기 위함이다.

---

## 6. DagGraphResponse — DAG 시각화 응답

DAG 실행 상태를 Grafana Node Graph 패널과 ReactFlow에서 소비할 수 있는 형식으로 변환한다.

### 6.1 구조

```java
record DagGraphResponse(List<Node> nodes, List<Edge> edges)

record Node(
    String id,        // Job ID
    String title,     // Job 이름
    String subTitle,  // Job 타입 (BUILD, DEPLOY 등)
    String mainStat,  // 상태 (SUCCESS, FAILED, RUNNING 등)
    String color      // 상태별 색상 코드
)

record Edge(
    String id,        // 엣지 ID (e0, e1, ...)
    String source,    // 선행 Job ID
    String target     // 후속 Job ID
)
```

### 6.2 상태→색상 매핑

| 상태 | 색상 | 용도 |
|------|------|------|
| SUCCESS | green | 성공 완료 |
| FAILED | red | 실패 |
| RUNNING | blue | 실행 중 |
| WAITING_WEBHOOK | purple | 웹훅 대기 |
| PENDING | orange | 대기 중 |
| SKIPPED | gray | 건너뜀 |

PipelineDefinitionService.getDagGraph()에서 Job 정의와 실행 상태를 조합하여 응답을 생성한다. dependsOnJobIds를 Edge로 변환하고, JobExecution 상태를 Node의 mainStat과 color에 매핑한다.
