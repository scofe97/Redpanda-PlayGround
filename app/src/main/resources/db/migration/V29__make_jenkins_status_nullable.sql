-- jenkins_script가 없는 Job은 jenkins_status가 null이어야 한다
ALTER TABLE pipeline_job ALTER COLUMN jenkins_status DROP NOT NULL;
ALTER TABLE pipeline_job ALTER COLUMN jenkins_status DROP DEFAULT;

-- 기존 스크립트 없는 Job의 PENDING 상태를 null로 정리
UPDATE pipeline_job SET jenkins_status = NULL WHERE jenkins_script IS NULL;
