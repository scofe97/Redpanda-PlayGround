-- pipeline_job_execution 타임스탬프 컬럼 추가
-- WAITING_EXECUTOR 상태 진입 시점을 DB 단일 원천으로 추적하기 위해 created_at/updated_at 추가.
-- waitingJobs 메모리 구조 제거에 따른 스키마 보완.

ALTER TABLE pipeline_job_execution
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMP NOT NULL DEFAULT NOW();

ALTER TABLE pipeline_job_execution
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP NOT NULL DEFAULT NOW();
