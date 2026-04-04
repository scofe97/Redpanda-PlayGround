-- Jenkins 인스턴스별 최대 동시 실행 가능한 executor 수를 저장한다.
-- 멀티 Jenkins 환경에서 executor 가용성 판단에 사용된다.
ALTER TABLE support_tool ADD COLUMN max_executors INT DEFAULT 2;
