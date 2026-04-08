-- Job은 독립 엔티티로 단독 실행 가능하므로 execution_order는 nullable이어야 한다.
-- 실행 순서는 pipeline_job_mapping 테이블에서 파이프라인 조합 시 결정된다.
ALTER TABLE pipeline_job ALTER COLUMN execution_order DROP NOT NULL;
