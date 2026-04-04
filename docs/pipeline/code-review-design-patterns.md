# 파이프라인 코드리뷰 — 디자인 패턴 적용 기록

이 문서는 이번 세션에서 파이프라인 모듈에 적용한 구조 개선과 디자인 패턴을 코드리뷰 관점에서 정리한 것이다. 각 패턴을 왜 도입했는지, 어떻게 구현했는지, 무엇이 개선됐는지를 설명한다.

---

## 1. 경량 상태머신

### 왜 도입했는가

상태를 String으로 관리하면 두 가지 문제가 생긴다. 첫째, 오타가 컴파일 타임에 잡히지 않는다. `"RUNING"`처럼 잘못된 값이 런타임까지 내려온다. 둘째, 허용되지 않는 전이를 막을 방법이 없다. `FAILED → SUCCESS`처럼 의미적으로 불가능한 전이도 코드 어디서든 세팅할 수 있다. Playground는 SAGA 보상 트랜잭션과 DAG 스케줄러가 상태를 공유하므로, 잘못된 전이 하나가 전체 실행 흐름을 오염시킬 수 있다.

### 구현

세 개의 enum에 동일한 패턴을 적용했다.

**`JobExecutionStatus`** (`pipeline/domain/JobExecutionStatus.java`)

Job 단위 실행 상태. 8개 값(PENDING, RUNNING, SUCCESS, FAILED, SKIPPED, COMPENSATED, WAITING_WEBHOOK, WAITING_EXECUTOR)과 허용 전이 맵을 함께 정의한다.

```java
private static final Map<JobExecutionStatus, Set<JobExecutionStatus>> ALLOWED_TRANSITIONS = Map.ofEntries(
        Map.entry(PENDING, Set.of(RUNNING, WAITING_EXECUTOR, SKIPPED, FAILED))
        , Map.entry(RUNNING, Set.of(SUCCESS, FAILED, WAITING_WEBHOOK, PENDING))  // PENDING은 재시도
        , Map.entry(WAITING_WEBHOOK, Set.of(SUCCESS, FAILED))
        , Map.entry(WAITING_EXECUTOR, Set.of(RUNNING, FAILED, PENDING))
        , Map.entry(SUCCESS, Set.of(COMPENSATED))
        , Map.entry(FAILED, Set.of())  // 최종 상태
        , Map.entry(SKIPPED, Set.of())  // 최종 상태
        , Map.entry(COMPENSATED, Set.of(FAILED))  // 보상 실패
);

public static void validateTransition(JobExecutionStatus from, JobExecutionStatus to) {
    if (from == to) return;  // 멱등성: 동일 상태 전이는 허용
    Set<JobExecutionStatus> allowed = ALLOWED_TRANSITIONS.get(from);
    if (allowed == null || !allowed.contains(to)) {
        throw new IllegalStateException(
                "Invalid job status transition: %s → %s".formatted(from, to));
    }
}
```

**`PipelineStatus`** (`pipeline/domain/PipelineStatus.java`)

파이프라인 전체 상태. 4개 값(PENDING, RUNNING, SUCCESS, FAILED). `JobExecutionStatus`와 별도 enum으로 분리한 이유는 전이 규칙과 의미가 다르기 때문이다. SKIPPED, COMPENSATED, WAITING_WEBHOOK은 파이프라인 수준에서는 존재하지 않는 개념이다.

**`DispatchStatus`** (`pipeline/domain/DispatchStatus.java`)

DB 분산 큐용 상태. `dispatch_status` 컬럼과 `FOR UPDATE SKIP LOCKED`를 함께 쓰는 멀티 인스턴스 디스패치에서 사용한다. PENDING → DISPATCHING → DONE의 단순한 선형 흐름이다.

### 검증 위치

`validateTransition`은 상태를 DB에 쓰기 직전에 호출한다. 전이 시도 자체를 막는 것이 아니라, 잘못된 전이가 DB에 반영되는 시점에 예외를 던져 트랜잭션이 롤백되게 한다.

### 테스트 (`domain/JobExecutionStatusTest.java`)

허용된 전이(예: PENDING → RUNNING)는 예외 없이 통과하고, 불가능한 전이(예: SUCCESS → RUNNING, FAILED → RUNNING)는 `IllegalStateException`을 던지는지 검증한다. 동일 상태 전이(`FAILED → FAILED`)는 멱등성을 위해 항상 허용되는 것도 파라미터화 테스트로 확인한다.

