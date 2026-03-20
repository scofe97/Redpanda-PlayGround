-- V23: Job을 Pipeline에서 분리하여 독립 엔티티로 만든다.
-- Job은 단독 실행 가능한 단위가 되고, Pipeline은 기존 Job들의 묶음(DAG)이 된다.

-- 1. Job에 preset_id 추가 (프리셋이 Job 레벨로 이동)
ALTER TABLE pipeline_job ADD COLUMN preset_id BIGINT REFERENCES middleware_preset(id);

-- 2. 기존 데이터: pipeline_definition의 preset_id를 각 job으로 복사
UPDATE pipeline_job pj
SET preset_id = pd.preset_id
FROM pipeline_definition pd
WHERE pj.pipeline_definition_id = pd.id AND pd.preset_id IS NOT NULL;

-- 3. pipeline_definition_id nullable로 변경 (독립 Job 허용)
ALTER TABLE pipeline_job ALTER COLUMN pipeline_definition_id DROP NOT NULL;

-- 4. Pipeline ↔ Job 다대다 매핑 테이블
CREATE TABLE pipeline_job_mapping (
    pipeline_definition_id BIGINT NOT NULL REFERENCES pipeline_definition(id) ON DELETE CASCADE,
    job_id                 BIGINT NOT NULL REFERENCES pipeline_job(id) ON DELETE CASCADE,
    execution_order        INTEGER NOT NULL,
    PRIMARY KEY (pipeline_definition_id, job_id)
);
CREATE INDEX idx_pipeline_job_mapping_job ON pipeline_job_mapping(job_id);

-- 5. 기존 데이터 마이그레이션: 현재 FK 관계 → 매핑 테이블로 복사
INSERT INTO pipeline_job_mapping (pipeline_definition_id, job_id, execution_order)
SELECT pipeline_definition_id, id, execution_order
FROM pipeline_job
WHERE pipeline_definition_id IS NOT NULL;

-- 6. pipeline_definition에서 preset_id 제거 (Job 레벨로 이동 완료)
ALTER TABLE pipeline_definition DROP COLUMN preset_id;
