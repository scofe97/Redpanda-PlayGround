package com.study.playground.operator.pipeline.pipeline.infrastructure.persistence;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "pipeline_step", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"pipeline_version_id", "step_order", "job_id"})
})
@Getter
@Setter
public class PipelineStepEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "pipeline_step_id")
    private Long stepId;

    @Column(name = "pipeline_version_id", nullable = false)
    private Long versionId;

    @Column(name = "step_order", nullable = false)
    private int stepOrder;

    @Column(name = "job_id", nullable = false, length = 20)
    private String jobId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "created_by", length = 10)
    private String createdBy;

    protected PipelineStepEntity() {}
}
