package com.study.playground.executor.dispatch.infrastructure.persistence;

import com.study.playground.executor.dispatch.domain.model.JobDefinitionInfo;
import com.study.playground.executor.dispatch.domain.port.out.JobDefinitionQueryPort;
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
                SELECT pj.id as job_id, p.project_id, pj.purpose_id
                     , st.id as jenkins_instance_id
                     , pj.job_name
                FROM public.pipeline_job pj
                JOIN public.purpose p ON p.id = pj.purpose_id
                JOIN public.purpose_entry pe ON pe.purpose_id = p.id AND pe.category = 'CI_CD_TOOL'
                JOIN public.support_tool st ON st.id = pe.tool_id
                WHERE pj.id = CAST(? AS BIGINT)
                """;
        return jdbcTemplate.queryForObject(sql, (rs, rowNum) -> new JobDefinitionInfo(
                rs.getString("job_id")
                , rs.getLong("project_id")
                , rs.getLong("purpose_id")
                , rs.getLong("jenkins_instance_id")
                , rs.getString("job_name")
        ), jobId);
    }
}