```java
@Test
@DisplayName("SUCCESS에서 RUNNING으로 전이는 불가능하다")
void success_to_running_불가() {
    assertThatThrownBy(() ->
            JobExecutionStatus.validateTransition(JobExecutionStatus.SUCCESS, JobExecutionStatus.RUNNING))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("SUCCESS → RUNNING");
}

@ParameterizedTest
@EnumSource(JobExecutionStatus.class)
@DisplayName("동일 상태 전이는 멱등성을 위해 항상 허용된다")
void 동일_상태_전이는_항상_허용(JobExecutionStatus status) {
    JobExecutionStatus.validateTransition(status, status);
}
```

---

## 2. 템플릿 메서드 패턴

### 왜 도입했는가

`JenkinsCloneAndBuildStep`과 `JenkinsDeployStep`은 Jenkins Job 트리거 → 파라미터 구성 → Kafka 커맨드 발행 → webhook 대기라는 동일한 실행 흐름을 공유하면서, Job 이름 결정 방식과 파라미터 파싱 로직만 달랐다. 두 클래스를 독립적으로 유지하면 공통 흐름의 버그를 한 곳에서만 고쳐도 다른 곳에 영향이 없어 불일치가 생긴다.

### 구현

**`AbstractJenkinsStep`** (`engine/step/AbstractJenkinsStep.java`)

공통 실행 흐름을 `execute()` 메서드에 고정하고, 서브클래스가 재정의할 Hook 메서드 4개를 제공한다.

```java
public abstract class AbstractJenkinsStep implements PipelineJobExecutor {

    /** 기본 Jenkins Job 이름. jobId가 null일 때 사용한다. */
    protected abstract String defaultJobName();

    /** 로그 메시지에 사용할 스텝 식별자 (예: "Build", "Deploy"). */
    protected abstract String stepLabel();

    /**
     * configJson이 없을 때 jobName에서 파라미터를 파싱하는 폴백 로직.
     * 기본 구현은 no-op — 필요한 서브클래스만 오버라이드한다.
     */
    protected void parseJobSpecificParams(PipelineJobExecution jobExecution, Map<String, String> params) {
        // no-op
    }

    /**
     * 추가 파라미터를 주입하는 확장점.
     * Deploy 스텝에서 이전 Job의 executionContext를 주입하는 데 사용한다.
     * 기본 구현은 no-op.
     */
    protected void enrichParams(PipelineExecution execution, PipelineJobExecution jobExecution
            , Map<String, String> params) {
        // no-op
    }

    @Override
    public void execute(PipelineExecution execution, PipelineJobExecution jobExecution) throws Exception {
        // 1) Jenkins 가용성 확인
        // 2) Job 이름 결정 (per-Job 폴더 또는 defaultJobName())
        // 3) 기본 파라미터 세팅 (EXECUTION_ID, JOB_ID)
        // 4) configJson 파싱 또는 parseJobSpecificParams() 폴백
        // 5) enrichParams() 호출
        // 6) 사용자 파라미터 병합
        // 7) Kafka 커맨드 발행
        // 8) webhook 대기 플래그 설정
    }
}
```

서브클래스는 필수 Hook(`defaultJobName`, `stepLabel`)만 반드시 구현하고, 선택 Hook(`parseJobSpecificParams`, `enrichParams`)은 필요할 때만 오버라이드한다.

**`JenkinsCloneAndBuildStep`** — `parseJobSpecificParams`를 오버라이드하여 jobName에서 Git URL, 브랜치를 파싱한다.

**`JenkinsDeployStep`** — `enrichParams`를 오버라이드하여 이전 Job의 `executionContext`(ARTIFACT_URL 등)를 파라미터로 주입한다.

### 효과

두 클래스가 독립적으로 가지고 있던 공통 실행 흐름 코드를 한 곳으로 합쳤다. 각 서브클래스는 차이점만 구현하므로 새 Step 타입을 추가할 때 `AbstractJenkinsStep`을 상속하고 Hook만 채우면 된다.

---

## 3. 전략 패턴

### 왜 도입했는가

실패 정책(FailurePolicy) 처리 로직이 `DagExecutionCoordinator`에 switch/case로 직접 내장되어 있었다. 새 정책을 추가하거나 기존 정책의 동작을 변경하려면 Coordinator를 수정해야 했다. Coordinator는 이미 DAG 실행 조율, 동시성 관리, 상태 복원 등 여러 책임을 가진 복잡한 클래스라서, 실패 정책 코드가 뒤섞이면 파악하기 어려워진다.

### 구현

**`FailureStrategy`** 인터페이스 (`dag/engine/strategy/FailureStrategy.java`)

```java
public interface FailureStrategy {
    void handle(UUID executionId, DagExecutionState state, FailureContext context);
}
```

