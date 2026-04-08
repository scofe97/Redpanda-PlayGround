-- pipeline_execution에 디스패치 상태 컬럼 추가 (분산 큐 전환)
ALTER TABLE pipeline_execution
    ADD COLUMN dispatch_status VARCHAR(20) NOT NULL DEFAULT 'PENDING';

CREATE INDEX idx_pe_dispatch_status_created
    ON pipeline_execution(dispatch_status, created_at ASC);

-- 기존 데이터 마이그레이션: 완료된 실행은 DONE, 실행 중은 DISPATCHING
UPDATE pipeline_execution
    SET dispatch_status = 'DONE'
    WHERE status IN ('SUCCESS', 'FAILED');

UPDATE pipeline_execution
    SET dispatch_status = 'DISPATCHING'
    WHERE status = 'RUNNING';
