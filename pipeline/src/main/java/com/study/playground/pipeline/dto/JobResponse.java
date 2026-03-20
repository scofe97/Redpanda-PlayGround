package com.study.playground.pipeline.dto;

import com.study.playground.pipeline.domain.PipelineJob;
import com.study.playground.pipeline.domain.PipelineJobType;

import java.time.LocalDateTime;

/**
 * Job 독립 CRUD 응답 DTO.
 *
 * <p>presetName은 DB JOIN으로 채워진 {@link PipelineJob#getPresetName()}을 사용한다.
 * Pipeline 매핑 컨텍스트에서 생성된 Job은 JOIN이 없으므로 presetName이 null일 수 있다.</p>
 */
public record JobResponse(
        Long id
        , String jobName
        , PipelineJobType jobType
        , Long presetId
        , String presetName
        , String configJson
        , String jenkinsScript
        , String jenkinsStatus
        , LocalDateTime createdAt
) {

    /** findById 등 presetName이 JOIN으로 채워진 경우에 사용한다. */
    public static JobResponse from(PipelineJob job) {
        return new JobResponse(
                job.getId()
                , job.getJobName()
                , job.getJobType()
                , job.getPresetId()
                , job.getPresetName()
                , job.getConfigJson()
                , job.getJenkinsScript()
                , job.getJenkinsStatus()
                , job.getCreatedAt()
        );
    }
}