**`FailureContext`** 콜백 인터페이스 (`dag/engine/strategy/FailureContext.java`)

전략 객체가 Coordinator 내부 메서드를 직접 참조하지 않도록 필요한 동작만 노출한다. `DagExecutionCoordinator`가 이 인터페이스를 구현한다.

```java
public interface FailureContext {
    void skipPendingJobs(UUID executionId, DagExecutionState state);
    void finalizeExecution(UUID executionId, DagExecutionState state);
    void dispatchByPriority();
    PipelineJobExecutionRepository jobExecutionRepository();
    PipelineEventProducer eventProducer();
    PipelineExecutionRepository executionRepository();
}
```

**구현체 3개**

| 클래스 | 정책 | 동작 |
|--------|------|------|
| `StopAllStrategy` | STOP_ALL | RUNNING 완료 대기 후 PENDING을 SKIP하고 종료 |
| `SkipDownstreamStrategy` | SKIP_DOWNSTREAM | 실패한 Job의 전이적 하위만 SKIP, 독립 브랜치는 계속 실행 |
| `FailFastStrategy` | FAIL_FAST | 즉시 모든 PENDING을 SKIP, RUNNING 완료 후 종료 |

**`DagExecutionCoordinator`에서의 등록과 사용**

```java
// 초기화: FailurePolicy → 전략 객체 맵
private final Map<FailurePolicy, FailureStrategy> failureStrategies = Map.of(
        FailurePolicy.STOP_ALL, new StopAllStrategy()
        , FailurePolicy.SKIP_DOWNSTREAM, new SkipDownstreamStrategy()
        , FailurePolicy.FAIL_FAST, new FailFastStrategy()
);

// 실패 발생 시 정책에 따라 전략 선택 후 위임
FailurePolicy policy = definition.getFailurePolicy();
failureStrategies.get(policy).handle(executionId, state, this);
```

### 효과

새 FailurePolicy를 추가할 때 `FailureStrategy` 구현체를 하나 만들고 맵에 등록하면 된다. Coordinator를 수정할 필요가 없다(OCP 준수). 각 정책의 동작이 독립된 클래스로 분리되어 테스트하기도 쉬워진다.

---

## 4. 데코레이터 패턴

### 왜 도입했는가

`JenkinsAdapter`는 Jenkins REST API를 호출하는 20여 개 메서드를 가지고 있다. 각 메서드마다 try-catch를 작성하면 재시도 로직이 분산되고, 재시도 정책을 바꾸려면 20개 메서드를 모두 수정해야 한다. 또한 `JenkinsAdapter` 자체의 코드가 비즈니스 로직과 재시도 인프라 코드가 뒤섞여 읽기 어려워진다.

### 구현

먼저 `JenkinsClient` 인터페이스를 추출하여 사용처(`AbstractJenkinsStep`, `DagExecutionCoordinator` 등)가 구체 구현이 아닌 인터페이스에만 의존하게 했다. 그 위에 `RetryableJenkinsClient` 데코레이터를 얹는다.

**`JenkinsClient`** 인터페이스 (`port/JenkinsClient.java`) — 20여 개 메서드 선언

**`RetryableJenkinsClient`** (`adapter/RetryableJenkinsClient.java`) — `JenkinsClient`를 구현하고 내부에 `delegate`로 다른 `JenkinsClient`를 받는다.

```java
public class RetryableJenkinsClient implements JenkinsClient {

    private static final int MAX_RETRIES = 3;
    private final JenkinsClient delegate;

    // 모든 메서드가 retrySupplier / retryRunnable을 통해 위임된다
    @Override
    public JenkinsBuildInfo getBuildInfo(String jobName, int buildNumber) {
        return retrySupplier(() -> delegate.getBuildInfo(jobName, buildNumber)
                , "getBuildInfo(%s, %d)".formatted(jobName, buildNumber));
    }

    private <T> T retrySupplier(Supplier<T> action, String operationName) {
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                return action.get();
            } catch (Exception e) {
                if (!isRetryable(e) || attempt == MAX_RETRIES) throw e;
                long delayMs = 1000L * (1L << attempt); // 1s, 2s, 4s (exponential backoff)
                sleep(delayMs);
            }
        }
        // 도달 불가
        throw new RuntimeException("Exhausted retries for " + operationName);
    }

    private boolean isRetryable(Throwable e) {
        // ConnectException, SocketTimeoutException, UnknownHostException,
        // ResourceAccessException은 일시적 장애로 판단하여 재시도
        // 4xx 비즈니스 에러는 재시도하지 않음
    }
}
```

