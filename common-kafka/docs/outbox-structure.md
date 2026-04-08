# Outbox 패키지 구조
---
> executor 패턴(Scheduler → Application → Domain + Infrastructure)을 적용한 Outbox 리팩토링 구조 설명

## 리팩토링 동기

기존 `OutboxPoller`는 277줄 단일 클래스에 스케줄링, Kafka 발행, CloudEvents 헤더, OTel 트레이싱, 재시도/DLQ 처리, 클린업까지 모든 책임을 담고 있었다. OTel 코드를 제거하려면 발행 로직 전체를 건드려야 하고, 재시도 정책을 변경하려면 폴링 루프 안의 private 메서드를 수정해야 했다.

executor 모듈이 사용하는 **Scheduler → Application Service → Domain Service + Infrastructure** 분리 패턴을 적용하여 각 책임을 독립 클래스로 분리했다. OTel 실험적 코드의 격리, 재시도 정책의 독립적 테스트, 스케줄링과 비즈니스 로직의 분리가 가능해졌다.

## 패키지 구조

```
outbox/
├── OutboxEvent.java                          ← JPA Entity (변경 없음)
├── OutboxEventHandler.java                   ← 커스텀 핸들러 인터페이스 (변경 없음)
├── OutboxEventRepository.java                ← JPA Repository (변경 없음)
├── OutboxMetrics.java                        ← Micrometer 메트릭 (변경 없음)
├── OutboxProperties.java                     ← 설정 프로퍼티 (변경 없음)
├── OutboxStatus.java                         ← 상태 Enum (변경 없음)
├── EventPublisher.java                       ← 비즈니스 진입점 (변경 없음)
├── OutboxAutoConfiguration.java              ← 빈 등록 (RetryPolicy 추가)
│
├── domain/
│   └── OutboxRetryPolicy.java                ← 순수 도메인: 재시도 판정 + 백오프 계산
│
├── application/
│   ├── OutboxPollService.java                ← 오케스트레이션: poll → publish → mark
│   └── OutboxCleanupService.java             ← SENT 정리 오케스트레이션
│
└── infrastructure/
    ├── scheduler/
    │   ├── OutboxPollScheduler.java           ← @Scheduled → OutboxPollService
    │   └── OutboxCleanupScheduler.java        ← @Scheduled → OutboxCleanupService
    └── publishing/
        ├── OutboxKafkaPublisher.java          ← Kafka 발행 + CloudEvents + [OTel]
        └── OutboxDlqPublisher.java            ← DLQ best-effort 발행
```

## 계층별 책임

| 계층 | 클래스 | 책임 | Spring 의존 |
|------|--------|------|-------------|
| **domain** | `OutboxRetryPolicy` | 재시도 초과 판정, 지수 백오프 다음 재시도 시각 계산. 순수 Java로 작성되어 단위 테스트가 용이하다. | 없음 (POJO) |
| **application** | `OutboxPollService` | 폴링 전체 흐름 오케스트레이션: TX 안에서 PENDING 조회 → TX 밖에서 이벤트별 발행 → 성공/스킵/실패 마킹. executor의 `DispatchEvaluatorService`와 동일한 역할이다. | `@Service` |
| **application** | `OutboxCleanupService` | 보존 기간 초과 SENT 레코드 삭제. 단순 오케스트레이션이므로 별도 도메인 서비스 없이 직접 Repository를 호출한다. | `@Service` |
| **infrastructure** | `OutboxPollScheduler` | 500ms 주기로 `OutboxPollService.poll()` 호출. 스케줄링 주기만 담당한다. executor의 `DispatchScheduler`와 동일한 패턴이다. | `@Component`, `@Scheduled` |
| **infrastructure** | `OutboxCleanupScheduler` | 매일 03시 `OutboxCleanupService.cleanup()` 호출. | `@Component`, `@Scheduled` |
| **infrastructure** | `OutboxKafkaPublisher` | Kafka 전송 + CloudEvents 헤더 + OTel trace context 복원. OTel 코드가 이 클래스 안에 격리되어 있어 제거가 쉽다. | `@Component` |
| **infrastructure** | `OutboxDlqPublisher` | DEAD 이벤트를 `playground.dlq` 토픽으로 best-effort 전송. 디버깅용 메타데이터 헤더를 포함한다. | `@Component` |
| **config** | `OutboxAutoConfiguration` | `OutboxMetrics`와 `OutboxRetryPolicy` 빈 등록. RetryPolicy는 Spring 어노테이션이 없으므로 여기서 `@Bean`으로 등록한다. | `@Configuration` |

