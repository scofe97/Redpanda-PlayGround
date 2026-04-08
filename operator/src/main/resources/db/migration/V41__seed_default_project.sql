-- 기본 프로젝트 생성
INSERT INTO project (name, description) VALUES ('default', '기본 프로젝트');

-- 기존 default 목적에 프로젝트 연결
UPDATE purpose SET project_id = (SELECT id FROM project WHERE name = 'default') WHERE name = 'default';
