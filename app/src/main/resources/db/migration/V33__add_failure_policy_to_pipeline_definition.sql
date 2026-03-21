ALTER TABLE pipeline_definition ADD COLUMN failure_policy VARCHAR(30) NOT NULL DEFAULT 'STOP_ALL';
