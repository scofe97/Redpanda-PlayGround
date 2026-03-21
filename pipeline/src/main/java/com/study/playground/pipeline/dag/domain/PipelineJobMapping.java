package com.study.playground.pipeline.dag.domain;

import lombok.Getter;
import lombok.Setter;

import com.study.playground.pipeline.domain.PipelineJobType;
/**
 * 파이프라인 정의와 독립 Job 간의 매핑.
 *
 * <p>pipeline_job_mapping 테이블을 표현한다. Job이 독립 엔티티로 분리된 후,
 * Pipeline은 Job을 직접 소유하지 않고 이 매핑을 통해 참조한다.</p>
 *
 * <p>executionOrder는 매핑 테이블의 컬럼으로, 동일 파이프라인 내 실행 순서 힌트다.
 * jobName, jobType 등 job* 필드는 LEFT JOIN 결과를 받기 위한 편의 필드이며
 * DB 컬럼이 아니다.</p>
 */
@Getter
@Setter
public class PipelineJobMapping {
    private Long pipelineDefinitionId;
    private Long jobId;
    private Integer executionOrder;

    // 조인 결과 매핑용 (DB 컬럼 아님)
    private String jobName;
    private PipelineJobType jobType;
    private Long presetId;
    private String presetName;
    private String configJson;
}
