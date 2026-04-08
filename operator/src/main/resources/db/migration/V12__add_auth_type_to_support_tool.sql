ALTER TABLE support_tool
    ADD COLUMN auth_type VARCHAR(20) NOT NULL DEFAULT 'BASIC';

-- 기존 시드 데이터에 auth_type 설정
-- Jenkins/Nexus: BASIC (username + password/token)
-- GitLab: PRIVATE_TOKEN (Private-Token 헤더)
-- Registry: NONE (인증 없음)
UPDATE support_tool SET auth_type = 'PRIVATE_TOKEN' WHERE tool_type = 'GITLAB';
UPDATE support_tool SET auth_type = 'NONE' WHERE tool_type = 'REGISTRY';
