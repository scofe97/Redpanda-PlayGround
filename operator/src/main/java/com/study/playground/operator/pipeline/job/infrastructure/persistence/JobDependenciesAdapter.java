package com.study.playground.operator.pipeline.job.infrastructure.persistence;

import com.study.playground.operator.pipeline.job.domain.port.out.LoadJobDependenciesPort;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class JobDependenciesAdapter implements LoadJobDependenciesPort {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public JobDependencies load(String jobId, String presetId) {
        // operator 내부 조인은 Job -> Purpose -> CI_CD_TOOL PurposeEntry -> SupportTool 순서다.
        var sql = """
                SELECT j.job_id, j.project_id, j.preset_id,
                       st.url, st.username, st.api_token, NULL as jenkins_script
                FROM job j
                JOIN purpose p ON p.id = CAST(j.preset_id AS BIGINT)
                JOIN purpose_entry pe ON pe.purpose_id = p.id AND pe.category = 'CI_CD_TOOL'
                JOIN support_tool st ON st.id = pe.tool_id
                WHERE j.job_id = ?
                """;

        return jdbcTemplate.queryForObject(sql, (rs, rowNum) -> new JobDependencies(
                rs.getString("project_id")
                , rs.getString("preset_id")
                , rs.getString("job_id")
                , rs.getString("url")
                , rs.getString("username")
                , rs.getString("api_token")
                , rs.getString("jenkins_script")
        ), jobId);
    }
}
