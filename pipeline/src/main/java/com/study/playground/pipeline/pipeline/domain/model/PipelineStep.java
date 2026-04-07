package com.study.playground.pipeline.pipeline.domain.model;

import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 순수 도메인 모델 — 파이프라인 스텝 (TB_TPS_PP_002).
 * 동일 STEP_SEQ를 가진 스텝은 향후 병렬 실행으로 확장 가능.
 */
@Getter
public class PipelineStep {

    private Long stepId;
    private Long versionId;
    private String jobId;
    private int stepOrder;
    private LocalDateTime createdAt;
    private String createdBy;

    public static PipelineStep create(String jobId, int stepOrder, String createdBy) {
        var s = new PipelineStep();
        s.jobId = jobId;
        s.stepOrder = stepOrder;
        s.createdAt = LocalDateTime.now();
        s.createdBy = createdBy;
        return s;
    }

    public void setVersionId(Long versionId) { this.versionId = versionId; }

    protected PipelineStep() {}
}
