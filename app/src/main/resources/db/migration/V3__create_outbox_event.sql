CREATE TABLE outbox_event (
    id BIGSERIAL PRIMARY KEY,
    aggregate_type VARCHAR(50) NOT NULL,
    aggregate_id VARCHAR(100) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    payload BYTEA NOT NULL,
    topic VARCHAR(200) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    sent_at TIMESTAMP,
    retry_count INTEGER NOT NULL DEFAULT 0
);

-- TODO: 조회량 증가 시 (status, created_at) 복합 인덱스로 교체 고려
-- CREATE INDEX idx_outbox_event_status_created_at ON outbox_event(status, created_at) WHERE status = 'PENDING';
CREATE INDEX idx_outbox_event_status ON outbox_event(status) WHERE status = 'PENDING';
