-- Executor 내부 Job 실행 테이블 (TB_TPS_EX_003)
CREATE TABLE execution_job (
    job_excn_id       VARCHAR(20)   NOT NULL PRIMARY KEY,
    pipeline_excn_id  VARCHAR(20),
    job_id            VARCHAR(20)   NOT NULL,
    build_no          INT,
    excn_stts         VARCHAR(16)   NOT NULL DEFAULT 'PENDING',
    priority          INT           NOT NULL DEFAULT 1,
    priority_dt       TIMESTAMP(6)  NOT NULL,
    retry_cnt         INT           NOT NULL DEFAULT 0,
    bgng_dt           TIMESTAMP(6),
    end_dt            TIMESTAMP(6),
    log_file_yn       CHAR(1)       NOT NULL DEFAULT 'N',
    reg_dt            TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    rgtr_id           VARCHAR(10),
    mdfcn_dt          TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    mdfr_id           VARCHAR(10),
    version           BIGINT        NOT NULL DEFAULT 0
);

CREATE INDEX idx_ej_stts_priority ON execution_job (excn_stts, priority ASC, priority_dt ASC);
CREATE INDEX idx_ej_pipeline ON execution_job (pipeline_excn_id, excn_stts);

-- Outbox 이벤트 테이블 (EventPublisher가 사용)
CREATE TABLE outbox_event (
    id              BIGSERIAL PRIMARY KEY,
    aggregate_type  VARCHAR(50)  NOT NULL,
    aggregate_id    VARCHAR(100) NOT NULL,
    event_type      VARCHAR(100) NOT NULL,
    payload         BYTEA        NOT NULL,
    topic           VARCHAR(200) NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    sent_at         TIMESTAMP,
    retry_count     INTEGER      NOT NULL DEFAULT 0,
    correlation_id  VARCHAR(100),
    trace_parent    VARCHAR(55),
    next_retry_at   TIMESTAMP
);

CREATE INDEX idx_outbox_pending ON outbox_event(status, created_at) WHERE status = 'PENDING';