**빈 등록** (`config/JenkinsClientConfig.java`)

```java
@Bean
public JenkinsClient jenkinsClient(JenkinsAdapter adapter) {
    return new RetryableJenkinsClient(adapter);
}
```

`JenkinsAdapter`(실제 HTTP 호출) → `RetryableJenkinsClient`(재시도) 순서로 감싸서 하나의 `JenkinsClient` 빈으로 등록한다. 사용처는 `@Autowired JenkinsClient`만 사용하면 되고, 재시도 여부를 알 필요가 없다.

### 재시도 대상 판단 기준

일시적 장애(`ConnectException`, `SocketTimeoutException`, `UnknownHostException`, Spring의 `ResourceAccessException`)만 재시도하고, 비즈니스 에러(4xx 응답)는 즉시 전파한다. 원인을 `getCause()` 체인으로 탐색하므로 Spring이 예외를 한 번 감싸더라도 올바르게 판단한다.

---

## 5. Spring AOP (`@RestoreTrace`)

### 왜 도입했는가

파이프라인 실행은 HTTP 요청 → Kafka 이벤트 → 스케줄러 스레드를 거치면서 OTel trace context가 끊긴다. 원래 요청의 traceParent를 DB에 저장해두고, 각 처리 지점에서 직접 복원 코드를 호출하는 방식은 OTel 복원 코드가 비즈니스 로직에 산재하는 문제가 있다.

### 구현

**`@RestoreTrace`** 어노테이션 (`aop/annotation/RestoreTrace.java`)

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RestoreTrace {
    String spanName() default "";  // 생략 시 "클래스명.메서드명" 자동 생성
}
```

**`TraceRestoreAspect`** (`aop/TraceRestoreAspect.java`)

```java
@Around("@annotation(restoreTrace)")
public Object restoreTraceContext(ProceedingJoinPoint joinPoint, RestoreTrace restoreTrace) throws Throwable {
    // 1) 첫 번째 UUID 파라미터를 executionId로 사용
    UUID executionId = findExecutionId(joinPoint.getArgs());

    // 2) DB에서 traceParent 조회
    var execution = executionRepository.findById(executionId).orElse(null);
    String traceParent = execution.getTraceParent();

    // 3) trace 복원 후 원래 메서드 실행
    TraceContextUtil.executeWithRestoredTrace(traceParent, spanName, attributes, () -> {
        resultHolder[0] = joinPoint.proceed();
    });
}
```

어노테이션을 붙이는 쪽은 `@RestoreTrace`만 선언하면 된다. OTel 복원 코드가 비즈니스 로직에서 사라진다.

### 제한 사항: self-invocation

Spring AOP는 프록시 기반이므로 같은 클래스 내부 메서드 호출에는 동작하지 않는다. `WebhookTimeoutChecker`의 내부 루프에서 호출하는 메서드는 AOP가 적용되지 않아 기존 방식(수동 복원 코드)을 유지했다. 외부에서 호출되는 `public` 메서드에만 `@RestoreTrace`를 붙여야 한다.

---

## 6. 이전 세션 변경사항 (함께 리뷰)

이번 세션의 패턴 적용과 함께 이전 세션에서 진행한 구조 개선도 동일 PR에 포함된다.

### DB 분산 큐

Semaphore 기반 인메모리 동시성 제어를 DB 기반으로 교체했다. `dispatch_status` 컬럼(`DispatchStatus` enum)과 `FOR UPDATE SKIP LOCKED`를 사용하여 멀티 인스턴스 환경에서도 하나의 인스턴스만 각 실행을 디스패치하도록 보장한다. Semaphore 제거로 인스턴스 재시작 시 상태 유실 문제가 해소됐다.

### 순차 모드 제거 + jobId 단일화

parallel/sequential 실행 모드를 분리하는 코드를 제거하고, 모든 실행을 DAG 기반 병렬 실행으로 통일했다. 순차 실행은 단일 체인 DAG로 표현 가능하므로 별도 모드가 불필요했다. 동시에 `jobId` 식별자를 통일하여 여러 곳에서 다른 이름으로 참조되던 혼란을 없앴다.

### MyBatis → JPA 전환

15개 MyBatis Mapper를 15개 `JpaRepository`로 교체했다. 단순 CRUD 쿼리는 JPA 메서드 이름 규칙으로 표현하고, 복잡한 쿼리(SKIP LOCKED 포함)는 `@Query`로 명시했다. Mapper XML 파일이 사라지고 Repository 인터페이스만으로 데이터 접근 계층이 완성된다.
