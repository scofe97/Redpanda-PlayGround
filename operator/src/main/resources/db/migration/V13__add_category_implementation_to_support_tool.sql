-- ToolType(도구명=역할) → ToolCategory(역할) + ToolImplementation(구현체) 분리
-- 기존 tool_type은 deprecated로 잔류, 안정화 후 제거 예정

ALTER TABLE support_tool
    ADD COLUMN category VARCHAR(30),
    ADD COLUMN implementation VARCHAR(30);

-- 기존 데이터 마이그레이션
UPDATE support_tool SET category = 'CI_CD_TOOL',        implementation = 'JENKINS'         WHERE tool_type = 'JENKINS';
UPDATE support_tool SET category = 'VCS',               implementation = 'GITLAB'          WHERE tool_type = 'GITLAB';
UPDATE support_tool SET category = 'LIBRARY',           implementation = 'NEXUS'           WHERE tool_type = 'NEXUS';
UPDATE support_tool SET category = 'CONTAINER_REGISTRY', implementation = 'DOCKER_REGISTRY' WHERE tool_type = 'REGISTRY';

-- NOT NULL 제약 추가
ALTER TABLE support_tool ALTER COLUMN category SET NOT NULL;
ALTER TABLE support_tool ALTER COLUMN implementation SET NOT NULL;

-- 카테고리 기반 조회를 위한 인덱스
CREATE INDEX idx_support_tool_category ON support_tool(category);
