package com.study.playground.executor.execution.infrastructure.persistence;

import com.study.playground.executor.execution.domain.model.JobDefinitionInfo;
import com.study.playground.executor.execution.domain.port.out.JobDefinitionQueryPort;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class JobDefinitionQueryAdapter implements JobDefinitionQueryPort {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public JobDefinitionInfo load(String jobId) {
        var sql = """
                SELECT j.job_id, j.project_id, j.preset_id
                     , st.id as jenkins_instance_id
                FROM operator.job j
                JOIN operator.purpose p ON p.id = CAST(j.preset_id AS BIGINT)
                JOIN operator.purpose_entry pe ON pe.purpose_id = p.id AND pe.category = 'CI_CD_TOOL'
                JOIN operator.support_tool st ON st.id = pe.tool_id
                WHERE j.job_id = ?
                """;
        return jdbcTemplate.queryForObject(sql, (rs, rowNum) -> new JobDefinitionInfo(
                rs.getString("job_id")
                , rs.getString("project_id")
                , rs.getString("preset_id")
                , rs.getLong("jenkins_instance_id")
        ), jobId);
    }
}
