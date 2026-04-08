-- 1. project 테이블 생성
CREATE TABLE project (
    id          BIGSERIAL    PRIMARY KEY,
    name        VARCHAR(100) NOT NULL UNIQUE,
    description VARCHAR(500),
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- 2. middleware_preset → purpose 리네이밍
ALTER TABLE middleware_preset RENAME TO purpose;
ALTER TABLE preset_entry RENAME TO purpose_entry;
ALTER TABLE purpose_entry RENAME COLUMN preset_id TO purpose_id;

-- 3. purpose에 project_id FK 추가
ALTER TABLE purpose ADD COLUMN project_id BIGINT REFERENCES project(id);

-- 4. 제약조건/인덱스 리네이밍
ALTER INDEX idx_preset_entry_preset_id RENAME TO idx_purpose_entry_purpose_id;

-- 5. pipeline_job의 preset_id → purpose_id
ALTER TABLE pipeline_job RENAME COLUMN preset_id TO purpose_id;
