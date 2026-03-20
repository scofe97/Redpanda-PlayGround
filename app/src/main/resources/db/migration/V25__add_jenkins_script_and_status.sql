-- Job 생성 시 Jenkins 파이프라인 동적 생성을 위한 컬럼 추가
-- 사용자 정의 Jenkinsfile 스크립트 저장 (DB = Source of Truth)
ALTER TABLE pipeline_job ADD COLUMN jenkins_script TEXT;

-- Jenkins 연동 상태 추적
-- PENDING: 미등록, ACTIVE: Jenkins 등록 완료, FAILED: 등록 실패, DELETING: 삭제 중
ALTER TABLE pipeline_job ADD COLUMN jenkins_status VARCHAR(20) NOT NULL DEFAULT 'PENDING';
