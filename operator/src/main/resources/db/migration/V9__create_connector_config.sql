CREATE TABLE connector_config (
    id          BIGSERIAL PRIMARY KEY,
    stream_id   VARCHAR(100) UNIQUE NOT NULL,
    tool_id     BIGINT NOT NULL,
    yaml_config TEXT NOT NULL,
    direction   VARCHAR(20) NOT NULL,
    created_at  TIMESTAMP NOT NULL DEFAULT NOW(),
    FOREIGN KEY (tool_id) REFERENCES support_tool(id) ON DELETE CASCADE
);