## 의존 흐름

의존 방향은 executor 모듈과 동일하게 **인프라 → 애플리케이션 → 도메인**이다. 인프라 계층(Scheduler, Publisher)은 애플리케이션 서비스에 의존하고, 애플리케이션 서비스는 도메인 서비스와 인프라 퍼블리셔에 의존한다. 도메인 서비스는 어떤 계층에도 의존하지 않는다.

```mermaid
graph TD
    subgraph infrastructure
        PS[OutboxPollScheduler<br>@Scheduled 500ms]
        CS[OutboxCleanupScheduler<br>@Scheduled cron]
        KP[OutboxKafkaPublisher<br>Kafka + CloudEvents]
        DLQ[OutboxDlqPublisher<br>DLQ best-effort]
    end

    subgraph application
        SVC[OutboxPollService<br>오케스트레이션]
        CL[OutboxCleanupService<br>SENT 정리]
    end

    subgraph domain
        RP[OutboxRetryPolicy<br>순수 도메인]
    end

    PS --> SVC
    CS --> CL
    SVC --> KP
    SVC --> DLQ
    SVC --> RP
    SVC --> REPO[OutboxEventRepository]
    SVC --> MET[OutboxMetrics]
    CL --> REPO
    KP --> KAFKA[KafkaTemplate]
    KP -.->|experimental| TRACE[TraceContextUtil]

    style PS fill:#e8f5e9,color:#333
    style CS fill:#e8f5e9,color:#333
    style KP fill:#e8f5e9,color:#333
    style DLQ fill:#e8f5e9,color:#333
    style SVC fill:#e3f2fd,color:#333
    style CL fill:#e3f2fd,color:#333
    style RP fill:#fff3e0,color:#333
    style TRACE stroke-dasharray: 5 5,color:#333
```

## 핵심 쿼리: findAndMarkProcessing

`OutboxEventRepository.findAndMarkProcessing()`은 Outbox 폴링의 핵심이다. CTE(Common Table Expression) 하나로 "조회 + 잠금 + 상태 변경"을 원자적으로 수행한다.

### SQL 전문

```sql
WITH target AS (
    SELECT oe.id
    FROM outbox_event oe
    WHERE oe.status = 'PENDING'
      AND (oe.next_retry_at IS NULL OR oe.next_retry_at <= NOW())
      AND NOT EXISTS (
          SELECT 1 FROM outbox_event oe2
          WHERE oe2.aggregate_id = oe.aggregate_id
            AND oe2.status = 'PROCESSING'
      )
    ORDER BY oe.created_at
    LIMIT :limit
    FOR UPDATE SKIP LOCKED
)
UPDATE outbox_event
SET status = 'PROCESSING'
WHERE id IN (SELECT id FROM target)
RETURNING *
```

### 절별 분석

**`WHERE oe.status = 'PENDING'`**

PENDING 상태인 이벤트만 대상으로 한다. PROCESSING, SENT, DEAD 상태 이벤트는 폴링 대상에서 제외된다.

**`AND (oe.next_retry_at IS NULL OR oe.next_retry_at <= NOW())`**

재시도 대기 중인 이벤트를 필터링한다. `next_retry_at`이 NULL이면 첫 번째 시도이므로 즉시 처리 대상이다. 값이 있으면 지수 백오프로 계산된 시각이 현재 시각을 지났을 때만 재시도한다. 이를 통해 실패한 이벤트가 백오프 간격을 무시하고 바로 재시도되는 것을 방지한다.

