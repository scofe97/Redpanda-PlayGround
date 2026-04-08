# Part 2: Executor 디스패치 로직 개선

## 전제조건
- Part 1 완료 후 진행 (cross-schema 쿼리가 `operator.*`로 변경된 상태)
- `support_tool` 테이블에 `max_executors INT DEFAULT 2` 컬럼이 이미 존재 (V42 마이그레이션)

## 현행 흐름 vs 개선 흐름

```
현행 DispatchEvaluatorService.tryDispatch():
┌──────────┐    ┌───────────────┐    ┌────────────────┐    ┌────────┐
│ PENDING  │───▶│ 개별 duplicate│───▶│ 개별 Jenkins   │───▶│ QUEUED │
│ 조회     │    │ check         │    │ slot check     │    │ 전환   │
│(SKIP LOCK│    │(per job)      │    │(per job)       │    │        │
└──────────┘    └───────────────┘    └────────────────┘    └────────┘
                                     ↑ Jenkins API 매번 호출 (N번)

개선 DispatchEvaluatorService.tryDispatch():
┌──────────┐    ┌──────────────────┐    ┌─────────────────┐    ┌──────────────┐    ┌────────┐
│ PENDING  │───▶│ jenkinsInstanceId│───▶│ 인스턴스별      │───▶│ 가용 인스턴스│───▶│ QUEUED │
│ 전체조회 │    │ 별 그룹화 (MAP) │    │ 헬스체크+가용성 │    │ job만        │    │ 전환   │
│(SKIP LOCK│    │ (중복 제거)      │    │ 1회씩 체크      │    │ 슬롯 제한    │    │        │
└──────────┘    └──────────────────┘    └─────────────────┘    └──────────────┘    └────────┘
                                         ↑ Jenkins API 인스턴스당 1번만
```

---

## 수정 파일 목록 (8개)

### 2-1. `JenkinsQueryPort.java` — 메서드 2개 추가

**파일**: `executor/src/main/java/com/study/playground/executor/execution/domain/port/out/JenkinsQueryPort.java`

추가:
```java
/** Jenkins 인스턴스 연결 가능 여부 (헬스체크) */
boolean isReachable(long jenkinsInstanceId);

/** support_tool.max_executors 조회 */
int getMaxExecutors(long jenkinsInstanceId);
```

### 2-2. `JenkinsClient.java` — 구현 추가 + JenkinsToolInfo 변경

**파일**: `executor/src/main/java/com/study/playground/executor/execution/infrastructure/jenkins/JenkinsClient.java`

(a) `JenkinsToolInfo` record 변경:
```java
// 기존
record JenkinsToolInfo(String url, String username, String credential) {}
// 변경
record JenkinsToolInfo(String url, String username, String credential, int maxExecutors) {}
```

(b) `getToolInfo()` SQL 변경:
```java
// 기존
var sql = "SELECT url, username, credential FROM public.support_tool WHERE id = ?";
return jdbcTemplate.queryForObject(sql, (rs, rowNum) -> new JenkinsToolInfo(
        rs.getString("url"), rs.getString("username"), rs.getString("credential")), id);
// 변경
var sql = "SELECT url, username, credential, max_executors FROM operator.support_tool WHERE id = ?";
return jdbcTemplate.queryForObject(sql, (rs, rowNum) -> new JenkinsToolInfo(
        rs.getString("url"), rs.getString("username"), rs.getString("credential"),
        rs.getInt("max_executors")), id);
```

(c) `isReachable()` 구현:
```java
@Override
public boolean isReachable(long jenkinsInstanceId) {
    try {
        var baseUri = resolveJenkinsUri(jenkinsInstanceId);
        var auth = buildAuthHeader(jenkinsInstanceId);
        feignClient.getComputerStatus(baseUri, auth);
        return true;
    } catch (Exception e) {
        log.warn("[JenkinsClient] Unreachable: instanceId={}, error={}"
                , jenkinsInstanceId, e.getMessage());
        return false;
    }
}
```

(d) `getMaxExecutors()` 구현:
```java
@Override
public int getMaxExecutors(long jenkinsInstanceId) {
    return getToolInfo(jenkinsInstanceId).maxExecutors();
}
```

### 2-3. `ExecutionJobPort.java` — 메서드 1개 추가

**파일**: `executor/src/main/java/com/study/playground/executor/execution/domain/port/out/ExecutionJobPort.java`

추가:
```java
/** 특정 Jenkins 인스턴스에 연결된 활성 Job 수 카운팅 */
int countActiveJobsByJenkinsInstanceId(long jenkinsInstanceId, List<ExecutionJobStatus> statuses);
```

### 2-4. `ExecutionJobJpaRepository.java` — native query 추가

**파일**: `executor/src/main/java/com/study/playground/executor/execution/infrastructure/persistence/ExecutionJobJpaRepository.java`

