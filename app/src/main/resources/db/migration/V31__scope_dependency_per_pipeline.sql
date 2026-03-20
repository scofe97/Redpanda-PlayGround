-- V31: pipeline_job_dependency를 파이프라인별로 범위 지정한다.
-- 동일 Job이 여러 파이프라인에서 서로 다른 의존성을 가질 수 있도록 한다.

-- 기존 데이터 삭제 (재생성)
DELETE FROM pipeline_job_dependency;

-- PK 변경: (job_id, depends_on_job_id) → (pipeline_definition_id, job_id, depends_on_job_id)
ALTER TABLE pipeline_job_dependency DROP CONSTRAINT pipeline_job_dependency_pkey;
ALTER TABLE pipeline_job_dependency ADD COLUMN pipeline_definition_id BIGINT NOT NULL DEFAULT 0;
ALTER TABLE pipeline_job_dependency ADD CONSTRAINT pipeline_job_dependency_pkey
    PRIMARY KEY (pipeline_definition_id, job_id, depends_on_job_id);
ALTER TABLE pipeline_job_dependency ADD CONSTRAINT fk_dep_pipeline_definition
    FOREIGN KEY (pipeline_definition_id) REFERENCES pipeline_definition(id) ON DELETE CASCADE;
ALTER TABLE pipeline_job_dependency ALTER COLUMN pipeline_definition_id DROP DEFAULT;

-- 기존 매핑에서 의존성 복원
INSERT INTO pipeline_job_dependency (pipeline_definition_id, job_id, depends_on_job_id)
SELECT m.pipeline_definition_id, m.job_id, d.depends_on_job_id
FROM pipeline_job_mapping m
CROSS JOIN LATERAL (SELECT unnest(ARRAY[]::bigint[]) as depends_on_job_id) d
WHERE FALSE;
