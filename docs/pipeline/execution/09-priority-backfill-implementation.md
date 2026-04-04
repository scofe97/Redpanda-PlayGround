# Priority Backfill 구현 해설

## 설계와 구현의 대응

[설계 문서](./08-priority-backfill-scheduler.md)의 구현 로드맵(Phase 1~3)이 실제 코드에 어떻게 반영되었는지 매핑한다.

| 설계 Phase | 항목 | 구현 파일 | 상태 |
|------------|------|----------|------|
| **Phase 1** | DB 분산 큐 (FOR UPDATE SKIP LOCKED) | `PriorityDispatchQueue.java` + `V43__add_dispatch_status.sql` | 완료 |
| Phase 1 | dispatchByPriority() 알고리즘 | `DagExecutionCoordinator.doDispatchByPriority()` | 완료 |
| Phase 1 | Jenkins별 가용 executor 집계 | `DagExecutionCoordinator.doDispatchByPriority()` 내부 `computeIfAbsent` | 완료 |
| Phase 1 | @PostConstruct 재구성 | `DagExecutionCoordinator.startExecution()`에서 부분 재시작 지원 | 완료 |
| **Phase 2** | completionFuture.get() 제거 | `PipelineEventConsumer.runPipelineAsync()` — 블로킹 없음 | 완료 |
| Phase 2 | Semaphore 제거 → DB 분산 큐로 대체 | Consumer는 `pipelineEngine.execute()` 직접 호출, 슬롯 제어는 `dispatchByPriority()`가 담당 | 완료 |
| Phase 2 | concurrency = "3" 적용 | `@KafkaListener(concurrency = "3")` | 완료 |
| Phase 2 | webhook → dispatchByPriority() 연결 | `onJobCompleted()` → `dispatchByPriority()` | 완료 |
| **Phase 3** | PipelineScheduler 인터페이스 분리 | 미구현 — 단일 모듈로 충분한 규모 | 보류 |
| Phase 3 | 멀티 모듈 분리 | 미구현 | 보류 |

설계 문서에서도 "Phase 3는 처리량이 늘어 수평 확장이 필요할 때 분리해도 늦지 않다"고 명시했으므로, 현재 단계에서 보류는 의도된 것이다.

---

## 핵심 클래스 구조

### PriorityDispatchQueue — DB 분산 큐 + 인메모리 캐시

디스패치 로직은 `DagExecutionCoordinator`에 위치하며, Queue는 데이터 접근 계층 역할을 한다. 이렇게 분리한 이유는 순환 의존성 방지다. Queue가 Coordinator를 참조하면 Coordinator → Queue → Coordinator 순환이 생긴다.

우선순위 큐의 **진실의 원천(source of truth)은 DB**다. `pipeline_execution.dispatch_status`와 `created_at`을 기반으로 `SELECT ... FOR UPDATE SKIP LOCKED`로 디스패치 대상을 원자적으로 선택한다. 인메모리 `ConcurrentSkipListMap`은 "이 인스턴스가 처리 중인 execution"을 빠르게 조회하기 위한 캐시 역할만 한다.

```java
@Component
@RequiredArgsConstructor
public class PriorityDispatchQueue {

    private final PipelineExecutionMapper executionMapper;

    // 인메모리 캐시: 이 인스턴스가 처리 중인 execution 추적 (빠른 조회용)
    private final ConcurrentSkipListMap<LocalDateTime, UUID> localCache
            = new ConcurrentSkipListMap<>();

    public void register(UUID executionId, LocalDateTime createdAt) {
        localCache.put(createdAt, executionId);
        // DB의 dispatch_status는 이미 PENDING (INSERT 시 기본값)
    }

    public void remove(UUID executionId) {
        localCache.values().remove(executionId);
        executionMapper.updateDispatchStatus(executionId, "DONE");
    }

    /** DB에서 우선순위 순으로 디스패치 대상 조회 (FOR UPDATE SKIP LOCKED) */
    public List<PipelineExecution> findPendingForDispatch() {
        return executionMapper.findPendingForDispatch();
    }

    public boolean isEmpty() {
        return localCache.isEmpty();
    }
}
```

