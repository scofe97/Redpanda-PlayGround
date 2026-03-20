-- pipeline_step → pipeline_job_execution 리네이밍
-- Step 개념을 제거하고 Job을 실행 추적 단위로 통합한다.
ALTER TABLE pipeline_step RENAME TO pipeline_job_execution;

-- 컬럼 리네이밍: step → job
ALTER TABLE pipeline_job_execution RENAME COLUMN step_order TO job_order;
ALTER TABLE pipeline_job_execution RENAME COLUMN step_type TO job_type;
ALTER TABLE pipeline_job_execution RENAME COLUMN step_name TO job_name;
