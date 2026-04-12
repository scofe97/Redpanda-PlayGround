# Receive Job
---
> operator가 발행한 `EXECUTOR_CMD_JOB_DISPATCH`를 받아 `execution_job` 테이블에 실행 대기 건을 생성한다. 이 유스케이스의 책임은 "받아 적재하는 것"까지이며, 실제 디스패치 판단은 별도 유스케이스가 담당한다.

[HTML 시각화 보기](01-receive-job.html)

## 흐름도

```mermaid
graph TD
    A([EXECUTOR_CMD_JOB_DISPATCH]) --> B[JobDispatchConsumer]
    B --> C[ReceiveJobService.receive]
    C --> D{jobExcnId\nalready exists?}
    D -->|YES| E[중복 요청 무시]
    D -->|NO| F[ExecutionJob.create]
    F --> G[(execution_job\nstatus = PENDING)]
```

## 진입점

- Kafka Consumer: `JobDispatchConsumer`
- Use case: `ReceiveJobUseCase`
- Application service: `ReceiveJobService`

## 입력

메시지는 Avro `ExecutorJobDispatchCommand`로 들어온다. consumer에서 `priorityDt`를 문자열 timestamp에서 `LocalDateTime`으로 변환한다.

```avro
// ExecutorJobDispatchCommand.avsc
{
  "name": "ExecutorJobDispatchCommand",
  "namespace": "com.study.playground.avro.executor",
  "fields": [
    {"name": "jobExcnId",       "type": "string"},
    {"name": "pipelineExcnId",  "type": ["null", "string"], "default": null},
    {"name": "jobId",           "type": "string"},
    {"name": "priorityDt",      "type": "string", "doc": "ISO 8601"},
    {"name": "rgtrId",          "type": ["null", "string"], "default": null},
    {"name": "timestamp",       "type": "string", "doc": "ISO 8601"}
  ]
}
```

## 처리 흐름

### consumer 진입

```java
// JobDispatchConsumer.java
@RetryableTopic(
        attempts = "4"
        , backoff = @Backoff(delay = 1000, multiplier = 2, maxDelay = 10000)
        , topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE
        , kafkaTemplate = "avroRetryKafkaTemplate"
)
@KafkaListener(
        topics = Topics.EXECUTOR_CMD_JOB_DISPATCH
        , groupId = "${spring.kafka.consumer.group-id:executor-group}"
        , concurrency = "2"
        , containerFactory = "avroListenerFactory"
)
public void onJobDispatch(@Payload ExecutorJobDispatchCommand cmd) {
    try {
        receiveJobUseCase.receive(
                cmd.getJobExcnId()
                , cmd.getPipelineExcnId()
                , cmd.getJobId()
                , LocalDateTime.ofInstant(Instant.parse(cmd.getPriorityDt()), ZoneId.systemDefault())
                , cmd.getRgtrId()
        );
    } catch (Exception e) {
        log.error("[JobDispatch] Failed: jobExcnId={}, error={}"
                , cmd.getJobExcnId(), e.getMessage(), e);
        throw new RuntimeException(e);
    }
}

@DltHandler
public void handleDlt(ExecutorJobDispatchCommand cmd) {
    log.error("[JobDispatch-DLT] Exhausted retries: jobExcnId={}", cmd.getJobExcnId());
}
```

`@RetryableTopic`이 consumer 레벨 예외를 최대 4회 재시도한다. 지수 백오프(1s → 2s → 4s, 최대 10s)를 적용한다. `onJobDispatch`에서 예외가 발생하면 `RuntimeException`으로 감싸 Spring Retry에 전파하고, 최종 소진 시 `@DltHandler`가 에러 로그를 남긴다.

**priorityDt 변환**: Avro 스키마에서 `priorityDt`는 ISO 8601 문자열(`"2026-04-09T10:00:00Z"`)로 전달된다. Avro는 `LocalDateTime` 같은 Java 시간 타입을 직접 지원하지 않기 때문에 문자열로 직렬화하는 것이 관례다. consumer에서 `Instant.parse()` → `LocalDateTime.ofInstant()`로 변환하는 이유는, executor 내부 도메인과 DB(`priority_dt` 컬럼)가 `LocalDateTime`을 사용하기 때문이다. 시간대는 `ZoneId.systemDefault()`를 사용하므로 서버 JVM 기준으로 변환된다.

### use case 전체 코드