**`AND NOT EXISTS (SELECT 1 FROM outbox_event oe2 WHERE oe2.aggregate_id = oe.aggregate_id AND oe2.status = 'PROCESSING')`**

**인스턴스 간 순서 보장의 핵심이다.** 동일 `aggregate_id`에 PROCESSING 상태인 이벤트가 하나라도 있으면, 해당 aggregate의 PENDING 이벤트를 조회하지 않는다. 인스턴스 A가 aggregate "order-123"의 이벤트 #1을 PROCESSING으로 가져갔다면, 인스턴스 B는 같은 aggregate의 이벤트 #2를 가져갈 수 없다. 이벤트 #1이 SENT 또는 DEAD로 전환된 후에야 #2가 조회 대상이 된다.

**`ORDER BY oe.created_at`**

생성 순서대로 처리한다. 같은 aggregate 내에서 이벤트 순서가 보장되는 이유는 NOT EXISTS 가드와 이 정렬의 조합 때문이다.

**`LIMIT :limit`**

한 번에 가져오는 배치 크기를 제한한다. 기본값은 `outbox.batch-size=50`이다. 배치가 크면 처리 시간이 길어져 다른 인스턴스가 대기하게 되고, 너무 작으면 폴링 오버헤드가 증가한다.

**`FOR UPDATE SKIP LOCKED`**

**멀티 인스턴스 경합 방지의 핵심이다.** `FOR UPDATE`는 선택된 행에 배타적 행 잠금(row-level exclusive lock)을 건다. `SKIP LOCKED`는 이미 다른 트랜잭션이 잠근 행을 건너뛴다. 두 인스턴스가 동시에 폴링해도 서로 다른 행을 가져가므로 블로킹 없이 병렬 처리가 가능하다.

`SELECT ... FOR UPDATE`만 사용하면 두 번째 인스턴스가 첫 번째 인스턴스의 잠금 해제를 기다리며 블로킹된다. `SKIP LOCKED`를 추가하면 잠긴 행을 스킵하고 잠기지 않은 행만 가져가므로 대기 시간이 0이다.

**`UPDATE ... SET status = 'PROCESSING' WHERE id IN (SELECT id FROM target)`**

CTE에서 선택한 행의 상태를 PENDING → PROCESSING으로 즉시 변경한다. SELECT와 UPDATE가 하나의 CTE 안에서 원자적으로 실행되므로, "조회 후 업데이트 사이에 다른 인스턴스가 끼어드는" 경합 조건(race condition)이 원천 차단된다.

**`RETURNING *`**

UPDATE 결과를 즉시 반환한다. 별도의 SELECT 쿼리 없이 업데이트된 이벤트 목록을 한 번의 라운드트립으로 가져온다.

### 인덱스 활용

```sql
INDEX idx_outbox_pending (status, next_retry_at)
```

이 복합 인덱스가 WHERE 절의 `status = 'PENDING' AND next_retry_at <= NOW()` 조건을 커버한다. `status`가 선행 컬럼이므로 PENDING 상태의 행만 효율적으로 스캔한다.

## 멀티 인스턴스 동시성 제어

Outbox 폴러를 여러 인스턴스에서 실행해도 이벤트 중복 발행이나 순서 역전이 발생하지 않는다. 동시성 제어는 두 계층에서 이루어진다.

### 1계층: 인스턴스 간 (DB 레벨)

```
인스턴스 A                              인스턴스 B
────────────                          ────────────
findAndMarkProcessing()               findAndMarkProcessing()
  │                                      │
  ├─ SELECT FOR UPDATE SKIP LOCKED       ├─ SELECT FOR UPDATE SKIP LOCKED
  │  → 이벤트 #1, #2, #3 잠금             │  → #1,#2,#3은 SKIP → #4, #5, #6 잠금
  │                                      │
  ├─ UPDATE status='PROCESSING'          ├─ UPDATE status='PROCESSING'
  │  → #1, #2, #3 = PROCESSING           │  → #4, #5, #6 = PROCESSING
  │                                      │
  └─ TX COMMIT                           └─ TX COMMIT
      │                                      │
  Kafka 발행 #1, #2, #3              Kafka 발행 #4, #5, #6
```

