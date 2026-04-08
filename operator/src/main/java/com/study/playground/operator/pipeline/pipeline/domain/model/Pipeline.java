package com.study.playground.operator.pipeline.pipeline.domain.model;

import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 순수 도메인 모델 — 파이프라인 (TB_TPS_PP_001).
 */
@Getter
public class Pipeline {

    private String pipelineId;
    private String projectId;
    private String name;
    private String description;
    private boolean failContinue;
    private String inOutType;
    private boolean deleted;
    private LocalDateTime createdAt;
    private String createdBy;
    private LocalDateTime updatedAt;
    private String updatedBy;

    public static Pipeline create(
            String pipelineId
            , String projectId
            , String name
            , String description
            , String createdBy
    ) {
        var p = new Pipeline();
        p.pipelineId = pipelineId;
        p.projectId = projectId;
        p.name = name;
        p.description = description;
        p.failContinue = false;
        p.inOutType = "IN";
        p.deleted = false;
        p.createdAt = LocalDateTime.now();
        p.updatedAt = LocalDateTime.now();
        p.createdBy = createdBy;
        p.updatedBy = createdBy;
        return p;
    }

    public void update(String name, String description, boolean failContinue, String updatedBy) {
        this.name = name;
        this.description = description;
        this.failContinue = failContinue;
        this.updatedBy = updatedBy;
        this.updatedAt = LocalDateTime.now();
    }

    public void softDelete() {
        this.deleted = true;
        this.updatedAt = LocalDateTime.now();
    }

    protected Pipeline() {}
}
