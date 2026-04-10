# Kafka 토픽 목록

## 네이밍 규칙

모든 토픽은 `{app}.{domain}.{type}.{name}` 패턴. 버전 접미사 미사용.

| 타입 | 의미 |
|------|------|
| `commands` | "이것을 해라" -- 수신자가 액션 수행 |
| `events` | "이것이 일어났다" -- 발생 사실 통보 |
| `dlq` | 처리 불가 메시지 격리 |

## 전체 토픽

모든 토픽은 Avro 직렬화를 사용한다.

| 토픽 | 타입 | 파티션 | 보존 | 키 | 용도 |
|------|------|--------|------|-----|------|
| `playground.pipeline.commands.execute` | command | 3 | 7일 | executionId | 파이프라인 실행 명령 |
| `playground.pipeline.commands.jenkins` | command | 3 | 7일 | jobName | Jenkins 빌드 트리거 |
| `playground.pipeline.commands.control` | command | 1 | 7일 | executionId | 재평가 트리거 |
| `playground.pipeline.commands.job-dispatch` | command | 3 | 7일 | executionId | 승인된 Job 실행 명령 |
| `playground.pipeline.events.step-changed` | event | 3 | 7일 | executionId | 스텝 상태 전이 |
| `playground.pipeline.events.completed` | event | 3 | 7일 | executionId | 파이프라인 완료 |
| `playground.pipeline.events.dag-job-dispatched` | event | 3 | 7일 | executionId | DAG Job 디스패치 완료 |
| `playground.pipeline.events.dag-job-completed` | event | 3 | 7일 | executionId | DAG Job 실행 완료 |
| `playground.pipeline.events.job-completed` | event | 1 | 7일 | executionId | Job 실행 결과 |
| `playground.ticket.events` | event | 3 | 7일 | ticketId | 티켓 생성/수정 |
| `playground.webhook.events.inbound` | event | 2 | 3일 | webhookSource | 정규화된 웹훅 |
| `playground.audit.events` | event | 1 | 30일 | userId | 감사 추적 |
| `playground.pipeline.dlq.job` | dlq | 1 | 30일 | - | 파이프라인 Job DLQ |
| `playground.dlq` | dlq | 1 | 30일 | - | 글로벌 DLQ |