DB 쿼리:

```sql
-- 분산 큐 패턴: 가장 오래된 미처리 실행을 원자적으로 선택
-- SKIP LOCKED: 다른 인스턴스가 잡고 있는 행은 건너뛰어 경합 없이 분배
SELECT * FROM pipeline_execution
WHERE status = 'RUNNING' AND dispatch_status = 'PENDING'
ORDER BY created_at ASC
FOR UPDATE SKIP LOCKED;
```

멀티 인스턴스에서 두 인스턴스가 동시에 `findPendingForDispatch()`를 호출하면, `SKIP LOCKED`에 의해 각 인스턴스가 서로 다른 파이프라인을 획득한다. 글로벌 우선순위가 보장되면서도 경합이 발생하지 않는다.

### DagExecutionCoordinator — 디스패치 엔진

948줄의 핵심 클래스다. 설계 문서의 모든 로직이 이 클래스에 집중되어 있다.

```
DagExecutionCoordinator
├─ startExecution()        — DAG 초기화 + 큐 등록 + 첫 디스패치
├─ onJobCompleted()        — Job 완료 → 상태 갱신 → 재디스패치 or 종료
├─ dispatchByPriority()    — 글로벌 락 → doDispatchByPriority()
├─ doDispatchByPriority()  — 우선순위 순회 + 백필 알고리즘 (핵심)
├─ finalizeExecution()     — SAGA 보상 + 상태 정리 + dispatch_status DONE
├─ executeJob()            — Jenkins 빌드 실행 (스레드풀에서)
└─ wakeUpWaitingExecutions() — executor 해제 시 대기 Job 재확인
```

---

## 전체 실행 흐름 (코드 레벨)

```
① PipelineEventConsumer.onPipelineEvent()
   → pipelineEngine.execute()  [비동기, 즉시 리턴]
   → 다음 메시지 소비

② DagExecutionCoordinator.startExecution()
   → executionMapper.updateStatus(RUNNING)
   → jobs + 의존성 로드
   → dagValidator.validate(jobs)
   → DagExecutionState.initialize()
   → priorityQueue.register(executionId, createdAt)
   → dispatchByPriority()  ← 여기서 첫 Job 디스패치

③ doDispatchByPriority()  [아래 "백필 알고리즘" 섹션에서 상세 해설]
   → DB 조회 (FOR UPDATE SKIP LOCKED) → readyJobs → Jenkins executor 확인 → 디스패치
   → executionMapper.updateDispatchStatus(id, "DISPATCHING")
   → jobExecutorPool.submit(() -> executeJob(...))

④ executeJob()
   → Jenkins REST API 호출 or Kafka 커맨드 발행
   → webhook 대기 (스레드 반환)

⑤ WebhookEventConsumer → DagExecutionCoordinator.onJobCompleted()
   → lock 획득
   → state.markCompleted(jobId)
   → 전체 완료? → finalizeExecution()
   → 아니면 → dispatchByPriority()  ← 다음 Job 디스패치 + 백필

⑥ finalizeExecution()
   → SAGA 보상 (실패 시)
   → executionStates.remove()
   → priorityQueue.remove()  ← dispatch_status → DONE
   → dispatchByPriority()  ← 대기 파이프라인 백필
```

핵심 사이클은 **③→④→⑤→③**이다. webhook이 도착할 때마다 `dispatchByPriority()`가 재호출되어, 우선순위 순으로 다음 Job을 디스패치한다. 이 사이클이 모든 Job이 완료될 때까지 반복된다.

---

## 백필이 발생하는 순간 (코드 추적)

`doDispatchByPriority()`의 이중 for 루프가 백필의 핵심이다. 실제 코드를 추적하며 백필이 어디서 발생하는지 보자.

