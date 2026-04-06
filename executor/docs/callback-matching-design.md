# Jenkins 콜백 매칭 방식 설계: jobId + buildNo

## 배경

Jenkins는 `jobExcnId`(실행 건 식별자)를 모른다. Jenkins 파이프라인�� `projectId/purposeId/jobId` 경���로 생성되며, 콜백에서도 `jobId + buildNumber`만 돌아온다.

Executor는 dispatch command에서 `jobId`만 받고, Jenkins 인스턴스와 경로는 DB 조회(`pipeline_job → purpose → purpose_entry → support_tool`)로 자체 결정한다.

---

## 설계

```
PENDING → QUEUED (buildNo 없음)
  → 트리거(JOB_ID 파라미터) → buildNo 기록 안 함
  → Jenkins STARTED 콜백 (jobId + buildNumber)
    → findActiveByJobId(jobId)로 조회 → buildNo 기록 → RUNNING
  → Jenkins COMPLETED ��백 (jobId + buildNumber)
    → findByJobIdAndBuildNo(jobId, buildNo)로 조회 → SUCCESS/FAILURE
```

### dispatch command 필드

| 필드 | 용도 |
|------|------|
| `jobExcnId` | 실행 건 식별자 + 멱등성 키 |
| `pipelineExcnId` | 파이프라인 실행 건 (단건 시 null) |
| `jobId` | Job 정의 식별자 — Jenkins 정보 조회 + 콜백 매칭 |
| `priorityDt` | 우선순위 기준 시각 |
| `rgtrId` | 등록자 |
| `timestamp` | 발행 시각 |

제거된 필드: `jenkinsInstanceId` (DB 조회), `jobName` (UI용, Executor 불필요), `idempotencyKey` (`jobExcnId`가 대체)

### Jenkins 정보 조회 (JobDefinitionQueryPort)

Executor가 `jobId`로 cross-schema 조인하여 Jenkins 인스턴스와 경로를 결정한다:

```sql
SELECT pj.id as job_id, p.project_id, pj.purpose_id
     , st.id as jenkins_instance_id
     , pj.job_name
FROM public.pipeline_job pj
JOIN public.purpose p ON p.id = pj.purpose_id
JOIN public.purpose_entry pe ON pe.purpose_id = p.id AND pe.category = 'CI_CD_TOOL'
JOIN public.support_tool st ON st.id = pe.tool_id
WHERE pj.id = CAST(? AS BIGINT)
```

Jenkins 경로: `{projectId}/{purposeId}/{jobId}`

### buildNo 기록 시점

| 시점 | 동작 |
|------|------|
| triggerBuild | buildNo 기록 안 함 (`getNextBuildNumber()` ���거) |
| STARTED 콜백 | `findActiveByJobId(jobId)` → `recordBuildNo(buildNumber)` → RUNNING |
| COMPLETED 콜백 | `findByJobIdAndBuildNo(jobId, buildNo)` → SUCCESS/FAILURE |

이 방식의 장점: Jenkins가 실제로 할당한 buildNumber를 사용하므로 `getNextBuildNumber()` 예측값과의 불일치 문제가 원천적으로 없다.

---

## 안전성 근거

### 중복 방지가 핵심 안전장치

`DispatchEvaluatorService.dispatch()`는 같은 `jobId`에 대해 QUEUED/RUNNING 상태인 Job이 이미 있으면 새 디스패치를 차단한다. 따라서 **동일 jobId에 대해 동시에 활성 상태인 Job은 최대 1건**이다.

STARTED 콜백에서 `findActiveByJobId(jobId)`로 조회하면 항상 유일한 Job을 찾을 수 있다.

---

## 예외 케이스 분석

### 시나리오 1: QUEUED 후 장기 실패, 재실행 시 다른 Job과 충돌

```
1. Job A (jobId="JOB-001") → QUEUED → 트리거 실패 → PENDING (buildNo=null)
2. 장시간 경과 (Jenkins 다운)
3. Job B (jobId="JOB-001") 도착 → A는 PENDING이므로 duplicate check 통과
4. Job B → QUEUED → triggerBuild → STARTED 콜백 → buildNo=10 기록 → RUNNING
5. COMPLETED 콜백: jobId="JOB-001", buildNumber=10 → Job B 매칭
6. B 완료 → Job A 재디스패치 → triggerBuild → STARTED → buildNo=11 기록
7. COMPLETED: buildNumber=11 → Job A 매칭
```

**결과**: 문제 없음. 각 Job이 STARTED 시점에 실제 buildNo를 기록하므로 정확히 분리된다.

### 시나리오 2: STARTED 콜백 미도착 (Jenkins 비정상 종료)

```
1. Job A → QUEUED → triggerBuild → Jenkins pod crash
2. STARTED 콜백 없음 → buildNo 기록 안 됨 → Job은 QUEUED 유지
3. COMPLETED 콜백도 없음 → Job QUEUED에서 stuck
```

**대응**: 향후 RunningJobTimeoutChecker(미구현)가 장시간 QUEUED인 Job을 감지하여 PENDING으로 리셋할 예정.

### 시나리오 3: 같은 jobId 동시 빌드

중복 방지(`existsByJobIdAndStatusIn`)가 원천 차단. 불가능.

### 시나리오 4: STARTED 콜백 중복 도착

```
1. 같은 STARTED 콜백 2회 도착
2. 1회차: findActiveByJobId → QUEUED Job 찾음 → buildNo 기록 + RUNNING 전환
3. 2회차: findActiveByJobId → RUNNING Job 찾음 → isTerminal=false → markAsRunning 호출
   → transitionTo(RUNNING) → RUNNING→RUNNING은 허용되지 않음 → IllegalStateException (catch에서 처리)
```

**참고**: 현재 RUNNING→RUNNING 전이가 미허용이므로 중복 콜백 시 예외 발생. 향후 멱등 처리 추가 가���.

---

## 향후 개선

| 항목 | 설명 |
|------|------|
| QUEUED 타임아웃 감지 | STARTED 콜백 미도착 시 QUEUED→PENDING 리셋 |
| RUNNING→RUNNING 멱등 처리 | 중복 STARTED 콜백 시 예외 대신 무시 |
| Jenkins Queue Item API | 외부 트리거 허용 시 실제 buildNo 확인 |
| 에러 유형별 재시도 분류 | 404(즉시 FAILURE) vs Connection refused(재시도) |