추가:
```java
@Query(value = """
        SELECT COUNT(*) FROM executor.execution_job ej
        JOIN operator.job j ON j.job_id = ej.job_id
        JOIN operator.purpose p ON p.id = CAST(j.preset_id AS BIGINT)
        JOIN operator.purpose_entry pe ON pe.purpose_id = p.id AND pe.category = 'CI_CD_TOOL'
        JOIN operator.support_tool st ON st.id = pe.tool_id
        WHERE st.id = :jenkinsInstanceId AND ej.excn_stts IN :statuses
        """, nativeQuery = true)
int countActiveJobsByJenkinsInstanceId(
        @Param("jenkinsInstanceId") long jenkinsInstanceId
        , @Param("statuses") List<String> statuses);
```

### 2-5. `ExecutionJobPersistenceAdapter.java` — 메서드 구현

**파일**: `executor/src/main/java/com/study/playground/executor/execution/infrastructure/persistence/ExecutionJobPersistenceAdapter.java`

추가:
```java
@Override
public int countActiveJobsByJenkinsInstanceId(long jenkinsInstanceId, List<ExecutionJobStatus> statuses) {
    var statusNames = statuses.stream().map(Enum::name).toList();
    return jpaRepository.countActiveJobsByJenkinsInstanceId(jenkinsInstanceId, statusNames);
}
```

### 2-6. `DispatchEvaluatorService.java` — 핵심 리팩토링

**파일**: `executor/src/main/java/com/study/playground/executor/execution/application/DispatchEvaluatorService.java`

전체 `tryDispatch()` 메서드를 다음으로 교체:

```java
private static final List<ExecutionJobStatus> ACTIVE_STATUSES = List.of(
        ExecutionJobStatus.QUEUED, ExecutionJobStatus.SUBMITTED, ExecutionJobStatus.RUNNING);

@Override
@Transactional
public void tryDispatch() {
    // 1. PENDING 전체 조회 (FOR UPDATE SKIP LOCKED)
    List<ExecutionJob> pendingJobs = jobPort.findDispatchableJobs(properties.getMaxBatchSize());
    if (pendingJobs.isEmpty()) return;

    // 2. jenkinsInstanceId별 그룹화 (중복 제거)
    Map<Long, List<ExecutionJob>> jobsByInstance = new LinkedHashMap<>();

    for (ExecutionJob job : pendingJobs) {
        var defInfo = jobDefinitionQueryPort.load(job.getJobId());
        jobsByInstance.computeIfAbsent(defInfo.jenkinsInstanceId(), k -> new ArrayList<>()).add(job);
    }

    // 3. 인스턴스별 사전 점검 + 디스패치
    for (var entry : jobsByInstance.entrySet()) {
        long instanceId = entry.getKey();
        List<ExecutionJob> jobs = entry.getValue();

        try {
            dispatchForInstance(instanceId, jobs);
        } catch (Exception e) {
            log.error("[Dispatch] Failed for instanceId={}: {}", instanceId, e.getMessage(), e);
        }
    }
}

private void dispatchForInstance(long instanceId, List<ExecutionJob> jobs) {
    // 3a. 헬스체크
    if (!jenkinsQueryPort.isReachable(instanceId)) {
        log.warn("[Dispatch] Jenkins unreachable: instanceId={}", instanceId);
        return;
    }

    // 3b. 가용 슬롯 계산 (QUEUED + SUBMITTED + RUNNING)
    int activeCount = jobPort.countActiveJobsByJenkinsInstanceId(instanceId, ACTIVE_STATUSES);
    int maxSlots = jenkinsQueryPort.getMaxExecutors(instanceId);
    int remainingSlots = maxSlots - activeCount;

    if (remainingSlots <= 0) {
        log.debug("[Dispatch] No slots: instanceId={}, active={}, max={}"
                , instanceId, activeCount, maxSlots);
        return;
    }

    // 3c. 가용 슬롯만큼 디스패치
    for (ExecutionJob job : jobs) {
        if (remainingSlots <= 0) break;

        if (jobPort.existsByJobIdAndStatusIn(job.getJobId(), ACTIVE_STATUSES)) {
            log.debug("[Dispatch] Duplicate skip: jobId={}", job.getJobId());
            continue;
        }

        dispatchService.prepareForDispatch(job);
        jobPort.save(job);
        publishPort.publishExecuteCommand(job);
        remainingSlots--;

        log.info("[Dispatch] Job queued: jobExcnId={}, jobId={}, instanceId={}, remainingSlots={}"
                , job.getJobExcnId(), job.getJobId(), instanceId, remainingSlots);
    }
}
```

import 추가:
```java
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Map;
import com.study.playground.executor.execution.domain.model.JobDefinitionInfo;
```

기존 `dispatch(ExecutionJob job)` private 메서드는 삭제.

### 2-7. `JobDefinitionInfo.java` — 변경 없음

기존 record가 이미 `jenkinsInstanceId`를 포함하므로 변경 불필요.

---

## SUBMITTED 카운팅 반영 설명

현재 `isImmediatelyExecutable()`은 Jenkins API만 보고 판단한다. 새 로직에서는 DB 카운팅(QUEUED + SUBMITTED + RUNNING)을 기준으로 판단하므로, SUBMITTED가 자동으로 슬롯 카운팅에 포함된다.

## 검증

```bash
./gradlew :executor:compileJava
./gradlew :executor:test
```
