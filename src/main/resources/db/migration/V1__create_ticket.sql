CREATE TABLE ticket (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    description TEXT,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE ticket_source (
    id BIGSERIAL PRIMARY KEY,
    ticket_id BIGINT NOT NULL REFERENCES ticket(id) ON DELETE CASCADE,
    source_type VARCHAR(20) NOT NULL,
    repo_url VARCHAR(500),
    branch VARCHAR(200),
    artifact_coordinate VARCHAR(500),
    image_name VARCHAR(500),
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_ticket_source_ticket_id ON ticket_source(ticket_id);
