# Step 1: Phase 2 전체 구조 오버뷰

## 핵심 개념

Phase 2는 기존 고정 순차 파이프라인에 **DAG 기반 병렬 실행**을 추가한 것이다. 세 가지 핵심 분리가 설계를 관통한다:

1. **정의(Definition) vs 실행(Execution)**: 동일한 파이프라인 정의를 여러 번 실행할 수 있다. `PipelineDefinition`은 "무엇을 어떤 순서로"를, `PipelineExecution`은 "언제 실행했고 결과가 무엇인지"를 담당한다.
2. **Job vs Step**: Job은 사용자가 구성하는 고수준 작업 단위(BUILD, DEPLOY)이고, Step은 엔진이 추적하는 저수준 실행 단위(GIT_CLONE, BUILD, DEPLOY)이다. 같은 Job 정의로 실행할 때마다 새 Step이 생성된다.
3. **순차 모드 vs DAG 모드**: `PipelineExecution.pipelineDefinitionId`가 null이면 기존 순차 모드, non-null이면 DAG 모드로 분기한다. 기존 코드 변경을 최소화하는 하위 호환 전략이다.

## 변경 파일 맵

```
신규 ~19개 / 수정 ~11개

com.study.playground.pipeline
├── domain/                                    (신규 3, 수정 2)
│   ├── PipelineDefinition.java               ← 신규: 파이프라인 정의 엔티티
│   ├── PipelineJob.java                      ← 신규: DAG 노드 (dependsOnJobIds)
│   ├── PipelineJobType.java                  ← 신규: BUILD/DEPLOY/IMPORT/ARTIFACT_DOWNLOAD/IMAGE_PULL
│   ├── PipelineExecution.java                ← 수정: pipelineDefinitionId 추가
│   └── PipelineStep.java                     ← 수정: jobId 추가
├── engine/                                    (신규 4, 수정 3)
│   ├── DagValidator.java                     ← 신규: Kahn's algo, 순환 탐지, 연결성 검증
│   ├── DagExecutionState.java                ← 신규: record, findReadyJobIds(), 역방향 위상 순서
│   ├── DagExecutionCoordinator.java          ← 신규: ReentrantLock, 스레드 풀 디스패치
│   ├── JobExecutorRegistry.java              ← 신규: JobType → Executor 매핑
│   ├── PipelineEngine.java                   ← 수정: DAG 라우팅 분기, resumeAfterWebhook DAG 경로
│   ├── SagaCompensator.java                  ← 기존 유지 (DAG 보상은 Coordinator에서 직접 처리)
│   └── WebhookTimeoutChecker.java            ← 수정: DAG 모드 감지 시 coordinator로 위임
├── api/                                       (신규 1)
│   └── PipelineDefinitionController.java     ← REST CRUD + execute
├── service/                                   (신규 1)
│   └── PipelineDefinitionService.java        ← 비즈니스 로직
├── dto/                                       (신규 4)
│   ├── PipelineDefinitionRequest.java        ← class (validation)
│   ├── PipelineDefinitionResponse.java       ← record (정적 팩토리)
│   ├── PipelineJobRequest.java               ← class (dependsOnIndices)
│   └── PipelineJobResponse.java              ← record
├── mapper/                                    (신규 2 Java + 2 XML, 수정 2 XML)
│   ├── PipelineDefinitionMapper.java + XML   ← CRUD + JOIN 조회
│   ├── PipelineJobMapper.java + XML          ← insertBatch, 의존성 CRUD
│   ├── PipelineExecutionMapper.xml           ← 수정: findByPipelineDefinitionId, insert에 컬럼 추가
│   └── PipelineStepMapper.xml                ← 수정: insertBatch에 job_id 추가
├── config/                                    (신규 1)
│   └── PipelineConfig.java                   ← jobExecutorPool Bean
└── event/                                     (수정 1)
    └── PipelineEventProducer.java            ← 수정 필요 (이슈 발견)

db/migration/
├── V17__create_pipeline_definition.sql       ← pipeline_definition 테이블
├── V18__create_pipeline_job.sql              ← pipeline_job + dependency 테이블
└── V19__add_job_id_to_pipeline_step.sql      ← 기존 테이블 확장

common-kafka/src/main/avro/pipeline/          (수정 3)
├── PipelineStepChangedEvent.avsc             ← jobId 옵셔널 추가
├── PipelineExecutionStartedEvent.avsc        ← pipelineDefinitionId 옵셔널 추가
└── JenkinsBuildCommand.avsc                  ← jobId 옵셔널 추가
```

## 요청 흐름: 파이프라인 정의 → 실행

