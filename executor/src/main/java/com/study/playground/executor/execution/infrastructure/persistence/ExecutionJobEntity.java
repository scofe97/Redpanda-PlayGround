package com.study.playground.executor.execution.infrastructure.persistence;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "execution_job", indexes = {
        @Index(name = "idx_ej_stts_priority", columnList = "excn_stts, priority, priority_dt")
        , @Index(name = "idx_ej_pipeline", columnList = "pipeline_excn_id, excn_stts")
})
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ExecutionJobEntity {

    @Id
    @Column(name = "job_excn_id", length = 50)
    private String jobExcnId;

    @Column(name = "pipeline_excn_id", length = 50)
    private String pipelineExcnId;

    @Column(name = "job_id", nullable = false, length = 50)
    private String jobId;

    @Column(name = "build_no")
    private Integer buildNo;

    @Column(name = "excn_stts", nullable = false, length = 16)
    private String status;

    @Column(nullable = false)
    private int priority;

    @Column(name = "priority_dt", nullable = false)
    private LocalDateTime priorityDt;

    @Column(name = "retry_cnt", nullable = false)
    private int retryCnt;

    @Column(name = "bgng_dt")
    private LocalDateTime bgngDt;

    @Column(name = "end_dt")
    private LocalDateTime endDt;

    @Column(name = "log_file_yn", nullable = false, columnDefinition = "bpchar")
    private String logFileYn;

    @Column(name = "reg_dt", nullable = false, updatable = false)
    private LocalDateTime regDt;

    @Column(name = "rgtr_id", length = 10)
    private String rgtrId;

    @Column(name = "mdfcn_dt", nullable = false)
    private LocalDateTime mdfcnDt;

    @Column(name = "mdfr_id", length = 10)
    private String mdfrId;

    @Version
    private long version;
}