`FOR UPDATE SKIP LOCKED` 덕분에 두 인스턴스는 서로 다른 이벤트를 가져가며, 블로킹 없이 병렬 처리된다. 동일 이벤트가 두 인스턴스에 중복 할당되는 것은 불가능하다.

### 2계층: 배치 내 (애플리케이션 레벨)

같은 배치에 동일 aggregate의 이벤트가 여러 개 포함될 수 있다. 예를 들어 aggregate "order-123"의 이벤트 #1(주문 생성)과 #2(결제 요청)가 한 배치에 들어올 수 있다. #1 발행이 실패하면 #2를 먼저 발행하면 안 된다.

```java
// OutboxPollService.poll()
Set<String> failedAggregates = new HashSet<>();

for (var event : events) {
    // 선행 이벤트가 실패한 aggregate의 후속 이벤트는 skip
    if (failedAggregates.contains(event.getAggregateId())) {
        skippedIds.add(event.getId());  // → PENDING으로 복구됨
        continue;
    }
    try {
        kafkaPublisher.publish(event);
        sentIds.add(event.getId());
    } catch (Exception e) {
        // 실패한 aggregate를 등록 → 같은 aggregate의 후속 이벤트도 skip
        failedAggregates.add(event.getAggregateId());
        handleFailure(event);  // 재시도 또는 DEAD
    }
}
```

`failedAggregates` Set이 aggregate 단위 stop-on-failure 역할을 한다. skip된 이벤트는 `revertToPending()`으로 PENDING 상태로 복구되어 다음 폴링 사이클에서 재처리된다.

### 인스턴스 간 + 배치 내 조합

두 계층이 결합되어 어떤 시나리오에서도 순서가 보장된다:

| 시나리오 | 보호 메커니즘 | 동작 |
|---------|-------------|------|
| 인스턴스 A와 B가 동시에 같은 이벤트를 가져가려 함 | `FOR UPDATE SKIP LOCKED` | B는 A가 잠근 행을 건너뛰고 다른 행을 가져감 |
| 인스턴스 A가 aggregate X의 #1을 처리 중, B가 X의 #2를 가져가려 함 | `NOT EXISTS (status='PROCESSING')` | X에 PROCESSING 이벤트가 있으므로 B의 쿼리에서 X의 #2가 제외됨 |
| 같은 배치에서 aggregate X의 #1이 실패, #2가 남아있음 | `failedAggregates` Set | #2는 skip → PENDING 복구 → 다음 사이클에서 #1 재시도 후 #2 처리 |
| 인스턴스 A가 PROCESSING 상태에서 crash | Spring의 `@Scheduled` fixedDelay | 다음 폴링에서 PROCESSING 상태 이벤트가 NOT EXISTS 가드에 걸림. 별도 타임아웃 메커니즘으로 오래된 PROCESSING을 PENDING으로 복구 필요 (현재 미구현, 수동 대응) |

## 트랜잭션 경계 설계

`OutboxPollService.poll()`은 의도적으로 세 개의 트랜잭션 경계를 분리한다.

```
TX-1 (조회):  findAndMarkProcessing()    ← DB 커넥션 점유 최소화
             ↓ TX COMMIT
비-TX (발행):  for each event → Kafka     ← Kafka 타임아웃이 DB 커넥션을 점유하지 않음
             ↓
TX-2 (성공):  batchMarkAsSent()          ← 성공 이벤트 일괄 SENT 마킹
TX-3 (스킵):  revertToPending()          ← 스킵 이벤트 PENDING 복구
TX-4..N:     handleFailure()             ← 실패 이벤트별 개별 TX
```

