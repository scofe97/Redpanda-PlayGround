-- Operator Job 테이블
CREATE TABLE operator_job (
    id                    BIGSERIAL PRIMARY KEY,
    execution_pipeline_id VARCHAR(100),
    job_id                BIGINT NOT NULL,
    pipeline_id           VARCHAR(100),
    job_name              VARCHAR(200) NOT NULL,
    job_order             INT NOT NULL,
    jenkins_instance_id   BIGINT NOT NULL,
    config_json           TEXT,
    status                VARCHAR(20) NOT NULL DEFAULT 'WAIT',
    created_at            TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMP
);

-- Outbox 이벤트 테이블
CREATE TABLE outbox_event (
    id              BIGSERIAL PRIMARY KEY,
    aggregate_type  VARCHAR(50) NOT NULL,
    aggregate_id    VARCHAR(100) NOT NULL,
    event_type      VARCHAR(100) NOT NULL,
    payload         BYTEA NOT NULL,
    topic           VARCHAR(200) NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    sent_at         TIMESTAMP,
    retry_count     INTEGER NOT NULL DEFAULT 0,
    correlation_id  VARCHAR(100),
    trace_parent    VARCHAR(55),
    next_retry_at   TIMESTAMP
);

CREATE INDEX idx_op_outbox_pending ON outbox_event(status, created_at) WHERE status = 'PENDING';
