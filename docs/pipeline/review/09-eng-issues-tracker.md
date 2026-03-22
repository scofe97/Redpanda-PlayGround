# 엔지니어링 이슈 추적기

DAG 엔진 코드 리뷰에서 발견된 엔지니어링 이슈(ENG)와 데이터베이스 이슈(DB)를 통합 추적한다.

---

## ENG 이슈 (DAG 엔진)

출처: `review/07-phase2-dag-engine.md`

| ID | 심각도 | 설명 | 상태 | 해결 방법 |
|----|--------|------|------|----------|
| ENG-1 | MAJOR | `topologicalSort()` 중복 연산 — `validate()`와 `topologicalSort()` 모두 Kahn's algorithm을 각자 실행하여 그래프 구축이 2회 반복됨 | ✅ RESOLVED | `validate()`와 `topologicalSort()` 모두 `private runKahns()`로 위임. 그래프 구축(inDegree, adjacency) 1회로 통합 |
| ENG-2 | MINOR | `validateConnectivity()` 미사용 파라미터 — `originalInDegree`가 전달되지만 메서드 내에서 사용되지 않음 | ✅ RESOLVED | `validateConnectivity()` 시그니처에서 `originalInDegree` 파라미터 삭제 |
| ENG-3 | CRITICAL | `DagExecutionState` record 불변성 위반 — `completedJobIds`, `runningJobIds`, `failedJobIds`가 가변 `HashSet`이고 접근자가 내부 Set 참조를 그대로 노출 | ✅ RESOLVED | record → class 전환. `markCompleted/markRunning/markFailed/markSkipped/removeRunning` 전용 변경 메서드 제공. 외부 접근자(`runningJobIds()`, `failedJobIds()`)는 `Set.copyOf()` 방어적 복사 반환. `skippedJobIds`, `failurePolicy` 필드 추가 |
| ENG-4 | MAJOR | `dispatchReadyJobs()` lock reentrance 복잡성 — `lock.isHeldByCurrentThread()` 조건 분기로 호출 경로가 복잡해져 향후 데드락 위험 | 🔧 WONTFIX | 현재 `isHeldByCurrentThread()` 사용은 1곳(dispatchReadyJobs)뿐이며 동작은 정상. 방어적 패턴으로 유지 |
| ENG-5 | MAJOR | `executionStates`/`executionLocks` 메모리 누수 — 앱 크래시 시 RUNNING 상태 실행이 메모리에 남고 재시작 복원 미구현 | ✅ RESOLVED | `@Scheduled(fixedDelay = 300_000)` `cleanupStaleExecutions()` 5분 주기 정리 + `@PostConstruct` `recoverRunningExecutions()`로 재시작 시 RUNNING 실행 복원 구현 |
| ENG-6 | MAJOR | `startExecution()` lock 없이 상태 초기화 — `executionStates.put()` 후 `dispatchReadyJobs()` 사이에 빠른 webhook 콜백이 도달하면 race condition 가능 | ✅ RESOLVED | lock 등록 직후 `lock.lock()`하고, lock 범위 안에서 `DagExecutionState.initialize()` + `executionStates.put()` + `dispatchReadyJobs()` 수행. race window 제거 |
| ENG-7 | MINOR | `IMPORT → JenkinsCloneAndBuildStep` placeholder 매핑 — IMPORT(외부 저장소 산출물 임포트)가 BUILD와 동일한 실행기를 사용하여 의미 불일치 | ⚠️ OPEN | `JenkinsCloneAndBuildStep` placeholder 유지 중. 주석에 `// placeholder: IMPORT 전용 executor 미구현` 명시. IMPORT 전용 executor 미구현, 요구사항 확정 후 구현 예정 |

---

## DB 이슈 (스키마)

출처: `review/06-phase2-db-migration.md`

| ID | 심각도 | 설명 | 상태 | 해결 방법 |
|----|--------|------|------|----------|
| DB-1 | MINOR | `pipeline_job_dependency`에 `depends_on_job_id` 역방향 인덱스 없음 — "이 Job에 의존하는 Job 찾기" 역방향 조회 시 full scan 발생 | ✅ RESOLVED | V21 마이그레이션에서 `idx_job_dependency_depends_on` 인덱스 추가됨 |
| DB-2 | MINOR | `pipeline_job.job_type VARCHAR(30)`에 CHECK 제약 없음 — 잘못된 enum 값이 DB에 저장될 수 있음 | ⚠️ OPEN | 애플리케이션 레벨 enum 검증으로 대체 중. DB 레벨 보호 부재 |
| DB-3 | MAJOR | `pipeline_step.job_id`에 인덱스 없음 — `jobId → stepOrder` 매핑 직접 조회 시 full scan | ✅ RESOLVED | V21 마이그레이션에서 `idx_job_execution_job_id` 인덱스 추가됨 |
| DB-4 | MAJOR | `pipeline_execution.pipeline_definition_id`에 인덱스 없음 — `PipelineDefinitionService.delete()` 시 `findByPipelineDefinitionId()` full scan | ✅ RESOLVED | V21 마이그레이션에서 `idx_execution_definition_id` 인덱스 추가됨 |

---

## 알려진 버그

### BUG-1: buildConfigXml() 파라미터 정의 누락

**심각도**: MAJOR
**발견일**: 2026-03-22
**증상**: BUILD/DEPLOY Job에서 `configJson`의 사용자 파라미터(GIT_URL, BRANCH 등)가 Jenkins에 전달되지 않음
**원인**: `JenkinsAdapter.buildConfigXml()`이 `parameterDefinitions`에 `EXECUTION_ID`와 `STEP_ORDER`만 정적 정의. `configJson`에서 사용하는 파라미터가 Jenkins Job XML에 포함되지 않아 Jenkins가 해당 파라미터를 알 수 없음
**영향**: E2E 테스트에서 DEPLOY 아티팩트 다운로드 미완성
**해결 방향**: `PipelineJob.parameterSchemas()`에서 스키마를 읽어 `buildConfigXml()` 내 `parameterDefinitions`를 동적으로 생성
**상태**: ⚠️ OPEN

---

## 이슈 통계

| 상태 | 개수 |
|------|------|
| ✅ RESOLVED | 8 |
| ⚠️ OPEN | 3 |
| 🔧 WONTFIX | 1 |
| **합계** | **12** |

> 원본 리뷰: `review/07-phase2-dag-engine.md`, `review/06-phase2-db-migration.md`