```java
private void doDispatchByPriority() {
    var availableByJenkins = new HashMap<String, Integer>();

    // ── 외부 루프: DB에서 우선순위 순으로 조회 (FOR UPDATE SKIP LOCKED) ──
    for (var pending : priorityQueue.findPendingForDispatch()) {  // ← P1 → P2 → P3 순서
        UUID executionId = pending.getId();
        var state = executionStates.get(executionId);
        if (state == null || state.isAllDone()) continue;

        // tryLock — 다른 스레드가 이 execution을 처리 중이면 스킵
        var lock = executionLocks.get(executionId);
        if (!lock.tryLock()) continue;

        try {
            var readyJobIds = state.findReadyJobIds();   // ← DAG에서 준비된 Job

            // ── 내부 루프: 준비된 Job에 executor 배정 ──
            for (Long jobId : readyJobIds) {
                var tool = resolveJenkinsTool(job);
                int available = availableByJenkins.computeIfAbsent(
                    tool.url(), url -> /* Jenkins API로 가용 슬롯 계산 */);

                if (available == 0) {
                    registerWaitingJob(...);              // ← executor 부족 → 대기
                    continue;
                }

                // 디스패치!
                state.markRunning(jobId);
                jobExecutorPool.submit(() -> executeJob(...));
                availableByJenkins.put(url, available - 1);
            }
            // readyJobIds가 비었으면 → 자동으로 외부 루프의 다음 entry(P2)로
            // ★ 이 지점이 백필의 트리거다 ★
        } finally {
            lock.unlock();
        }
    }
}
```

**백필은 명시적 코드가 아니다.** for 루프의 자연스러운 진행이 백필이다.

```
P1의 readyJobs = []  (DAG 의존성 대기)
  → 내부 루프 즉시 종료
  → 외부 루프가 P2로 이동
  → P2의 readyJobs = [J1, J2]
  → executor 가용 → P2-J1 디스패치
  ★ P2가 P1의 빈 executor를 채움 = 백필
```

반대로 P1의 readyJobs가 있으면 P1이 먼저 executor를 가져간다. 외부 루프가 createdAt ASC 순서이므로 P1이 항상 P2보다 먼저 순회된다. 이것이 **우선순위 보장**이다.

---

## 동시성 제어 — 2단계 잠금

설계 문서에서는 ConcurrentSkipListMap의 lock-free 특성만 언급했지만, 실제 구현에서는 **2단계 잠금**이 추가되었다. executor 카운터의 정합성을 보장하기 위해서다.

### Level 1: dispatchLock (글로벌)

```java
private final ReentrantLock dispatchLock = new ReentrantLock();

public void dispatchByPriority() {
    if (priorityQueue.isEmpty()) return;
    dispatchLock.lock();           // ← 글로벌 락
    try {
        doDispatchByPriority();
    } finally {
        dispatchLock.unlock();
    }
}
```

`dispatchByPriority()`는 여러 지점에서 호출된다 — `startExecution()`, `onJobCompleted()`, `finalizeExecution()`, `wakeUpWaitingExecutions()`. 동시에 호출되면 `availableByJenkins` 카운터가 꼬일 수 있으므로 글로벌 락으로 직렬화한다.

### Level 2: executionLocks (per-execution)

```java
private final ConcurrentHashMap<UUID, ReentrantLock> executionLocks = new ConcurrentHashMap<>();

// doDispatchByPriority() 내부
if (!lock.tryLock()) continue;    // ← tryLock으로 데드락 방지
```

각 파이프라인 execution마다 독립 lock이 있다. `dispatchByPriority()`가 P1을 순회하는 동안 다른 스레드에서 P1의 `onJobCompleted()`가 호출될 수 있다. `tryLock()`을 사용하여 lock을 잡지 못하면 해당 execution을 스킵한다. 다음 `dispatchByPriority()` 호출 시 다시 시도한다.

