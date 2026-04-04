package com.study.playground.pipeline.pipeline.domain.model;

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
    private String inOutSe;
    private boolean deleted;
    private LocalDateTime regDt;
    private String rgtrId;
    private LocalDateTime mdfcnDt;
    private String mdfrId;

    public static Pipeline create(
            String pipelineId
            , String projectId
            , String name
            , String description
            , String rgtrId
    ) {
        var p = new Pipeline();
        p.pipelineId = pipelineId;
        p.projectId = projectId;
        p.name = name;
        p.description = description;
        p.failContinue = false;
        p.inOutSe = "IN";
        p.deleted = false;
        p.regDt = LocalDateTime.now();
        p.mdfcnDt = LocalDateTime.now();
        p.rgtrId = rgtrId;
        p.mdfrId = rgtrId;
        return p;
    }

    public void update(String name, String description, boolean failContinue, String mdfrId) {
        this.name = name;
        this.description = description;
        this.failContinue = failContinue;
        this.mdfrId = mdfrId;
        this.mdfcnDt = LocalDateTime.now();
    }

    public void softDelete() {
        this.deleted = true;
        this.mdfcnDt = LocalDateTime.now();
    }

    protected Pipeline() {}
}
