ALTER TABLE pipeline_definition ADD COLUMN parameter_schema_json TEXT;
ALTER TABLE pipeline_execution ADD COLUMN parameters_json TEXT;
