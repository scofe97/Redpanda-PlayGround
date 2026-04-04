# 파이프라인 동시성 제어 — 2026 최신 메타

기존 [이론 로드맵](./concurrency-theory-roadmap.md)의 개념들이 2025~2026년에 어떻게 진화했는지 정리한다. Playground가 Java 21을 사용하고 있으므로 일부는 바로 적용 가능하다.

## Virtual Threads + Semaphore — "Flood Gate" 패턴

Java 21에서 GA된 Virtual Thread는 OS 스레드 위에 경량 스레드를 올려, 블로킹 IO에서도 수만 개의 동시 태스크를 처리할 수 있다. Playground의 `CachedThreadPool`이 Jenkins 100개로 스케일할 때 수백 개 OS 스레드를 생성하는 문제를 근본적으로 해결한다.

```java
// 기존: OS 스레드 기반 — Jenkins 100개면 수백 스레드
ExecutorService pool = Executors.newCachedThreadPool();

// 변경: Virtual Thread — 수만 개 경량 스레드 가능
ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
```

Virtual Thread에서는 `Semaphore.acquire()`가 OS 스레드를 점유하지 않는다. 블로킹이 발생하면 Virtual Thread가 OS 스레드에서 unmount되고, 다른 Virtual Thread가 해당 OS 스레드를 사용한다. "Flood Gate" 패턴은 이 특성을 활용하여 Semaphore를 배압 도구로 사용하되 블로킹 비용을 제거한다.

```java
// VirtualThreadLimiter — 3가지 배압 정책
Semaphore gate = new Semaphore(maxActivePipelines);

gate.acquire();                          // BLOCK — Virtual Thread에서는 저비용
gate.tryAcquire(30, TimeUnit.SECONDS);   // TIMEOUT — 현재 hybrid 방식
if (!gate.tryAcquire()) throw ...;       // SHED — 즉시 거부
```

**Playground 영향:** Java 21을 사용하고 있으므로 `jobExecutorPool`을 `newVirtualThreadPerTaskExecutor()`로 전환하면 즉시 효과를 볼 수 있다. Semaphore 단독 방식의 "블로킹 시 재시도 불가" 한계도 완화되어, hybrid 없이 Semaphore 단독으로도 충분할 수 있다.

