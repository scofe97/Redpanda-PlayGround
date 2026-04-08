-- processed_event: (correlationId, eventType) 복합 키 → eventId(ce_id) 단일 키로 변경
-- ce_id(outbox PK)는 모든 메시지에 대해 고유하므로 범용 멱등성 키로 사용한다.
DROP TABLE processed_event;

CREATE TABLE processed_event (
    event_id VARCHAR(100) NOT NULL PRIMARY KEY,
    processed_at TIMESTAMP NOT NULL DEFAULT NOW()
);
