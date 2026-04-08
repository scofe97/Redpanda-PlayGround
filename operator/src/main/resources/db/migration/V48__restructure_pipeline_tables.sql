-- V48: pipeline 테이블을 새 JPA 엔티티 스키마에 맞춰 재구성
-- 기존 pipeline 관련 테이블을 DROP 후 새 스키마로 CREATE

-- 1. 기존 테이블 삭제 (의존성 순서)
DROP TABLE IF EXISTS pipeline_job_execution CASCADE;
DROP TABLE IF EXISTS pipeline_job_mapping CASCADE;
DROP TABLE IF EXISTS pipeline_job_dependency CASCADE;
DROP TABLE IF EXISTS pipeline_execution CASCADE;
DROP TABLE IF EXISTS pipeline_job CASCADE;
DROP TABLE IF EXISTS pipeline_definition CASCADE;

-- 2. pipeline 테이블 (구 pipeline_definition)
CREATE TABLE pipeline (
    pipeline_id   VARCHAR(20)  PRIMARY KEY,
    project_id    VARCHAR(20)  NOT NULL,
    pipeline_name VARCHAR(128) NOT NULL,
    pipeline_desc VARCHAR(512),
    fail_continue BOOLEAN      NOT NULL DEFAULT false,
    in_out_type   VARCHAR(1)   NOT NULL DEFAULT 'IN',
    deleted       BOOLEAN      NOT NULL DEFAULT false,
    created_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    created_by    VARCHAR(10)  NOT NULL,
    updated_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_by    VARCHAR(10)  NOT NULL
);

-- 3. job 테이블 (구 pipeline_job)
CREATE TABLE job (
    job_id      VARCHAR(20)  PRIMARY KEY,
    project_id  VARCHAR(50)  NOT NULL,
    preset_id   VARCHAR(50)  NOT NULL,
    category    VARCHAR(20)  NOT NULL,
    type        VARCHAR(20)  NOT NULL,
    locked      BOOLEAN      NOT NULL DEFAULT false,
    job_tags    TEXT,
    link_job_id VARCHAR(20),
    deleted     BOOLEAN      NOT NULL DEFAULT false,
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    created_by  VARCHAR(50),
    updated_at  TIMESTAMP             DEFAULT NOW(),
    updated_by  VARCHAR(50)
);

-- 4. pipeline_ver 테이블 (신규)
CREATE TABLE pipeline_ver (
    pipeline_version_id BIGSERIAL    PRIMARY KEY,
    pipeline_id         VARCHAR(20)  NOT NULL REFERENCES pipeline(pipeline_id),
    version             INTEGER      NOT NULL,
    description         VARCHAR(512),
    created_at          TIMESTAMP    NOT NULL DEFAULT NOW(),
    created_by          VARCHAR(10),
    UNIQUE (pipeline_id, version)
);

-- 5. pipeline_step 테이블 (신규 — 구 pipeline_job_mapping 대체)
CREATE TABLE pipeline_step (
    pipeline_step_id    BIGSERIAL   PRIMARY KEY,
    pipeline_version_id BIGINT      NOT NULL REFERENCES pipeline_ver(pipeline_version_id),
    step_order          INTEGER     NOT NULL,
    job_id              VARCHAR(20) NOT NULL,
    created_at          TIMESTAMP   NOT NULL DEFAULT NOW(),
    created_by          VARCHAR(10),
    UNIQUE (pipeline_version_id, step_order, job_id)
);
