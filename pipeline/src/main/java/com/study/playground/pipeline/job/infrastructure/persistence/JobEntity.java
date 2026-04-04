package com.study.playground.pipeline.job.infrastructure.persistence;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "job")
@Getter
@Setter
public class JobEntity {

    @Id
    @Column(name = "job_id", length = 20)
    private String jobId;

    @Column(name = "project_id", nullable = false, length = 50)
    private String projectId;

    @Column(name = "preset_id", nullable = false, length = 50)
    private String presetId;

    @Column(name = "job_ctgr_cd", nullable = false, length = 20)
    private String categoryCode;

    @Column(name = "job_type_cd", nullable = false, length = 20)
    private String typeCode;

    @Column(name = "job_lck_yn", nullable = false, length = 1)
    private String lockYn = "N";

    @Column(name = "job_tags", columnDefinition = "text")
    private String tags;

    @Column(name = "link_job_id", length = 20)
    private String linkJobId;

    @Column(name = "del_yn", nullable = false, length = 1)
    private String delYn = "N";

    @Column(name = "reg_dt", nullable = false, updatable = false)
    private LocalDateTime regDt;

    @Column(name = "rgtr_id", length = 50)
    private String rgtrId;

    @Column(name = "mdfcn_dt")
    private LocalDateTime mdfcnDt;

    @Column(name = "mdfr_id", length = 50)
    private String mdfrId;

    protected JobEntity() {}
}