Kafka 발행을 TX 안에 넣지 않는 이유는 다음과 같다. Kafka 브로커 응답 대기(기본 5초 타임아웃)가 DB 트랜잭션 안에서 발생하면, 그 시간 동안 DB 커넥션이 점유된다. 배치 50개 이벤트 중 Kafka 브로커가 느려지면 최대 250초(50 x 5초) 동안 커넥션 하나가 묶인다. 커넥션 풀(HikariCP 기본 10개)이 빠르게 고갈되어 다른 비즈니스 트랜잭션까지 영향받는다.

TX 밖에서 발행하면 DB 커넥션은 조회 TX가 끝나는 즉시 반환된다. 발행 실패 시에는 재시도 메커니즘이 보상하므로 "한 번은 실패해도 결국 발행된다(at-least-once)" 보장이 유지된다.

## OTel 격리 전략

OpenTelemetry는 실험적 기능이므로 제거가 쉽도록 구조적으로 격리했다. OTel 관련 코드는 `OutboxKafkaPublisher` 한 곳에만 존재하며, `// --- [EXPERIMENTAL: OpenTelemetry] ---` 블록으로 명확히 마킹되어 있다.

### 현재 동작

`TraceContextUtil.isOtelAvailable()`이 classpath에서 OTel 클래스 존재 여부를 확인한다. OTel이 있으면 저장된 traceparent로 부모 스팬을 복원하고, 새 스팬 안에서 Kafka 메시지를 전송한다. OTel이 없으면 `kafkaTemplate.send()`를 직접 호출한다.

### OTel 제거 방법 (2단계)

1. `OutboxKafkaPublisher.java`에서 `[EXPERIMENTAL]` 블록 삭제:
   - `sendWithTraceContext()` 메서드 전체
   - `TraceContextUtil` import

2. `publish()` 메서드에서 직접 전송으로 교체:

```java
// 변경 전
sendWithTraceContext(record, event);

// 변경 후
kafkaTemplate.send(record).get(5, TimeUnit.SECONDS);
```

나머지 클래스(`OutboxPollService`, `OutboxDlqPublisher`, 스케줄러 등)는 `TraceContextUtil`에 의존하지 않으므로 변경이 필요 없다. `EventPublisher`의 `captureTraceParent()` 호출은 OTel 없이도 null을 반환하여 정상 동작한다.

## Before / After

| 관점 | Before (OutboxPoller) | After (분리 구조) |
|------|----------------------|-------------------|
| 클래스 수 | 1개 (277줄) | 7개 (각 30~120줄) |
| 책임 | 스케줄링 + 발행 + 재시도 + DLQ + 클린업 + 트레이싱 | 클래스당 단일 책임 |
| OTel 격리 | private 메서드에 산재 | `OutboxKafkaPublisher` 한 곳, 마킹된 블록 |
| 재시도 정책 테스트 | OutboxPoller 전체를 로드해야 함 | `OutboxRetryPolicy` 단위 테스트 (Spring 불필요) |
| executor 패턴 일관성 | 독자적 구조 | Scheduler → Service → Domain 동일 패턴 |
| 외부 인터페이스 | - | 변경 없음 (`EventPublisher`, `OutboxEventHandler`, 설정 키 동일) |

## 확장 가이드

### 새로운 발행 채널 추가

Kafka 외에 다른 메시징 시스템(예: RabbitMQ)으로 발행해야 한다면, `infrastructure/publishing/` 하위에 새 Publisher를 추가하고 `OutboxPollService`에서 조건부로 라우팅한다. 기존 `OutboxEventHandler` 인터페이스를 활용할 수도 있다.

### 재시도 정책 변경

`OutboxRetryPolicy`는 순수 Java 클래스이므로 별도 프로파일이나 조건부 빈으로 교체할 수 있다. 선형 백오프나 지터가 필요하면 `OutboxRetryPolicy`를 상속하거나, `OutboxAutoConfiguration`에서 `@ConditionalOnMissingBean`으로 기본 빈을 등록하고 애플리케이션 레벨에서 커스텀 빈을 제공하면 된다.

### 관련 문서

- [Outbox 아키텍처 (개요, 상태 머신, 설정, 메트릭)](outbox-architecture.html)
- [Outbox 처리 흐름 (시각화)](outbox-flow.html)