**참조:** [Virtual Thread vs Reactive Backpressure](https://www.javacodegeeks.com/2025/08/virtual-thread-vs-reactive-backpressure-build-resilience-patterns-in-high-concurrency-apis.html), [Flood Gate 패턴](https://www.jvm-weekly.com/p/taming-the-virtual-thread-torrent)

## Structured Concurrency (JEP 507) + Scoped Values (JEP 506)

Java 25/26에서 확정된 두 기능이다. DAG 실행 모델과 직접적으로 관련된다.

**Structured Concurrency**는 부모 태스크가 자식 태스크의 라이프사이클을 관리한다. 자식이 실패하면 나머지 자식을 자동 취소하고, 부모가 취소되면 자식도 취소된다.

```java
// DAG의 독립 체인을 병렬 실행 — 하나 실패 시 나머지 자동 취소
try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
    var chain1 = scope.fork(() -> executeChain(jenkins1Jobs));
    var chain2 = scope.fork(() -> executeChain(jenkins2Jobs));
    scope.join().throwIfFailed();  // chain1 실패 → chain2 자동 취소
}
```

현재 Playground의 `DagExecutionCoordinator`가 수동으로 관리하는 "실패 시 나머지 Job 취소 → SAGA 보상" 흐름을 선언적으로 표현할 수 있다.

**Scoped Values**는 `ThreadLocal`의 대안으로, 불변 컨텍스트 데이터를 스레드 간에 안전하게 공유한다. `executionId`나 `jenkinsToolInfo` 같은 실행 컨텍스트를 전파하는 데 적합하다.

**Playground 영향:** Java 21에서는 Preview이므로 당장 적용은 어렵지만, Java 25/26 업그레이드 시 DAG 실행 코드를 크게 단순화할 수 있다.

**참조:** [JEP 525: Structured Concurrency](https://openjdk.org/jeps/525), [JEP 506: Scoped Values](https://openjdk.org/jeps/506)

## Kafka Share Groups (KIP-932) — 파티션 기반 배압의 한계 해결

Kafka 4.1에서 Preview, Confluent Cloud에서 Kafka 4.2로 GA, Kafka 4.4(2026)에서 DLQ 지원이 예정되어 있다.

기존 Consumer Group은 파티션:Consumer = 1:1 매핑이 강제되었다. Share Groups는 이 제약을 제거하여 전체 토픽을 하나의 큐처럼 소비한다.

```
기존 Consumer Group:
  partition-0 → consumer-0 (고정 매핑)
  partition-1 → consumer-1 (고정 매핑)
  → consumer가 파티션보다 많으면 idle, 파티션 편향 발생

Share Group:
  모든 파티션 → 모든 consumer가 경쟁적으로 소비
  → 파티션 편향 문제 원천 해결
  → at-least-once 시맨틱스 (레코드 단위 acknowledge)
```

**Playground 영향:** `backpressure-partition.md`에서 "key 해싱 편향"으로 파티션 방식을 부적합하다고 판단했는데, Share Groups는 이 문제를 근본적으로 해결한다. 다만 아직 Preview이므로 프로덕션 PoC에는 시기상조이다. Kafka 4.4(2026 후반)의 DLQ 지원이 나오면 `@RetryableTopic` + Share Group 조합으로 배압을 걸 수 있게 된다.

**참조:** [KIP-932: Queues for Kafka](https://cwiki.apache.org/confluence/display/KAFKA/KIP-932:+Queues+for+Kafka), [Spring Kafka Share Consumer](https://spring.io/blog/2025/10/14/introducing-spring-kafka-share-consumer/)

## Valkey — Redis 대안 (분산 Semaphore 인프라)

2024년 Redis 라이선스가 SSPL로 변경된 이후, Redis 7.2.4 포크인 Valkey가 주요 대안으로 부상했다. AWS, Google Cloud, Oracle 등이 Valkey를 지원한다.

분산 Semaphore 관점에서 중요한 점은 **Redisson 라이브러리가 Redis와 Valkey 모두 지원**한다는 것이다. `RSemaphore`, `RPermitExpirableSemaphore` 등 동일 API를 코드 변경 없이 사용할 수 있다. Valkey는 멀티스레드 I/O 최적화로 고동시성 시나리오에서 Redis보다 나은 성능을 보인다.

**Playground 영향:** 멀티 인스턴스로 스케일아웃할 때 분산 Semaphore가 필요하다면, 라이선스 부담이 없는 Valkey + Redisson 조합이 합리적인 선택이다.

**참조:** [Valkey vs Redis 2026](https://betterstack.com/community/comparisons/redis-vs-valkey/), [Redisson GitHub](https://github.com/redisson/redisson)

## 학습 순서에 추가할 토픽

| 토픽 | 레이어 | 추천 Phase | Playground 적용 시점 |
|------|--------|-----------|---------------------|
| Virtual Threads | L2 (동시성) | Phase 2 | 즉시 (Java 21) |
| Structured Concurrency | L2 (동시성) | Phase 4 | Java 25/26 업그레이드 시 |
| Scoped Values | L2 (동시성) | Phase 4 | Java 25/26 업그레이드 시 |
| Share Groups (KIP-932) | L3 (분산) | Phase 4 | Kafka 4.4 GA 이후 (2026 후반) |
| Valkey | L3 (분산) | Phase 3 | 멀티 인스턴스 전환 시 |

## 참조 문서

- [concurrency-theory-roadmap.md](./01-concurrency-theory-roadmap.md) — 이론 로드맵 (기초~고급 16개 토픽)
- [multi-jenkins-architecture.md](./02-multi-jenkins-architecture.md) — 전체 아키텍처
- [backpressure-semaphore.md](./03-backpressure-semaphore.md) — Semaphore 방식
- [backpressure-hybrid.md](./06-backpressure-hybrid.md) — 하이브리드 방식 + 멀티 인스턴스 확장