```
1. 정의 생성
POST /api/pipelines { name, description, presetId }
  → PipelineDefinitionService.create()
    → definitionMapper.insert()
    → 응답: PipelineDefinitionResponse (jobs 빈 목록)

2. Job 구성
PUT /api/pipelines/{id}/jobs [ { jobName, jobType, executionOrder, dependsOnIndices } ]
  → PipelineDefinitionService.updateJobs()
    → 기존 Job/의존성 삭제
    → jobMapper.insertBatch() → ID 생성
    → dependsOnIndices → jobId 변환 → insertDependency()
    → dagValidator.validate() → 순환/단절 탐지
    → 응답: PipelineDefinitionResponse (jobs + dependencies)

3. 실행 트리거
POST /api/pipelines/{id}/execute
  → PipelineDefinitionService.execute()
    → DAG 검증
    → PipelineExecution 생성 (pipelineDefinitionId = id)
    → Job → Step 변환 (각 Job이 하나의 Step)
    → Outbox 이벤트 발행 (PIPELINE_EXECUTION_STARTED)
    → 응답: 202 Accepted + PipelineExecutionResponse

4. 비동기 실행
OutboxPoller → PipelineEventConsumer → PipelineEngine.execute()
  → pipelineDefinitionId != null → dagCoordinator.startExecution()
```

## DAG 실행 흐름

```
startExecution(execution)
  ① 상태 → RUNNING
  ② Job 목록 + 의존성 로드
  ③ DAG 검증 (DagValidator)
  ④ Step 목록 로드 → jobId↔stepOrder 매핑
  ⑤ DagExecutionState 초기화
  ⑥ dispatchReadyJobs(루트 Job)

dispatchReadyJobs(execution)
  ① ReentrantLock 획득 (reentrant 허용)
  ② findReadyJobIds() — 모든 의존성 완료된 Job
  ③ containerCap(3) 제한 적용
  ④ 각 Job을 jobExecutorPool에 submit

executeJob(execution, job, stepOrder)
  ① Step RUNNING 전환 + 이벤트 발행
  ② executor.execute(execution, step)
  ③ waitingForWebhook?
     → true: WAITING_WEBHOOK 전환, 스레드 반환
     → false: SUCCESS 전환, onJobCompleted(success=true)
  ④ 예외 시: FAILED 전환, onJobCompleted(success=false)

onJobCompleted(executionId, stepOrder, jobId, success)
  ① Lock 획득
  ② runningJobIds에서 제거, completed/failed에 추가
  ③ isAllDone? → finalizeExecution
  ④ hasFailure? → running 비면 skipPending + finalize
  ⑤ 새 ready Job → dispatchReadyJobs

finalizeExecution(executionId, state)
  ① hasFailure → SAGA 보상 (역방향 위상 순서) → FAILED
  ② !hasFailure → SUCCESS
  ③ executionStates/Locks 정리
```

## E2E 시나리오: 7 Job x 3 Round

```
Round 1 (병렬): Job1-BUILD(git-A), Job2-BUILD(git-B)  → Agent 2개
Round 2 (순차): Job3-DOWNLOAD → Job4-PULL → Job5-DEPLOY → Agent 1개씩
Round 3 (병렬): Job6-DEPLOY(prod-A), Job7-DEPLOY(prod-B) → Agent 2개

DAG:
  Job1 ──┐
         ├── Job3 → Job4 → Job5 ──┬── Job6
  Job2 ──┘                        └── Job7
```

| Round | 실행 Job | 조건 | containerCap |
|-------|---------|------|-------------|
| 1 | Job1, Job2 | 루트 (dependsOn 없음) | 2/3 |
| 2a | Job3 | Job1, Job2 완료 | 1/3 |
| 2b | Job4 | Job3 완료 | 1/3 |
| 2c | Job5 | Job4 완료 | 1/3 |
| 3 | Job6, Job7 | Job5 완료 | 2/3 |

## 설계 결정 요약

| 결정 | 선택 | 이유 |
|------|------|------|
| Job vs Step | 별도 테이블 | 정의(Job)와 실행(Step)의 생명주기가 다름 |
| DAG 표현 | join 테이블 + 도메인 adjacency list | SQL 정합성 + 런타임 편의성 |
| 하위 호환 | pipelineDefinitionId null 체크 | 기존 코드 0 변경 |
| 동시성 | 실행당 ReentrantLock | 웹훅 콜백 레이스 방지, 실행 간 독립 |
| 스레드 풀 | 고정 3 (containerCap) | Jenkins Agent 리소스 한도 |

## 퀴즈

**Q1**: Job과 Step을 같은 테이블로 합치지 않은 이유는 무엇인가?

> Job은 "설계도"이고 Step은 "실행 기록"이다. 같은 Job 정의로 10번 실행하면 Job은 1개지만 Step은 10개가 된다. 생명주기가 다르므로 테이블도 분리해야 한다.

**Q2**: `pipelineDefinitionId != null` 체크 대신 별도 `executionMode` 필드를 두지 않은 이유는?

> 새 필드를 추가하면 기존 INSERT 쿼리, DTO, Avro 스키마를 모두 수정해야 한다. nullable FK는 이미 추가되었으므로 이를 분기 조건으로 재활용하면 변경 범위를 최소화할 수 있다. YAGNI 원칙.