```java
// ReceiveJobService.java
@Transactional
public void receive(
        String jobExcnId
        , String pipelineExcnId
        , String jobId
        , LocalDateTime priorityDt
        , String rgtrId
) {
    if (jobPort.existsById(jobExcnId)) {
        log.debug("[Receive] Duplicate job ignored: jobExcnId={}", jobExcnId);
        return;
    }

    ExecutionJob job = ExecutionJob.create(
            jobExcnId, pipelineExcnId, jobId
            , DEFAULT_PRIORITY, priorityDt, rgtrId
    );

    try {
        jobPort.save(job);
        log.info("[Receive] Job received: jobExcnId={}, jobId={}, priority={}, priorityDt={}"
                , jobExcnId, jobId, DEFAULT_PRIORITY, priorityDt);
    } catch (DataIntegrityViolationException e) {
        log.debug("[Receive] Duplicate job ignored after concurrent insert: jobExcnId={}", jobExcnId);
    }
}
```

### 코드 설명

**멱등성 이중 방어**: `existsById`로 1차 중복 체크를 수행한다. at-least-once 소비 환경에서 같은 command가 다시 올 수 있으므로 이미 존재하면 즉시 종료한다. `save` 시점에는 다른 스레드가 먼저 insert했을 가능성이 있으므로 `DataIntegrityViolationException`을 잡아 2차 방어한다.

**도메인 객체 기본값**: `ExecutionJob.create()`는 `status = PENDING`, `retryCnt = 0`, `logFileYn = N`으로 고정한다. 수신 시점에는 Jenkins 슬롯 확인이나 Job 정의 조회를 하지 않고 "실행 대기 레코드 생성"만 보장한다. 이 분리 덕분에 메시지 수신 실패와 디스패치 실패를 따로 다룰 수 있다.

**실패 처리**: consumer 레벨 예외는 `@RetryableTopic`으로 재시도된다. 최종 소진 시 DLT로 빠지고 에러 로그를 남긴다. DB 저장 중 중복은 실패가 아니라 정상 무시로 취급한다.

## 테이블 스키마

이 유스케이스에서 적재하는 `execution_job` 테이블의 주요 컬럼은 다음과 같다.

```mermaid
erDiagram
    execution_job {
        varchar(50) job_excn_id PK "Job 실행 건 식별자"
        varchar(50) pipeline_excn_id "파이프라인 실행 건 (nullable)"
        varchar(50) job_id "Job 정의 식별자"
        int build_no "Jenkins 빌드 번호 (이후 단계에서 채워짐)"
        varchar(16) excn_stts "실행 상태 (PENDING)"
        int priority "우선순위 (기본 1)"
        timestamp priority_dt "우선순위 기준 시각"
        int retry_cnt "재시도 횟수 (기본 0)"
        timestamp bgng_dt "시작 시각 (이후 단계에서 채워짐)"
        timestamp end_dt "종료 시각 (이후 단계에서 채워짐)"
        char(1) log_file_yn "로그 파일 적재 여부 (N)"
        timestamp reg_dt "등록 시각"
        varchar(10) rgtr_id "등록자 ID"
        timestamp mdfcn_dt "수정 시각"
        varchar(10) mdfr_id "수정자 ID"
        bigint version "낙관적 잠금 버전"
    }
```

인덱스는 디스패치 조회를 위한 `idx_ej_stts_priority(excn_stts, priority, priority_dt)`와 파이프라인 조회를 위한 `idx_ej_pipeline(pipeline_excn_id, excn_stts)`이 있다.

## 핵심 로직

### 1. 이중 멱등성 보장

이 유스케이스는 같은 `jobExcnId`가 다시 와도 새 Job을 만들지 않는다.

- 1차 방어: `existsById`
- 2차 방어: 저장 중 unique 충돌을 잡아 무시

at-least-once 메시지 소비 환경을 전제로 작성돼 있다.

### 2. 상태는 항상 PENDING에서 시작

수신 시점에는 Jenkins 슬롯 확인이나 Job 정의 조회를 하지 않는다. 우선 "실행 대기 레코드 생성"만 보장하고, 뒤 단계가 이를 소비한다. 적재가 끝나면 다음 주기의 `DispatchScheduler`가 `tryDispatch()`를 호출하면서 실행 후보 선정이 이어진다.

### 3. 실패 처리

consumer 레벨 예외는 `@RetryableTopic`으로 재시도된다. 최종 소진 시 DLT로 빠지고 에러 로그를 남긴다. DB 저장 중 중복은 실패가 아니라 정상 무시로 취급한다.

## 상태 변화

- 입력: 없음 (신규 생성)
- 출력: `PENDING`

## 관련 클래스

- `execution/infrastructure/messaging/JobDispatchConsumer`
- `execution/application/ReceiveJobService`
- `execution/domain/model/ExecutionJob`
- `execution/domain/port/out/ExecutionJobPort`
