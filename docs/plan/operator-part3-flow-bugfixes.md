# Part 3: 전체 흐름 로직 구멍 수정

## 발견된 문제점 및 수정 방안

| # | 문제 | 위치 | 수정 |
|---|------|------|------|
| 3-1 | **QUEUED 상태 타임아웃 미처리** — QUEUED 전환 후 execute 메시지가 유실되면 영구 정체 | `StaleJobRecoveryService` | QUEUED 상태 체류 방어 로직 추가 |
| 3-2 | **빌드번호 레이스 컨디션** — `queryNextBuildNumber` → `triggerBuild` 사이에 다른 빌드가 끼면 번호 불일치 | `JobExecuteService` | 현행 유지 후 StaleRecovery가 보정 (Location 헤더 방식은 향후 개선) |
| 3-3 | **toolInfoCache / k8sModeCache 무한 캐싱** — Jenkins 정보 변경 시 반영 불가 | `JenkinsClient` | TTL 기반 캐시로 변경 (5분) |
| 3-4 | **dispatch에서 per-job Jenkins API 호출** — N개 job이면 N번 호출 | `DispatchEvaluatorService` | Part 2에서 인스턴스별 그룹화로 해결 |

---

## 수정 파일 목록 (4개)

### 3-1. QUEUED 상태 타임아웃 방어

#### `ExecutorProperties.java`

**파일**: `executor/src/main/java/com/study/playground/executor/execution/config/ExecutorProperties.java`

추가:
```java
/** QUEUED 상태 체류 허용 시간 (초) — 이 시간 초과 시 PENDING 복귀 or FAILURE */
private int queuedStaleSeconds = 60;

/** QUEUED 방어 스케줄러 주기 (ms) */
private long queuedCheckIntervalMs = 15000;
```

#### `StaleJobRecoveryService.java`

**파일**: `executor/src/main/java/com/study/playground/executor/execution/application/StaleJobRecoveryService.java`

메서드 추가:
```java
/**
 * QUEUED 상태에서 queuedStaleSeconds 이상 체류한 Job을 방어한다.
 * execute 메시지가 유실된 경우 PENDING으로 복귀시켜 재디스패치하거나,
 * 최대 재시도 초과 시 FAILURE로 전환한다.
 */
public void recoverStaleQueued() {
    var cutoff = LocalDateTime.now().minusSeconds(properties.getQueuedStaleSeconds());
    var staleJobs = jobPort.findByStatusAndMdfcnDtBefore(ExecutionJobStatus.QUEUED, cutoff);

    for (ExecutionJob job : staleJobs) {
        log.warn("[StaleRecovery] QUEUED stale detected: jobExcnId={}, lastModified={}"
                , job.getJobExcnId(), job.getMdfcnDt());
        dispatchService.retryOrFail(job, properties.getJobMaxRetries());
        jobPort.save(job);
    }
}
```

#### `StaleJobRecoveryScheduler.java`

**파일**: `executor/src/main/java/com/study/playground/executor/execution/infrastructure/scheduler/StaleJobRecoveryScheduler.java`

스케줄러 메서드 추가:
```java
@Scheduled(fixedDelayString = "${pipeline.queued-check-interval-ms:15000}")
public void recoverStaleQueued() {
    staleJobRecoveryService.recoverStaleQueued();
}
```

### 3-2. 빌드번호 레이스 컨디션

현행 유지. StaleRecovery가 보정하므로 즉시 수정 불필요. 향후 `triggerBuild` 응답의 Location 헤더에서 queue item ID를 추출하여 실제 빌드번호를 확인하는 방식으로 개선 가능.

### 3-3. 캐시 TTL 적용

**파일**: `executor/src/main/java/com/study/playground/executor/execution/infrastructure/jenkins/JenkinsClient.java`

(a) 캐시 래퍼 record 추가:
```java
record CachedToolInfo(JenkinsToolInfo info, Instant cachedAt) {}
private final Map<Long, CachedToolInfo> toolInfoCache = new ConcurrentHashMap<>();
private static final Duration CACHE_TTL = Duration.ofMinutes(5);
```

(b) `getToolInfo()` 메서드 변경:
```java
private JenkinsToolInfo getToolInfo(long jenkinsInstanceId) {
    var cached = toolInfoCache.get(jenkinsInstanceId);
    if (cached != null && Instant.now().isBefore(cached.cachedAt().plus(CACHE_TTL))) {
        return cached.info();
    }

    var sql = "SELECT url, username, credential, max_executors FROM operator.support_tool WHERE id = ?";
    var info = jdbcTemplate.queryForObject(sql, (rs, rowNum) -> new JenkinsToolInfo(
            rs.getString("url"), rs.getString("username"), rs.getString("credential")
            , rs.getInt("max_executors")), jenkinsInstanceId);

    toolInfoCache.put(jenkinsInstanceId, new CachedToolInfo(info, Instant.now()));
    return info;
}
```

(c) k8sModeCache도 동일하게 TTL 적용:
```java
record CachedK8sMode(boolean isK8s, Instant cachedAt) {}
private final Map<Long, CachedK8sMode> k8sModeCache = new ConcurrentHashMap<>();

// isK8sMode() 메서드에서도 동일한 TTL 패턴 적용
```

---

## 검증

```bash
./gradlew :executor:compileJava
./gradlew :executor:test
```

### 수동 검증 항목
- QUEUED 상태 장기 체류 job이 자동 복구되는지 확인 (queuedStaleSeconds 후)
- Jenkins 정보 변경 후 5분 내 캐시 갱신되는지 확인
- 멀티 Jenkins 인스턴스 환경에서 인스턴스별 슬롯 제한 동작 확인
- SUBMITTED 상태 job이 슬롯 카운팅에 포함되는지 확인
- Jenkins 인스턴스 다운 시 해당 인스턴스 job이 skip되는지 확인
