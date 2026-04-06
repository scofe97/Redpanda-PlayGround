# Jenkins 콜백 매칭 방식 설계: jobId + buildNo

## 배경

Jenkins는 `jobExcnId`(실행 건 식별자)를 모른다. Jenkins 파이프라인은 `projectId/purposeId/jobId` 경로로 생성되며, 콜백에서도 `jobId + buildNumber`만 돌아온다.

따라서 Executor가 Jenkins에 `EXECUTION_JOB_ID`를 전달하는 현재 PoC 방식을 제거하고, `jobId + buildNo`로 매칭하는 실제 설계로 전환한다.

---

## 매칭 방식 비교

### 현재 (PoC)

```
PENDING → nextBuildNumber 조회 → buildNo 기록 + QUEUED → 트리거(EXECUTION_JOB_ID) → 콜백(executionJobId) 매칭
```

Jenkins에 `EXECUTION_JOB_ID`를 빌드 파라미터로 전달하고, webhook이 이를 콜백에 포함시킨다.
`jobExcnId`로 ExecutionJob을 직접 조회한다.

### 변경 후 (실제 설계)

```
PENDING → QUEUED (buildNo 없음) → 트리거(JOB_ID) → buildNo 기록 → 콜백(jobId + buildNumber) 매칭
```

Jenkins에 `JOB_ID`만 전달한다. 콜백에서 `jobId + buildNumber`로 ExecutionJob을 조회한다.
`nextBuildNumber` 사전 채번은 DispatchEvaluatorService에서 제거하고, triggerBuild 응답에서 buildNo를 기록한다.

---

## 안전성 근거

### 중복 방지가 핵심 안전장치

Executor의 `DispatchEvaluatorService.dispatch()`는 같은 `jobId`에 대해 QUEUED/RUNNING 상태인 Job이 이미 있으면 새 디스패치를 **차단**한다.

```java
if (jobPort.existsByJobIdAndStatusIn(job.getJobId(), List.of(QUEUED, RUNNING))) {
    return; // skip
}
```

이 규칙 때문에 **동일 jobId에 대해 동시에 활성 상태인 Job은 최대 1건**이다. 따라서 `jobId + buildNo` 조합으로 조회하면 항상 유일한 ExecutionJob을 찾을 수 있다.

### nextBuildNumber 사전 채번 제거 이유

현재 DispatchEvaluatorService는 QUEUED 전환 전에 `queryNextBuildNumber()`를 호출하여 buildNo를 사전에 기록한다. 하지만 이 값은 **예측값**이지 확정값이 아니다.

`getNextBuildNumber()` 호출과 실제 `triggerBuild()` 사이에 다른 빌드가 끼어들면 채번한 번호와 실제 빌드 번호가 어긋난다. 중복 방지 덕분에 Executor 내부에서는 발생하지 않지만, 외부 트리거가 있으면 가능하다.

triggerBuild 내부에서 `getNextBuildNumber()`를 호출하고 즉시 트리거하면 시간 간격이 최소화되어 더 정확하다. 따라서 사전 채번은 제거하고, **buildNo 기록을 triggerBuild 이후로 이동**한다.

---

## 예외 케이스 분석

### 시나리오 1: QUEUED 후 장기 실패, 재실행 시 다른 Job과 충돌

```
1. Job A (jobId="JOB-001") → QUEUED → 트리거 실패 → retryCnt=1, PENDING (buildNo=null)
2. 장시간 경과 (Jenkins 다운)
3. Job B (jobId="JOB-001") 도착
   → duplicate check: A는 PENDING (QUEUED/RUNNING 아님) → B 통과
4. Job B → QUEUED → triggerBuild → buildNo=10 기록
5. 콜백: jobId="JOB-001", buildNumber=10 → findByJobIdAndBuildNo → Job B 매칭
6. B 완료 (SUCCESS) → QUEUED/RUNNING 없음
7. tryDispatch → Job A 재디스패치 → QUEUED → triggerBuild → buildNo=11 기록
8. 콜백: jobId="JOB-001", buildNumber=11 → Job A 매칭
```

**결과**: 문제 없음. buildNo가 다르므로 각 Job이 정확히 분리된다.

