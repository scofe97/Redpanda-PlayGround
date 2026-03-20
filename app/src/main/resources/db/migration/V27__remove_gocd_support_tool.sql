-- GoCD 구현체 미지원으로 제거
-- preset_entry에서 GOCD 도구 참조 먼저 삭제 (FK 제약)
DELETE FROM preset_entry WHERE tool_id IN (SELECT id FROM support_tool WHERE implementation = 'GOCD');
DELETE FROM support_tool WHERE implementation = 'GOCD';
