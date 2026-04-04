-- V45: pipeline_job.pipeline_definition_id 제거
-- V23에서 pipeline_job_mapping 다대다 테이블로 관계를 이관했으므로
-- Job 테이블에 남아있던 레거시 FK를 정리한다.

DROP INDEX IF EXISTS idx_pipeline_job_definition_id;
ALTER TABLE pipeline_job DROP COLUMN pipeline_definition_id;
