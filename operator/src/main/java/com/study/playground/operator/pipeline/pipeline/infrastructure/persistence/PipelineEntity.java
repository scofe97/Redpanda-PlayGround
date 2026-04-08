package com.study.playground.operator.pipeline.pipeline.infrastructure.persistence;

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

    @Column(name = "pipeline_name", nullable = false, length = 128)
    private String name;

    @Column(name = "pipeline_desc", length = 512)
    private String description;

    @Column(name = "fail_continue", nullable = false)
    private boolean failContinue = false;

    @Column(name = "in_out_type", nullable = false, length = 1)
    private String inOutType = "IN";

    @Column(name = "deleted", nullable = false)
    private boolean deleted = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "created_by", nullable = false, length = 10)
    private String createdBy;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "updated_by", nullable = false, length = 10)
    private String updatedBy;

    protected PipelineEntity() {}
}
