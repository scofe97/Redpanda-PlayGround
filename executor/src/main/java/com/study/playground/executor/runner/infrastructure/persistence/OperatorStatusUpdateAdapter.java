package com.study.playground.executor.runner.infrastructure.persistence;

import com.study.playground.executor.runner.domain.port.out.UpdateOperatorStatusPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * UpdateOperatorStatusPort 구현.
 * op DB(operator_stub 스키마)의 pipeline_job_execution 상태를 cross-schema UPDATE한다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OperatorStatusUpdateAdapter implements UpdateOperatorStatusPort {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void updateJobExecutionStatus(String jobExcnId, String status) {
        // TODO: op의 실제 테이블/스키마명 확정 후 수정
        var sql = """
                UPDATE operator_stub.operator_job
                SET status = ?, updated_at = NOW()
                WHERE id = CAST(? AS BIGINT)
                """;

        int updated = jdbcTemplate.update(sql, status, jobExcnId);
        log.debug("[OpStatusUpdate] jobExcnId={}, status={}, rows={}"
                , jobExcnId, status, updated);
    }
}
