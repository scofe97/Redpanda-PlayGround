-- Outbox 이벤트에 W3C traceparent를 저장하여 E2E 트레이스를 연결한다.
-- OutboxPoller가 Kafka 발행 시 이 값을 복원하여 원래 HTTP 요청 trace에 연결한다.
-- nullable: OTel Agent 없이 실행할 때는 저장되지 않는다.
ALTER TABLE outbox_event ADD COLUMN trace_parent VARCHAR(55);
