# Job 재시도 패턴 (Exponential Backoff Retry)

## 문제

동기 실행 Job이 일시적 오류(네트워크 타임아웃, Jenkins 과부하 등)로 실패할 때 즉시 FAILED 처리하면 전체 파이프라인이 실패한다. 재시도로 복구할 수 있는 일시적 오류를 구분해야 한다.

## 해결: ScheduledExecutorService + Exponential Backoff

```java
// executeJob() catch 블록
catch (Exception e) {
    int currentRetry = je.getRetryCount();
    if (currentRetry < props.jobMaxRetries()) {
        jobExecutionMapper.incrementRetryCount(je.getId());
        long delaySeconds = 1L << currentRetry;  // 1s, 2s, 4s
        retryScheduler.schedule(
            () -> executeJob(execution, job, jobOrder),
            delaySeconds, TimeUnit.SECONDS);
        return;  // onJobCompleted 호출하지 않음
    }
    // maxRetries 초과 → FAILED
}
```

## 핵심 설계 결정

### 동기 예외만 재시도하는 이유

webhook timeout은 재시도 대상이 아니다. Jenkins가 빌드를 이미 트리거했고, 아직 실행 중일 수 있기 때문이다. 같은 빌드를 다시 트리거하면 중복 빌드가 발생한다. webhook timeout은 WebhookTimeoutChecker가 FAILED로 처리하고, 필요하면 부분 재시작 API로 재시작한다.

### 재시도 중 상태

재시도 스케줄링 시 job 상태를 PENDING으로 되돌린다. 이유는 RUNNING 상태를 유지하면 stale 정리기가 타임아웃으로 간주할 수 있고, FAILED로 두면 DAG 엔진이 실패 정책을 적용하기 때문이다. PENDING으로 되돌리면 재시도가 끝날 때까지 DAG 엔진이 해당 job을 "아직 시작 전"으로 간주한다.

단, `state.markRunning()`은 유지한다. DagExecutionState에서는 여전히 running으로 추적되어 동시 실행 슬롯을 점유하고, 다른 ready job이 해당 슬롯을 차지하는 것을 방지한다.

### Exponential Backoff

`2^retryCount` 초: retry 0 → 1초, retry 1 → 2초, retry 2 → 4초. 짧은 간격으로 시작하되 점진적으로 늘려서 일시적 과부하 상황에서 부하를 줄인다. 최대 재시도 횟수가 2(기본값)이므로 최대 대기 시간은 4초다.

### retry_count DB 컬럼

`V32__add_retry_count_to_job_execution.sql`로 추가. 기본값 0으로 기존 데이터와 호환. 복구 시에도 이 값을 참조하여 이미 재시도한 횟수를 알 수 있다.
