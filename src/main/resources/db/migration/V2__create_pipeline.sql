CREATE TABLE pipeline_execution (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    ticket_id BIGINT NOT NULL REFERENCES ticket(id),
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    started_at TIMESTAMP,
    completed_at TIMESTAMP,
    error_message TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE pipeline_step (
    id BIGSERIAL PRIMARY KEY,
    execution_id UUID NOT NULL REFERENCES pipeline_execution(id) ON DELETE CASCADE,
    step_order INTEGER NOT NULL,
    step_type VARCHAR(30) NOT NULL,
    step_name VARCHAR(100) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    log TEXT,
    started_at TIMESTAMP,
    completed_at TIMESTAMP
);

CREATE INDEX idx_pipeline_execution_ticket_id ON pipeline_execution(ticket_id);
CREATE INDEX idx_pipeline_step_execution_id ON pipeline_step(execution_id);
