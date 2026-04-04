package com.study.playground.pipeline.pipeline.infrastructure.persistence;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "pipeline")
@Getter
@Setter
public class PipelineEntity {

    @Id
    @Column(name = "pipeline_id", length = 20)
    private String pipelineId;

    @Column(name = "project_id", nullable = false, length = 20)
    private String projectId;

    @Column(name = "pipeline_nm", nullable = false, length = 128)
    private String name;

    @Column(name = "pipeline_desc", length = 512)
    private String description;

    @Column(name = "fail_continue_yn", nullable = false, length = 1)
    private String failContinueYn = "N";

    @Column(name = "in_out_se", nullable = false, length = 1)
    private String inOutSe = "IN";

    @Column(name = "del_yn", nullable = false, length = 1)
    private String delYn = "N";

    @Column(name = "reg_dt", nullable = false, updatable = false)
    private LocalDateTime regDt;

    @Column(name = "rgtr_id", nullable = false, length = 10)
    private String rgtrId;

    @Column(name = "mdfcn_dt", nullable = false)
    private LocalDateTime mdfcnDt;

    @Column(name = "mdfr_id", nullable = false, length = 10)
    private String mdfrId;

    protected PipelineEntity() {}
}
