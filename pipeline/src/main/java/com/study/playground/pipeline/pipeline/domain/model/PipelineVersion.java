package com.study.playground.pipeline.pipeline.domain.model;

import lombok.Getter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 순수 도메인 모델 — 파이프라인 버전 (TB_TPS_PP_011).
 * 파이프라인 수정 시 새 버전 행이 생성된다.
 */
@Getter
public class PipelineVersion {

    private Long versionId;
    private String pipelineId;
    private int version;
    private String description;
    private LocalDateTime createdAt;
    private String createdBy;
    private List<PipelineStep> steps = new ArrayList<>();

    public static PipelineVersion create(
            String pipelineId
            , int version
            , String description
            , String createdBy
    ) {
        var v = new PipelineVersion();
        v.pipelineId = pipelineId;
        v.version = version;
        v.description = description;
        v.createdAt = LocalDateTime.now();
        v.createdBy = createdBy;
        return v;
    }

    public void addStep(String jobId, int stepOrder, String createdBy) {
        steps.add(PipelineStep.create(jobId, stepOrder, createdBy));
    }

    public void setVersionId(Long versionId) { this.versionId = versionId; }

    protected PipelineVersion() {}
}
