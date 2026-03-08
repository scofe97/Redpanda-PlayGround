# 멱등성 패턴 (ProcessedEvent 중복 방지)

## 1. 개요

Kafka(Redpanda)는 at-least-once 전달을 기본으로 보장한다. 즉, 네트워크 장애나 Consumer 재시작이 발생하면 같은 메시지가 두 번 이상 도착할 수 있다. Consumer가 메시지를 처리한 직후, 오프셋을 커밋하기 직전에 죽으면 재시작 시 같은 메시지를 다시 읽는다. 이것은 버그가 아니라 설계된 동작이다.

문제는 Consumer 로직이 멱등하지 않을 때 발생한다. 주문 생성, 결제 승인, 외부 API 호출처럼 부작용이 있는 작업은 같은 메시지를 두 번 처리하면 데이터 불일치나 이중 청구가 생긴다. 따라서 Consumer 로직 자체를 멱등하게 만들거나, 중복 메시지를 탐지하여 건너뛰는 장치가 필요하다.

## 2. 이 프로젝트에서의 적용

### processed_event 테이블

중복 탐지의 핵심은 "이 메시지를 이미 처리했는가"를 저장하는 저장소다. 이 프로젝트는 `processed_event` 테이블을 사용한다.

```sql
CREATE TABLE processed_event (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    correlation_id VARCHAR(255) NOT NULL,
    event_type     VARCHAR(100) NOT NULL,
    processed_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uq_processed_event (correlation_id, event_type)
);
```

`(correlation_id, event_type)` 복합 유니크 키가 핵심이다. 같은 상관관계 ID라도 이벤트 타입이 다르면 별개의 처리로 간주한다. 예를 들어 `order-123` 상관관계 ID에 대해 `ORDER_CREATED`와 `ORDER_PAID`는 각각 독립적으로 기록된다.

### INSERT...WHERE NOT EXISTS (preemptive acquire)

중복 여부를 먼저 SELECT로 확인한 뒤 처리하는 방식은 TOCTOU(Time Of Check To Time Of Use) 경쟁 조건에 취약하다. 두 Consumer 인스턴스가 동시에 같은 메시지를 받으면 둘 다 SELECT에서 미처리로 판단하고 동시에 처리를 시작할 수 있다.

이 프로젝트는 preemptive acquire 패턴을 사용한다. 처리 전에 먼저 레코드를 삽입하려 시도하고, 유니크 제약 위반이 발생하면 중복으로 판단한다.

```java
// Spring Data JPA 네이티브 쿼리 예시
@Modifying
@Query(value = """
    INSERT INTO processed_event (correlation_id, event_type, processed_at)
    SELECT :correlationId, :eventType, NOW()
    WHERE NOT EXISTS (
        SELECT 1 FROM processed_event
        WHERE correlation_id = :correlationId AND event_type = :eventType
    )
    """, nativeQuery = true)
int insertIfAbsent(@Param("correlationId") String correlationId,
                   @Param("eventType") String eventType);
```

반환값이 0이면 이미 처리된 메시지다. `WHERE NOT EXISTS` 구문과 유니크 키를 함께 사용하면 SELECT+INSERT 사이의 경쟁 조건 없이 DB 수준에서 원자적으로 처리된다.

### 중복 수신 시 처리

삽입 시도 결과가 0이면(이미 처리됨) 비즈니스 로직을 실행하지 않고 skip 로그만 남긴 뒤 오프셋을 커밋한다. Consumer가 멈추지 않아야 하기 때문에 예외를 던지지 않는다.

```
[SKIP] 이미 처리된 이벤트 — correlationId=order-123, eventType=ORDER_CREATED
```

### webhook 멱등키

Jenkins 파이프라인 실행에서 발생하는 webhook 이벤트는 별도의 멱등키를 사용한다.

```
jenkins:{executionId}:{stepOrder}
```

`executionId`는 Jenkins 빌드 번호, `stepOrder`는 파이프라인 단계 순서다. 같은 빌드의 같은 단계에서 webhook이 재전송되어도 동일한 키로 탐지되어 무시된다.

## 3. 왜 이 방식인가

**DB 기반** (이 프로젝트의 선택): 별도 인프라 추가 없이 비즈니스 트랜잭션과 동일한 DB를 사용한다. `processed_event` 삽입과 비즈니스 데이터 변경을 하나의 트랜잭션에 묶을 수 있어 원자성이 자연스럽게 확보된다. 단점은 DB I/O가 추가되고, 대용량 처리에서 병목이 될 수 있다는 점이다.

**Redis 기반**: `SETNX`(SET if Not eXists) 명령으로 빠른 중복 탐지가 가능하다. 처리량이 높은 시스템에 적합하지만 Redis가 필수 인프라가 되고, Redis 장애 시 멱등성 보장이 무너진다. TTL 기반 자동 만료가 가능하다는 장점이 있다.

**Kafka 트랜잭션(exactly-once)**: Kafka 자체 트랜잭션 기능을 사용하면 Consumer 오프셋과 Producer 전송을 원자적으로 처리할 수 있다. 그러나 외부 시스템(DB, API) 호출을 포함하는 로직에는 적용되지 않으며, 설정이 복잡하고 성능 비용이 있다.

이 프로젝트는 외부 API 호출과 DB 쓰기가 혼재하기 때문에 Kafka 트랜잭션만으로는 부족하다. Redis 추가 의존성을 피하고 트랜잭션 일관성을 보장하는 DB 기반 방식이 적합하다.

## 4. 주의사항

**테이블 정리 전략**: `processed_event` 테이블은 시간이 지날수록 계속 커진다. 무한 증가를 막으려면 `processed_at` 기준으로 일정 기간이 지난 레코드를 주기적으로 삭제해야 한다. 보존 기간은 Kafka 메시지 보존 기간보다 길어야 한다. Kafka retention이 7일이라면 `processed_event`는 최소 14일 이상 보존해야 재처리 시나리오에서 중복을 탐지할 수 있다.

```sql
-- 배치 삭제 예시 (30일 이상 경과한 레코드)
DELETE FROM processed_event
WHERE processed_at < DATE_SUB(NOW(), INTERVAL 30 DAY)
LIMIT 1000;
```

한 번에 전체 삭제하면 DB 락이 길어질 수 있으므로 `LIMIT`으로 배치 처리한다.

**키 충돌 설계**: `correlation_id`는 전역적으로 고유해야 한다. UUID v4를 사용하면 충돌 확률이 사실상 없다. 순차 ID나 타임스탬프 기반 ID는 분산 환경에서 충돌 가능성이 있으므로 피한다. webhook의 `jenkins:{executionId}:{stepOrder}` 패턴처럼 도메인별로 prefix를 붙이면 `correlation_id` 네임스페이스 충돌을 방지할 수 있다.

**트랜잭션 경계**: `insertIfAbsent`와 비즈니스 로직은 반드시 같은 트랜잭션 안에 있어야 한다. 삽입 성공 후 비즈니스 로직이 실패하면 트랜잭션 롤백으로 삽입도 함께 취소되어, 다음 재시도 시 정상적으로 처리된다. 두 작업이 별도 트랜잭션으로 분리되면 삽입은 성공했지만 비즈니스 처리는 실패한 유령 레코드가 생겨 이후 재시도가 모두 중복으로 판단된다.