**왜 `lock()`이 아니라 `tryLock()`인가?** 글로벌 dispatchLock을 잡은 상태에서 executionLock을 `lock()`으로 기다리면 데드락 가능성이 있다. `onJobCompleted()`가 executionLock을 잡고 `dispatchByPriority()`를 호출하면 dispatchLock을 기다리게 되기 때문이다.

```
스레드 A: dispatchLock.lock() → executionLock.lock() [대기]
스레드 B: executionLock.lock() → dispatchByPriority() → dispatchLock.lock() [대기]
→ 교착 상태!
```

`tryLock()`은 즉시 실패하므로 이 교착 상태를 방지한다.

### Semaphore 제거 — DB 분산 큐로 대체

DB 분산 큐 전환 이후 Semaphore는 제거되었다. 이전에는 `activePipelineSlots.tryAcquire(30s)`로 동시 파이프라인 수를 제한했지만, DB 큐(`dispatch_status`)가 파이프라인 순서를 관리하고 Jenkins executor 가용 체크가 실제 Job 제출 시점을 제어하므로 Semaphore의 역할이 중복되었다.

현재 Consumer는 이벤트 수신 → `pipelineEngine.execute()` 직접 호출만 담당한다. 슬롯 제어는 `DagExecutionCoordinator.dispatchByPriority()`에서 Jenkins executor 가용성을 확인하여 처리한다. Jenkins Queue에 넣지 않는 이유는 Jenkins 장애 시 내부 큐의 Job이 소실되기 때문이다.

---

## 설계 문서와 달라진 점

| 항목 | 설계 문서 | 실제 구현 | 이유 |
|------|----------|----------|------|
| dispatchByPriority() 위치 | PriorityDispatchQueue 내부 | DagExecutionCoordinator 내부 | 순환 의존성 방지. Queue가 Coordinator를 참조하면 안 됨 |
| 우선순위 큐 저장소 | ConcurrentSkipListMap (인메모리) | DB (`FOR UPDATE SKIP LOCKED`) + 인메모리 캐시 | 멀티 인스턴스에서 글로벌 우선순위 보장 |
| 동시성 제어 | ConcurrentSkipListMap의 lock-free만 언급 | 2단계 잠금 (dispatchLock + executionLocks) + DB 행 잠금 | executor 카운터 정합성 + 데드락 방지 + 분산 경합 방지 |
| executor 가용 계산 | `jenkinsRegistry.getAll()` 단일 소스 | Jenkins API + 앱 레벨 제한 `min(jenkinsAvailable, appLimit)` 이중 체크 | K8s dynamic agent는 totalExecutors=0이므로 앱 설정으로 폴백 |
| @PostConstruct 재구성 | 별도 @PostConstruct 메서드 | `startExecution()` 내부에서 이전 SUCCESS Job 사전 등록으로 부분 재시작 | 재시작보다 "새 실행이 이전 상태를 계승"하는 방식 채택 |

가장 주목할 차이는 **dispatchByPriority()의 위치**와 **큐 저장소**다. 설계에서는 Queue가 인메모리로 스케줄링을 담당했지만, 구현에서는 Queue를 DB 기반 분산 큐로 전환하고 Coordinator가 스케줄링을 수행한다. DB의 `dispatch_status` + `FOR UPDATE SKIP LOCKED`로 멀티 인스턴스 환경에서도 글로벌 우선순위를 보장한다.

---

## 참조

- [priority-backfill-scheduler.md](./08-priority-backfill-scheduler.md) — 설계 문서 (로드맵)
- [multi-jenkins-architecture.md](./02-multi-jenkins-architecture.md) — 멀티 Jenkins 전체 아키텍처
- [dispatch-strategy.md](../../skills/redpanda-playground/references/pipeline/dispatch-strategy.md) — 디스패치 전략 설계 근거
- [concurrency-theory-roadmap.md](./01-concurrency-theory-roadmap.md) — 이론 학습 로드맵