### 시나리오 2: nextBuildNumber 부정확 — 외부 트리거로 빌드 번호 어긋남

```
1. Job A → QUEUED → getNextBuildNumber() = 10
2. 외부에서 같은 Jenkins Job을 트리거 → buildNo=10이 외부 빌드에 사용됨
3. Executor가 트리거 → Jenkins가 실제로 buildNo=11을 할당
4. Executor는 buildNo=10으로 기록 (실제는 11)
5. 콜백: jobId="JOB-001", buildNumber=11 → findByJobIdAndBuildNo("JOB-001", 11) → 매칭 실패
6. Job A는 QUEUED 상태에서 stuck
```

**위험도**: 높음
**발생 가능성**: "Executor만 트리거" 가정 하에서는 발생 불가
**대응 방안**: 향후 다른 시스템도 트리거할 경우, Jenkins Queue Item API(`/queue/item/{id}/api/json`)를 폴링하여 실제 할당된 buildNo를 확인하는 방식으로 업그레이드

### 시나리오 3: 콜백이 buildNo 기록 전에 도착

```
1. triggerBuild 성공 → Jenkins 즉시 빌드 시작 → STARTED 콜백 발송
2. Executor가 아직 buildNo를 DB에 저장하지 않음
3. findByJobIdAndBuildNo → 매칭 실패
```

**발생 가능성**: 극히 낮음. K8s agent pod 생성에 ~40초 소요, DB 저장은 밀리초 수준. triggerBuild와 DB 저장은 같은 `@Transactional` 안에서 실행되므로, 콜백이 도착하는 시점에는 이미 커밋 완료된 상태.

### 시나리오 4: 트리거 성공했지만 DB 저장 실패

```
1. triggerBuild 성공 (Jenkins에서 빌드 시작됨)
2. recordBuildNo → jobPort.save() 실패 (DB 장애)
3. 트랜잭션 롤백 → buildNo가 DB에 없음
4. 콜백 매칭 불가 → Jenkins에 고아 빌드 잔존
```

**발생 가능성**: 극히 낮음
**영향**: Jenkins에 고아 빌드가 남지만, ExecutionJob은 QUEUED 상태에서 다음 tryDispatch 시 재시도됨
**참고**: 이 문제는 현재 설계에도 동일하게 존재

### 시나리오 5: 같은 jobId로 동시 빌드 요청

```
1. Job A (jobId="JOB-001") → QUEUED → RUNNING
2. Job B (jobId="JOB-001") 도착
3. duplicate check: existsByJobIdAndStatusIn("JOB-001", [QUEUED, RUNNING]) = true
4. Job B → PENDING에 머무름 (디스패치 차단)
```

**발생 가능성**: 불가능. 중복 방지가 원천 차단.

---

## 종합 평가

| 시나리오 | 위험도 | 발생 가능성 | 현재 설계에도 동일? |
|----------|--------|-----------|---------------------|
| 장기 실패 후 재실행 | 없음 | - | - |
| nextBuildNumber 부정확 | 높음 | 낮음 (Executor만 트리거 시 발생 불가) | 예 |
| 콜백 선착 | 낮음 | 극히 낮음 | 예 |
| DB 저장 실패 | 중간 | 극히 낮음 | 예 |
| 동시 빌드 충돌 | 없음 | 불가능 | - |

**결론**: `jobId + buildNo` 매칭 방식은 안전하다. 식별된 위험(nextBuildNumber 부정확)은 현재 설계에도 동일하게 존재하며, "Executor만 트리거"라는 전제 하에서는 발생하지 않는다.

---

## 향후 개선 (Executor 외부 트리거를 허용할 경우)

`getNextBuildNumber()` 기반 예측 대신, Jenkins의 실제 빌드 할당을 확인하는 방식으로 전환:

1. `POST /job/{path}/buildWithParameters` → 응답 `Location` 헤더에서 queue item ID 추출
2. `GET /queue/item/{id}/api/json` 폴링 → `executable.number` 필드에서 실제 buildNo 확인
3. 실제 buildNo를 DB에 기록

이 방식은 외부 트리거와의 빌드 번호 충돌을 완전히 방지한다.
