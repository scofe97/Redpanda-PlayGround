-- 기존 프리셋을 "default"로 이름 변경 (V26은 이름 불일치로 미적용)
UPDATE middleware_preset SET name = 'default', description = '기본 프리셋' WHERE name = 'preset-team-b';
