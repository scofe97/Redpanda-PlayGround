-- V48: Rename TPS-legacy Korean abbreviated columns to clean English naming
-- Affected tables: job, pipeline, pipeline_ver, pipeline_step

-- ============================================================
-- job table
-- ============================================================

-- Boolean conversions (del_yn, job_lck_yn)
ALTER TABLE job ADD COLUMN deleted boolean NOT NULL DEFAULT false;
UPDATE job SET deleted = (del_yn = 'Y');
ALTER TABLE job DROP COLUMN del_yn;

ALTER TABLE job ADD COLUMN locked boolean NOT NULL DEFAULT false;
UPDATE job SET locked = (job_lck_yn = 'Y');
ALTER TABLE job DROP COLUMN job_lck_yn;

-- Simple renames
ALTER TABLE job RENAME COLUMN job_ctgr_cd TO category;
ALTER TABLE job RENAME COLUMN job_type_cd TO type;
ALTER TABLE job RENAME COLUMN reg_dt TO created_at;
ALTER TABLE job RENAME COLUMN rgtr_id TO created_by;
ALTER TABLE job RENAME COLUMN mdfcn_dt TO updated_at;
ALTER TABLE job RENAME COLUMN mdfr_id TO updated_by;

-- ============================================================
-- pipeline table
-- ============================================================

-- Boolean conversions (del_yn, fail_continue_yn)
ALTER TABLE pipeline ADD COLUMN deleted boolean NOT NULL DEFAULT false;
UPDATE pipeline SET deleted = (del_yn = 'Y');
ALTER TABLE pipeline DROP COLUMN del_yn;

ALTER TABLE pipeline ADD COLUMN fail_continue boolean NOT NULL DEFAULT false;
UPDATE pipeline SET fail_continue = (fail_continue_yn = 'Y');
ALTER TABLE pipeline DROP COLUMN fail_continue_yn;

-- Simple renames
ALTER TABLE pipeline RENAME COLUMN pipeline_nm TO pipeline_name;
ALTER TABLE pipeline RENAME COLUMN in_out_se TO in_out_type;
ALTER TABLE pipeline RENAME COLUMN reg_dt TO created_at;
ALTER TABLE pipeline RENAME COLUMN rgtr_id TO created_by;
ALTER TABLE pipeline RENAME COLUMN mdfcn_dt TO updated_at;
ALTER TABLE pipeline RENAME COLUMN mdfr_id TO updated_by;

-- ============================================================
-- pipeline_ver table
-- ============================================================

ALTER TABLE pipeline_ver RENAME COLUMN pipeline_ver_id TO pipeline_version_id;
ALTER TABLE pipeline_ver RENAME COLUMN pl_ver TO version;
ALTER TABLE pipeline_ver RENAME COLUMN pl_ver_desc TO description;
ALTER TABLE pipeline_ver RENAME COLUMN reg_dt TO created_at;
ALTER TABLE pipeline_ver RENAME COLUMN rgtr_id TO created_by;

-- ============================================================
-- pipeline_step table
-- ============================================================

ALTER TABLE pipeline_step RENAME COLUMN pipeline_ver_id TO pipeline_version_id;
ALTER TABLE pipeline_step RENAME COLUMN step_seq TO step_order;
ALTER TABLE pipeline_step RENAME COLUMN reg_dt TO created_at;
ALTER TABLE pipeline_step RENAME COLUMN rgtr_id TO created_by;
