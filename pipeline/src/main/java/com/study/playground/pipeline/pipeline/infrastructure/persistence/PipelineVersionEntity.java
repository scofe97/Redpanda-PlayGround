package com.study.playground.pipeline.pipeline.infrastructure.persistence;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "pipeline_ver", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"pipeline_id", "version"})
})
@Getter
@Setter
public class PipelineVersionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "pipeline_version_id")
    private Long versionId;

    @Column(name = "pipeline_id", nullable = false, length = 20)
    private String pipelineId;

    @Column(name = "version", nullable = false)
    private int version;

    @Column(name = "description", length = 512)
    private String description;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "created_by", length = 10)
    private String createdBy;

    protected PipelineVersionEntity() {}
}
