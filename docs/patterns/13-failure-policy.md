# 실패 정책 패턴 (Failure Policy)

## 문제

DAG 실행 중 Job이 실패하면 어떻게 할 것인가? 단일 정답은 없고, 파이프라인의 성격에 따라 다른 전략이 필요하다.

## 세 가지 정책

### STOP_ALL (기본)

실패 발생 시 새 Job dispatch를 중단하고, RUNNING 중인 Job의 완료를 기다린 뒤 SAGA 보상을 실행한다.

적합한 경우: 모든 Job이 서로 밀접하게 관련되어 있고, 하나라도 실패하면 전체가 무의미한 파이프라인. 예를 들어 Build → Test → Deploy 체인.

### SKIP_DOWNSTREAM

실패한 Job의 전이적 하위(BFS)만 SKIP하고, 독립 브랜치는 계속 실행한다.

```
    A
   / \
  B   C    ← B가 실패하면
   \ /
    D      ← B의 downstream인 D만 SKIP, C는 계속 실행
```

적합한 경우: diamond DAG에서 독립 브랜치가 있고, 한 브랜치 실패가 다른 브랜치에 영향을 주지 않는 경우.

핵심 규칙: RUNNING 중인 하위 Job은 SKIP 대상에서 제외한다. 완료 후 `onJobCompleted()`에서 재평가한다.

### FAIL_FAST

실패 즉시 모든 PENDING Job을 SKIP 처리한다. RUNNING 중인 Job은 완료를 기다린 뒤 최종 판정.

적합한 경우: 빠른 피드백이 중요한 CI 파이프라인. 첫 실패 시 즉시 알림을 보내고 나머지를 건너뛰어 리소스를 절약한다.

## allDownstream() 구현

```java
public Set<Long> allDownstream(Long failedJobId) {
    Set<Long> downstream = new LinkedHashSet<>();
    Queue<Long> queue = new LinkedList<>();
    queue.add(failedJobId);
    while (!queue.isEmpty()) {
        Long current = queue.poll();
        for (Long succ : successorGraph.getOrDefault(current, Set.of())) {
            if (!downstream.contains(succ) && !runningJobIds.contains(succ)) {
                downstream.add(succ);
                queue.add(succ);
            }
        }
    }
    return downstream;
}
```

BFS로 successor graph를 순회하되, RUNNING job은 제외한다. `LinkedHashSet`을 사용하여 삽입 순서(BFS 레벨 순서)를 유지한다.

## isAllDone() 변경

기존: `completed + failed == total`
변경: `completed + failed + skipped == total`

SKIP된 Job도 "종료된 것"으로 간주해야 전체 완료 판정이 정확해진다.
