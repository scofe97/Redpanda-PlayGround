package com.study.playground.operator.pipeline.job.infrastructure.persistence;

import com.study.playground.operator.pipeline.job.domain.port.out.LoadJobDependenciesPort;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * LoadJobDependenciesPort 구현.
 * Job + Purpose(Preset) + SupportTool(CI_CD_TOOL)에서 Jenkins 접속 정보를 조회한다.
 * app 모듈의 테이블을 cross-schema 또는 같은 스키마에서 조회.
 */
@Component
@RequiredArgsConstructor
public class JobDependenciesAdapter implements LoadJobDependenciesPort {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public JobDependencies load(String jobId, String presetId) {
        // TODO: 실제 테이블 구조에 맞춰 쿼리 수정
        // Job → Purpose(preset) → PurposeEntry(CI_CD_TOOL) → SupportTool
        var sql = """
                SELECT j.job_id, j.project_id, j.preset_id,
                       st.url, st.username, st.credential, NULL as jenkins_script
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
                , rs.getString("credential")
                , rs.getString("jenkins_script")
        ), jobId);
    }
}
