ALTER TABLE outbox_event ADD COLUMN next_retry_at TIMESTAMP;

DROP INDEX IF EXISTS idx_outbox_event_status;

CREATE INDEX idx_outbox_event_pending ON outbox_event(status, created_at)
    WHERE status = 'PENDING';
