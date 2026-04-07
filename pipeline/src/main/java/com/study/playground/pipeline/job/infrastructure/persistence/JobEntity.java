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

    @Column(name = "category", nullable = false, length = 20)
    private String categoryCode;

    @Column(name = "type", nullable = false, length = 20)
    private String typeCode;

    @Column(name = "locked", nullable = false)
    private boolean locked = false;

    @Column(name = "job_tags", columnDefinition = "text")
    private String tags;

    @Column(name = "link_job_id", length = 20)
    private String linkJobId;

    @Column(name = "deleted", nullable = false)
    private boolean deleted = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "created_by", length = 50)
    private String createdBy;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "updated_by", length = 50)
    private String updatedBy;

    protected JobEntity() {}
}
