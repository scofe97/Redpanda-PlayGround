# Outbox Ordering 사례 조사
---
> Transactional Outbox의 ordering, `SKIP LOCKED`, 멀티 인스턴스 확장, aggregate 단위 처리 전략과 관련된 외부 글을 조사하고, 현재 `common-kafka`의 설계 고민과 연결해 정리한 문서다.

## 조사 목적

현재 `common-kafka`의 Outbox는 `aggregate_id` 내부 순서를 강하게 보장하려고 한다. 특히 다음 질문에 대한 외부 사례를 찾는 것이 목적이다.

- `FOR UPDATE SKIP LOCKED`만으로 멀티 인스턴스 정합성이 충분한가
- 같은 `aggregate_id`를 한 poll에서 여러 건 처리하려면 무엇이 더 필요한가
- hot aggregate 병목이 생길 때 업계는 어떤 방식으로 확장하는가

이번 조사에서 본문을 직접 확인한 출처는 다음 여섯 개다.

- Chris Richardson, microservices.io
- gmhafiz
- Namastack DEV 글
- Namastack Features 문서
- Kamil Grzybek
- Banandre

## 공통 결론

조사한 사례들은 대체로 같은 결론을 가리킨다. `FOR UPDATE SKIP LOCKED`는 중복 선택 방지에는 유용하지만, 그것만으로는 `aggregate_id` 내부 순서를 항상 보장한다고 보지 않는다.

ordering이 중요한 경우 실전 전략은 크게 두 방향으로 갈린다.

- 가장 보수적으로는 `aggregate`의 head 1건만 선택한다.
- 처리량이 더 필요하면 `aggregate ownership`, `partition ownership`, `per-aggregate locking` 같은 상위 단위 제어를 둔다.

즉, 현재 `common-kafka`의 head-only 쿼리는 흔한 튜토리얼형 구현은 아니지만, 정합성 우선 전략으로는 충분히 일관된 선택이다.

## 사례별 정리

### microservices.io

출처:

