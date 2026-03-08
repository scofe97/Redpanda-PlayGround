-- 기본 지원 도구 등록 (호스트에서 앱 실행 기준, localhost:매핑포트)
-- credential은 평문 저장
-- 이미 존재하면 무시 (tool_type 기준)
INSERT INTO support_tool (tool_type, name, url, username, credential, active)
SELECT 'JENKINS', 'Playground Jenkins', 'http://localhost:29080', 'admin', 'admin', true
WHERE NOT EXISTS (SELECT 1 FROM support_tool WHERE tool_type = 'JENKINS');

INSERT INTO support_tool (tool_type, name, url, username, credential, active)
SELECT 'GITLAB', 'Playground GitLab', 'http://localhost:29180', 'root', 'playground1234!', true
WHERE NOT EXISTS (SELECT 1 FROM support_tool WHERE tool_type = 'GITLAB');

INSERT INTO support_tool (tool_type, name, url, username, credential, active)
SELECT 'NEXUS', 'Playground Nexus', 'http://localhost:28881', 'admin', 'admin123', true
WHERE NOT EXISTS (SELECT 1 FROM support_tool WHERE tool_type = 'NEXUS');

INSERT INTO support_tool (tool_type, name, url, username, credential, active)
SELECT 'REGISTRY', 'Playground Registry', 'http://localhost:25050', null, null, true
WHERE NOT EXISTS (SELECT 1 FROM support_tool WHERE tool_type = 'REGISTRY');
