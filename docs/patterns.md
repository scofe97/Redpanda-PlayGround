# 아키텍처 패턴 카탈로그

이 문서는 Redpanda Playground에 적용된 10개 아키텍처 패턴의 인덱스다. 각 패턴의 상세 내용은 `patterns/` 디렉토리의 개별 문서를 참조한다.

## 패턴 요약

| # | 패턴 | 핵심 한 줄 | 상세 |
|---|------|-----------|------|
| 1 | 202 Accepted | 긴 작업은 즉시 응답 + 추적 URL 제공 | [patterns/01-202-accepted.md](patterns/01-202-accepted.md) |
| 2 | SAGA Orchestrator | PipelineEngine이 오케스트레이터, 실패 시 완료 스텝 역순 보상 | [patterns/02-saga-orchestrator.md](patterns/02-saga-orchestrator.md) |
| 3 | Transactional Outbox | DB 트랜잭션 + 이벤트 발행 원자성, 500ms 폴링 | [patterns/03-transactional-outbox.md](patterns/03-transactional-outbox.md) |
| 4 | SSE 실시간 알림 | 서버→클라이언트 단방향 스트리밍, TanStack Query 캐시 무효화 | [patterns/04-sse-realtime.md](patterns/04-sse-realtime.md) |
| 5 | Break-and-Resume | 웹훅 대기 시 스레드 해제, CAS로 경쟁 조건 방지 | [patterns/05-break-and-resume.md](patterns/05-break-and-resume.md) |
| 6 | ~~Redpanda Connect~~ (제거됨) | ~~HTTP↔Kafka 브릿지~~ → 현재는 JenkinsCommandConsumer(직접 소비) + Jenkins rpk(직접 produce) | [patterns/06-redpanda-connect.md](patterns/06-redpanda-connect.md) |
| 7 | 토픽/메시지 설계 | 도메인별 토픽, EventMetadata 공통 스키마, CloudEvents | [patterns/07-topic-message-design.md](patterns/07-topic-message-design.md) |
| 8 | Adapter/Fallback | 외부 시스템별 어댑터 분리, ToolRegistry 기반 동적 해석 | [patterns/08-adapter-fallback.md](patterns/08-adapter-fallback.md) |
| 9 | Idempotency | (correlationId, eventType) 복합 키, preemptive acquire | [patterns/09-idempotency.md](patterns/09-idempotency.md) |
| 10 | ~~Dynamic Connector Management~~ (제거됨) | ~~런타임 Connect 스트림 등록/삭제~~ → Connect 제거로 해당 없음 | [patterns/10-dynamic-connector.md](patterns/10-dynamic-connector.md) |