- [Pattern: Transactional outbox](https://microservices.io/patterns/data/transactional-outbox)

확인 위치:

- `Context` 섹션, 브라우징 확인 라인 `L23-L29`
- `Forces` 섹션, 브라우징 확인 라인 `L33-L39`
- `Result context` 섹션, 브라우징 확인 라인 `L48-L61`

핵심 내용:

- 이 글은 Transactional Outbox의 가장 표준적인 정의를 제시한다.
- 특히 같은 aggregate를 여러 트랜잭션, 여러 서비스 인스턴스가 갱신해도 이벤트 순서는 보존되어야 한다고 명시한다.
- 또한 relay가 메시지를 중복 발행할 수 있으므로 consumer는 idempotent해야 한다고 정리한다.

현재 논의와 연결점:

- 이 글은 `aggregate` ordering이 패턴의 핵심 force라고 분명히 말한다.
- 다만 ordering을 SQL로 어떻게 구현할지는 설명하지 않는다.
- 따라서 `common-kafka`의 `head-only` 쿼리는 이 글의 요구사항을 더 구체적인 구현으로 내린 사례라고 볼 수 있다.

### gmhafiz

출처:

- [Transactional Outbox Pattern](https://www.gmhafiz.com/blog/transactional-outbox-pattern/)

확인 위치:

- `Step (2) Producer` 섹션, 브라우징 확인 라인 `L117-L138`
- `#### SKIP LOCKED` 하위 섹션, 브라우징 확인 라인 `L136-L155`

핵심 내용:

- 이 글은 `SKIP LOCKED`를 row-level claim 도구로 설명한다.
- 여러 producer가 같은 outbox row를 중복 선택하지 않도록 `SELECT ... FOR UPDATE SKIP LOCKED`를 사용한다.
- 핵심 표현은 "the next unclaimed rows"다. 즉 이 글의 관심사는 기본적으로 "아직 안 집힌 row"를 찾는 문제다.

현재 논의와 연결점:

- 이 글은 `SKIP LOCKED`의 용도를 정확히 설명하지만, 논의 단위가 row다.
- `aggregate_id` 내부 순서나 같은 aggregate의 후행 이벤트 차단 문제는 다루지 않는다.
- 따라서 `common-kafka`에서 `SKIP LOCKED`만으로는 부족하고 `NOT EXISTS prev(...)`가 추가로 필요한 이유를 설명할 때 비교 기준으로 쓰기 좋다.

### Namastack DEV 글

출처:

- [Outbox Pattern: The Hard Parts (and How Namastack Outbox Helps)](https://dev.to/namastack/outbox-pattern-the-hard-parts-and-how-namastack-outbox-helps-507m)

확인 위치:

- 글 서두의 hard parts 목록, 브라우징 확인 라인 `L44-L50`
- `How Namastack Outbox defines ordering`, 브라우징 확인 라인 `L71-L121`
- `Scaling: partitioning and rebalancing`, 브라우징 확인 라인 `L124-L133`
- `Namastack Outbox approach: hash-based partitioning`, 브라우징 확인 라인 `L134-L157`

핵심 내용:

- 이 글은 ordering semantics를 "usually per aggregate/key, not global"이라고 정의한다.
- ordering key는 도메인상 순서가 중요한 단위로 잡아야 하며, 너무 coarse한 key와 너무 fine한 key를 모두 경계한다.
- scale-out 시 단순 DB locking은 contention과 hot row를 유발할 수 있다고 본다.
- 대신 hash-based partitioning으로 same key를 same partition, one active instance로 라우팅해 ordering과 확장을 동시에 해결하려고 한다.

현재 논의와 연결점:

- 현재 `common-kafka`의 `aggregate_id`는 사실상 Namastack 글의 `key` 역할이다.
- 이 글은 "같은 key는 순차 처리, 다른 key는 병렬 처리"를 명시적으로 설명하므로, 우리가 정리한 "id별 직렬화가 아니라 aggregate별 직렬화"와 거의 일치한다.
- 또한 hot aggregate 병목을 SQL 튜닝이 아니라 partition ownership으로 푸는 방향을 보여준다.

### Namastack Features 문서

출처:

- [Features - Namastack Outbox for Spring Boot](https://outbox.namastack.io/features/)

확인 위치:

- `Hash-based Partitioning`, 브라우징 확인 라인 `L70-L76`
- `Event Ordering`, 브라우징 확인 라인 `L145-L170`
- `Reliability Guarantees`, 브라우징 확인 라인 `L446-L452`

핵심 내용:

- 이 문서는 초기 distributed locking 방식에서 hash-based partitioning으로 옮겨갔다고 설명한다.
- 이유는 strict event ordering per aggregate를 유지하면서 lock contention을 줄이기 위해서다.
- 같은 aggregate의 ordering, different aggregate의 병렬성, stop-on-first-failure 정책까지 제품 기능으로 드러낸다.

현재 논의와 연결점:

- 이 문서는 "aggregate ownership" 계열 접근이 제품화된 사례로 볼 수 있다.
- `common-kafka`가 지금처럼 head-only 전략을 유지하다가 나중에 hot aggregate 문제가 커질 경우, 다음 단계가 어떤 방향이어야 하는지를 잘 보여준다.
- 특히 "ordering per aggregate", "horizontal scalability", "automatic rebalancing"을 동시에 보장 목표로 둔 점이 중요하다.

### Kamil Grzybek

출처:

- [The Outbox Pattern](https://www.kamilgrzybek.com/blog/posts/the-outbox-pattern)

확인 위치:

- `Outbox pattern` 설명부
- at-least-once / idempotency 설명, 브라우징 확인 라인 `L102-L103`
- `ProcessOutboxJob`, 브라우징 확인 라인 `L253-L266`

핵심 내용:

- 이 글은 전통적인 polling job 기반 Outbox 구현을 설명한다.
- relay는 outbox를 주기적으로 조회하고, 처리 후 processed 표시를 남긴다.
- 메시지는 한 번 이상 전송될 수 있으므로 receiver는 idempotent해야 한다.
- 예시 job에는 `DisallowConcurrentExecution`이 붙어 있어서 단일 job instance 동시 실행을 막는다.

현재 논의와 연결점:

- 이 글은 멀티 인스턴스 경쟁을 lock-free SQL로 푸는 대신, 스케줄러 레벨 동시 실행 억제에 더 가깝다.
- `common-kafka`처럼 멀티 poller 경쟁과 aggregate ordering을 SQL 단계에서 세밀하게 풀지는 않는다.
- 다만 "at-least-once + idempotency"라는 전제는 현재 구조와 동일하다.

### Banandre

출처:

- [Beyond the Tutorials: What Really Happens with the Outbox Pattern](https://www.banandre.com/blog/2025-09/beyond-the-tutorials-what-really-happens-with-the-outbox-pattern)

확인 위치:

- `The Postgres Lock Contention Horror Show`, 브라우징 확인 라인 `L814-L819`
- `When Outbox Actually Works`, 브라우징 확인 라인 `L850-L852`

핵심 내용:

- 이 글은 `FOR UPDATE SKIP LOCKED` polling이 load가 커지면 relay contention, thundering herd, DB CPU 상승으로 이어질 수 있다고 비판한다.
- 즉 `SKIP LOCKED`는 correctness 도구일 뿐, scale-out에서 성능 비용이 거의 공짜는 아니라는 주장이다.
- 동시에 moderate throughput, tolerant consumers, simple topologies에서는 Outbox가 잘 작동한다고도 정리한다.

현재 논의와 연결점:

- 이 글은 지금 우리가 우려하는 "hot aggregate", "poller가 많아질수록 DB contention이 커질 수 있음"을 운영 관점에서 뒷받침한다.
- 따라서 현재 head-only 쿼리가 정합성 면에서 타당하더라도, 고부하 단계에서 partition ownership이나 별도 aggregate lock으로 넘어갈 필요가 생길 수 있다는 점을 시사한다.

## 현재 common-kafka와의 매핑

현재 `common-kafka`는 다음 선택을 하고 있다.

- `aggregate_id`를 ordering key로 사용한다.
- `findHeadPendingIdsForProcessing()`에서 same aggregate의 head 1건만 고른다.
- `FOR UPDATE SKIP LOCKED`로 row 중복 선택을 방지한다.
- `failedAggregates`로 배치 내 stop-on-first-failure를 한 번 더 보강한다.

이 선택은 외부 사례 중 어디에 가장 가까운가:

- `SKIP LOCKED` 자체는 gmhafiz 글과 유사하다.
- ordering requirement 해석은 microservices.io, Namastack 글과 유사하다.
- future scaling concern은 Banandre, Namastack Features 문서의 문제의식과 가깝다.

즉 현재 구조는 "정합성 우선의 보수적 SQL 구현"으로 볼 수 있다.

## 다음 설계 단계에 대한 시사점

조사한 사례를 기준으로 하면 다음 판단이 가능하다.

- 지금 단계:
  `aggregate_id` 내부 strict ordering이 중요하다면 현재 head-only 방식은 충분히 방어적이다.

- 병목이 커질 때:
  단순히 `head_aggregates JOIN` 식으로 같은 aggregate 여러 row를 한 번에 잡는 것은 위험하다. 실전 사례들은 이런 상황에서 SQL만 더 복잡하게 하기보다 `partition ownership` 또는 `aggregate ownership`으로 올라간다.

- 장기 방향:
  hot aggregate가 실제 병목으로 확인되면 다음 후보는 `outbox_aggregate_lock` 테이블 방식 또는 hash partitioning 방식이다.

## 결론

이번에 확인한 글들 중 "현재 `common-kafka`와 완전히 같은 SQL"을 제시하는 사례는 찾지 못했다. 다만 ordering을 중요하게 보는 실전 글들은 공통적으로 `same aggregate/key는 순차`, `different key는 병렬`, `SKIP LOCKED만으로는 확장성이 공짜가 아님`이라는 방향을 공유한다.

따라서 현재 설계는 정합성 우선 관점에서 충분히 타당하다. 다만 throughput 문제가 현실화되면, 다음 단계 해법은 `WHERE` 절 미세 조정보다 `aggregate ownership` 또는 `partition ownership`으로 가는 것이 외부 사례들과도 더 잘 맞는다.
