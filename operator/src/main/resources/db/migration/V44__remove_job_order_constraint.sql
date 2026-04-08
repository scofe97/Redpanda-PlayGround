ALTER TABLE pipeline_job_execution ALTER COLUMN job_order DROP NOT NULL;
CREATE INDEX idx_pje_execution_job ON pipeline_job_execution(execution_id, job_id);
