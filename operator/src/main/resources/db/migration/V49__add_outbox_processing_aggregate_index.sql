-- PROCESSING 중인 aggregate를 빠르게 확인하기 위한 Partial Index.
-- NOT EXISTS 가드(findAndMarkProcessing)가 인덱스 lookup 한 번으로 완료된다.
-- PROCESSING 행은 항상 소수이므로 인덱스가 매우 작다.
CREATE INDEX idx_outbox_processing_aggregate
    ON outbox_event(aggregate_id)
    WHERE status = 'PROCESSING';
