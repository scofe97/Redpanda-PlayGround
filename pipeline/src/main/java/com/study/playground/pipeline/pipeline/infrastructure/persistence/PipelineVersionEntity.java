package com.study.playground.pipeline.pipeline.infrastructure.persistence;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "pipeline_ver", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"pipeline_id", "pl_ver"})
})
@Getter
@Setter
public class PipelineVersionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "pipeline_ver_id")
    private Long versionId;

    @Column(name = "pipeline_id", nullable = false, length = 20)
    private String pipelineId;

    @Column(name = "pl_ver", nullable = false)
    private int version;

    @Column(name = "pl_ver_desc", length = 512)
    private String description;

    @Column(name = "reg_dt", nullable = false, updatable = false)
    private LocalDateTime regDt;

    @Column(name = "rgtr_id", length = 10)
    private String rgtrId;

    protected PipelineVersionEntity() {}
}
