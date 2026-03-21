ALTER TABLE pipeline_job_execution ADD COLUMN retry_count INTEGER NOT NULL DEFAULT 0;
