-- DEPLOY 타입 Job의 배포 모드 (IMPORT: 반입, BUILD_REQUIRED: 빌드 연동)
ALTER TABLE pipeline_job ADD COLUMN deploy_mode VARCHAR(20);

-- BUILD_REQUIRED 모드에서 참조하는 빌드 Job ID
ALTER TABLE pipeline_job ADD COLUMN required_build_job_id BIGINT;

ALTER TABLE pipeline_job ADD CONSTRAINT fk_required_build_job
    FOREIGN KEY (required_build_job_id) REFERENCES pipeline_job(id);

ALTER TABLE pipeline_job ADD CONSTRAINT chk_deploy_mode
    CHECK (deploy_mode IS NULL OR deploy_mode IN ('IMPORT', 'BUILD_REQUIRED'));
