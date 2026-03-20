-- 파이프라인 정의 테이블: Job 구성의 설계도를 저장한다.
-- PipelineExecution과 분리하여 동일 정의를 여러 번 실행할 수 있다.
CREATE TABLE pipeline_definition (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(200)  NOT NULL,
    description TEXT,
    preset_id   BIGINT        REFERENCES middleware_preset(id),
    status      VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE',
    created_at  TIMESTAMP     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_pipeline_definition_status ON pipeline_definition(status);
