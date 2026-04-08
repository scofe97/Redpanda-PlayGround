-- Add CHECK constraint for job_type column to enforce valid enum values
ALTER TABLE pipeline_job
    ADD CONSTRAINT chk_pipeline_job_type
    CHECK (job_type IN ('BUILD', 'DEPLOY', 'IMPORT', 'ARTIFACT_DOWNLOAD', 'IMAGE_PULL'));
