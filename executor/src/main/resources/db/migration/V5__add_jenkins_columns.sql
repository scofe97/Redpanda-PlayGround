ALTER TABLE execution_job ADD COLUMN jenkins_instance_id BIGINT;
ALTER TABLE execution_job ADD COLUMN job_name VARCHAR(200);
