package com.study.playground.pipeline.pipeline.infrastructure.persistence;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "pipeline_step", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"pipeline_ver_id", "step_seq", "job_id"})
})
@Getter
@Setter
public class PipelineStepEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "pipeline_step_id")
    private Long stepId;

    @Column(name = "pipeline_ver_id", nullable = false)
    private Long versionId;

    @Column(name = "step_seq", nullable = false)
    private int seq;

    @Column(name = "job_id", nullable = false, length = 20)
    private String jobId;

    @Column(name = "reg_dt", nullable = false, updatable = false)
    private LocalDateTime regDt;

    @Column(name = "rgtr_id", length = 10)
    private String rgtrId;

    protected PipelineStepEntity() {}
}
