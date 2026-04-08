-- V46: pipeline_job에 pipeline_definition_id 추가 + dependency 테이블 정리

-- 1. pipeline_job에 pipeline_definition_id 컬럼 추가 (JPA Entity 매핑용)
ALTER TABLE pipeline_job ADD COLUMN IF NOT EXISTS pipeline_definition_id BIGINT;

-- 2. pipeline_job_mapping에서 pipeline_definition_id를 역채움
UPDATE pipeline_job j
SET pipeline_definition_id = m.pipeline_definition_id
FROM pipeline_job_mapping m
WHERE j.id = m.job_id AND j.pipeline_definition_id IS NULL;

-- 3. pipeline_job_dependency 중복 제거 + pipeline_definition_id 컬럼 제거
DELETE FROM pipeline_job_dependency a
    USING pipeline_job_dependency b
WHERE a.ctid < b.ctid
  AND a.job_id = b.job_id
  AND a.depends_on_job_id = b.depends_on_job_id;

ALTER TABLE pipeline_job_dependency DROP CONSTRAINT IF EXISTS pipeline_job_dependency_pkey;
ALTER TABLE pipeline_job_dependency DROP CONSTRAINT IF EXISTS fk_dep_pipeline_definition;
ALTER TABLE pipeline_job_dependency DROP COLUMN IF EXISTS pipeline_definition_id;
ALTER TABLE pipeline_job_dependency ADD PRIMARY KEY (job_id, depends_on_job_id);
