-- pipeline_step에 job_id 추가: 실행된 스텝이 어떤 Job 정의에서 비롯되었는지 추적한다.
-- nullable인 이유: 기존 티켓 기반 파이프라인은 Job 정의 없이 실행된다.
ALTER TABLE pipeline_step
    ADD COLUMN job_id BIGINT REFERENCES pipeline_job(id);

-- pipeline_execution에 pipeline_definition_id 추가: 어떤 정의를 기반으로 실행했는지 기록한다.
-- nullable인 이유: 기존 티켓 기반 파이프라인은 정의 없이 실행된다.
ALTER TABLE pipeline_execution
    ADD COLUMN pipeline_definition_id BIGINT REFERENCES pipeline_definition(id);
