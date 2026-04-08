-- 파이프라인 Job 테이블: DAG의 노드를 정의한다.
CREATE TABLE pipeline_job (
    id                       BIGSERIAL PRIMARY KEY,
    pipeline_definition_id   BIGINT       NOT NULL REFERENCES pipeline_definition(id) ON DELETE CASCADE,
    job_name                 VARCHAR(200) NOT NULL,
    job_type                 VARCHAR(30)  NOT NULL,
    execution_order          INTEGER      NOT NULL,
    config_json              TEXT,
    created_at               TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_pipeline_job_definition_id ON pipeline_job(pipeline_definition_id);

-- 파이프라인 Job 의존성 테이블: DAG의 엣지를 정의한다.
-- (job_id, depends_on_job_id) 복합 PK로 중복 의존성을 방지한다.
CREATE TABLE pipeline_job_dependency (
    job_id             BIGINT NOT NULL REFERENCES pipeline_job(id) ON DELETE CASCADE,
    depends_on_job_id  BIGINT NOT NULL REFERENCES pipeline_job(id) ON DELETE CASCADE,
    PRIMARY KEY (job_id, depends_on_job_id)
);
