package com.study.playground.pipeline.job.domain.model;

import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 순수 도메인 모델 — 통합작업관리 (TB_TPS_IJ_001).
 * JPA 어노테이션 없음.
 */
@Getter
public class Job {

    private String jobId;
    private String projectId;
    private String presetId;
    private JobCategory category;
    private JobType type;
    private boolean locked;
    private String tags;
    private String linkJobId;
    private boolean deleted;
    private LocalDateTime regDt;
    private String rgtrId;
    private LocalDateTime mdfcnDt;
    private String mdfrId;

    // === Factory ===

    public static Job create(
            String jobId
            , String projectId
            , String presetId
            , JobCategory category
            , JobType type
            , String rgtrId
    ) {
        var job = new Job();
        job.jobId = jobId;
        job.projectId = projectId;
        job.presetId = presetId;
        job.category = category;
        job.type = type;
        job.locked = false;
        job.deleted = false;
        job.regDt = LocalDateTime.now();
        job.mdfcnDt = LocalDateTime.now();
        job.rgtrId = rgtrId;
        job.mdfrId = rgtrId;
        return job;
    }

    // === 도메인 로직 ===

    public void lock() {
        this.locked = true;
        this.mdfcnDt = LocalDateTime.now();
    }

    public void unlock() {
        this.locked = false;
        this.mdfcnDt = LocalDateTime.now();
    }

    public void softDelete() {
        this.deleted = true;
        this.mdfcnDt = LocalDateTime.now();
    }

    public void linkTo(String linkJobId) {
        this.linkJobId = linkJobId;
        this.mdfcnDt = LocalDateTime.now();
    }

    public void setTags(String tags) { this.tags = tags; this.mdfcnDt = LocalDateTime.now(); }

    protected Job() {}
}
