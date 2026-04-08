-- V30: Job 단독 실행 시 ticket_id가 없으므로 nullable로 변경한다.
-- Phase 2에서 Job이 독립 엔티티가 되면서 티켓 없이 단독 실행이 가능해졌다.

ALTER TABLE pipeline_execution ALTER COLUMN ticket_id DROP NOT NULL;
