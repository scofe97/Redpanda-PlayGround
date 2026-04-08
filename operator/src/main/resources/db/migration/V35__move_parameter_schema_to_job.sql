-- 파라미터 스키마를 pipeline_definition에서 pipeline_job으로 이동
ALTER TABLE pipeline_job ADD COLUMN parameter_schema_json TEXT;
ALTER TABLE pipeline_definition DROP COLUMN parameter_schema_json;
