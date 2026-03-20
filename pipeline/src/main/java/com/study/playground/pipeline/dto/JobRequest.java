package com.study.playground.pipeline.dto;

import com.study.playground.pipeline.domain.PipelineJobType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * Job 독립 CRUD용 요청 DTO.
 *
 * <p>Pipeline 매핑에서 사용하는 {@link PipelineJobRequest}와 달리 executionOrder와
 * dependsOnIndices를 포함하지 않는다. 순서와 의존성은 Pipeline 정의 시 관리한다.</p>
 */
@Getter
@Setter
public class JobRequest {

    @NotBlank
    @Size(max = 200)
    private String jobName;

    @NotNull
    private PipelineJobType jobType;

    /** 연결할 미들웨어 프리셋 ID. 필수. */
    @NotNull
    private Long presetId;

    /** Job 타입별 설정 JSON. null 허용. */
    private String configJson;

    /** 사용자 정의 Jenkinsfile 스크립트. */
    private String jenkinsScript;
}
