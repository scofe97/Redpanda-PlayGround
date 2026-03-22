# ADR-004: Jenkins 관리에 Transactional Outbox 패턴 적용

## 상태

Accepted

## 맥락

파이프라인 실행 시 두 가지 작업이 원자적으로 이루어져야 한다. DB에 실행 상태를 저장하는 것과 Jenkins API를 호출하여 Job을 트리거하는 것이다.

이 두 작업 사이에는 분산 트랜잭션 문제가 있다. DB 저장 성공 후 Jenkins API 호출이 실패하면, DB에는 "실행 요청됨"으로 기록되어 있지만 실제로 Jenkins Job이 생성되지 않는 불일치가 발생한다. 반대로 Jenkins API 호출 성공 후 DB 저장이 실패하면, Jenkins에는 Job이 실행 중이지만 시스템에는 추적 레코드가 없다.

Jenkins는 외부 시스템이므로 DB 트랜잭션에 포함할 수 없다. 직접 API 호출 방식에서는 Jenkins 장애 시 재시도 로직을 별도로 구현해야 하고, 중복 실행 방지(idempotency)도 보장하기 어렵다.

## 결정

Transactional Outbox 패턴을 적용한다. DB 트랜잭션 내에서 비즈니스 데이터와 함께 outbox 이벤트를 저장하고, 별도 핸들러가 outbox를 읽어 Jenkins API를 호출한다. Jenkins와의 상태 불일치는 `JenkinsReconciler`가 주기적으로 감지하고 해소한다.

구현 구조는 다음과 같다.

```
1. 서비스 메서드 (단일 DB 트랜잭션)
   └─ INSERT INTO pipeline_execution (status = PENDING)
   └─ INSERT INTO outbox_event (type = JENKINS_JOB_TRIGGER, payload = {...})

2. OutboxEventHandler (트랜잭션 외부, 비동기)
   └─ outbox_event 테이블 폴링
   └─ Jenkins API 호출
   └─ 성공 시 outbox_event 상태 → PROCESSED
   └─ 실패 시 retry_count 증가, max 초과 시 DEAD_LETTER

3. JenkinsReconciler (주기적 실행)
   └─ pipeline_execution 상태와 Jenkins Job 상태 비교
   └─ 불일치 감지 시 상태 동기화
```

## 근거

Transactional Outbox를 선택한 이유는 두 가지다.

첫째, DB 트랜잭션을 활용한 원자성이다. DB 저장과 outbox 이벤트 삽입이 같은 트랜잭션 안에 있으므로, 둘 다 성공하거나 둘 다 실패한다. Jenkins API 호출은 트랜잭션 이후에 발생하므로, 호출 실패가 DB 상태에 영향을 주지 않는다.

둘째, 재시도 안전성이다. OutboxEventHandler가 실패한 이벤트를 재시도할 때, Jenkins Job이 이미 생성된 경우 중복 생성이 일어나지 않도록 `JenkinsReconciler`가 멱등성을 보장한다.

## 대안

**직접 API 호출 후 실패 시 rollback**: 가장 단순한 접근이다. 하지만 분산 트랜잭션이 필요하고, Jenkins 장애 시 애플리케이션도 실패 상태가 된다. Jenkins 응답 지연이 애플리케이션 응답 지연으로 직결되는 문제도 있다.

**Kafka 이벤트 발행 (Outbox 없이)**: DB 저장 후 Kafka에 직접 이벤트를 발행하는 방법이다. DB 저장 성공 + Kafka 발행 실패 시 불일치가 생기므로, 결국 Outbox와 같은 문제를 다른 형태로 갖는다. Kafka 자체가 Outbox 역할을 하려면 DB와 Kafka의 두 쓰기를 원자화하는 트랜잭션 메시지 방식이 필요하여 복잡도가 더 높다.

## 영향

- `outbox_event` 테이블이 DB 스키마에 추가된다
- `OutboxEventHandler`가 주기적으로 outbox를 폴링하므로 약간의 실행 지연이 발생한다 (PoC 수준에서 허용)
- `JenkinsReconciler`가 Jenkins API를 주기적으로 호출하므로 Jenkins에 부하가 생긴다 (폴링 간격 조정으로 완화)
- Jenkins Job 생성과 파이프라인 실행 상태 추적이 분리되어 디버깅 시 두 컴포넌트를 함께 확인해야 한다
