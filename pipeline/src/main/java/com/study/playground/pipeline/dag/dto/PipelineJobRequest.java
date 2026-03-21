package com.study.playground.pipeline.dag.dto;

import com.study.playground.pipeline.domain.PipelineJobType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class PipelineJobRequest {
    @NotBlank
    private String jobName;
    @NotNull
    private PipelineJobType jobType;
    @NotNull
    private Integer executionOrder;
    private String configJson;
    /** 이 Job이 의존하는 선행 Job의 인덱스 (요청 내 jobs 배열 기준 0-based). */
    private List<Integer> dependsOnIndices;
}
