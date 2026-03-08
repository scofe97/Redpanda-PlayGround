CREATE TABLE support_tool (
    id BIGSERIAL PRIMARY KEY,
    tool_type VARCHAR(20) NOT NULL,
    name VARCHAR(100) NOT NULL,
    url VARCHAR(500) NOT NULL,
    username VARCHAR(100),
    credential VARCHAR(500),
    active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- 기존 application.yml 값을 시드 데이터로 삽입
INSERT INTO support_tool (tool_type, name, url, username, credential, active) VALUES
('JENKINS', 'Local Jenkins', 'http://localhost:29080', 'admin',
 encode('5aea152c379246c9aeeef3285f0d5ded'::bytea, 'base64'), true),
('GITLAB', 'Local GitLab', 'http://localhost:29180', NULL,
 encode('glpat-JCjn3B9UU4CeS7sdHx4x'::bytea, 'base64'), true),
('NEXUS', 'Local Nexus', 'http://localhost:28881', 'admin', NULL, true),
('REGISTRY', 'Local Registry', 'http://localhost:25050', NULL, NULL, true);
