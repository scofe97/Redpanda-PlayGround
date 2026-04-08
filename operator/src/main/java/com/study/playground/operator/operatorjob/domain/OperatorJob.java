package com.study.playground.operator.operatorjob.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "operator_job")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OperatorJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "execution_pipeline_id", length = 100)
    private String executionPipelineId;

    @Column(name = "job_id", nullable = false)
    private long jobId;

    @Column(name = "pipeline_id", length = 100)
    private String pipelineId;

    @Column(name = "job_name", nullable = false, length = 200)
    private String jobName;

    @Column(name = "job_order", nullable = false)
    private int jobOrder;

    @Column(name = "jenkins_instance_id", nullable = false)
    private long jenkinsInstanceId;

    @Column(name = "config_json", columnDefinition = "text")
    private String configJson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Setter
    private OperatorJobStatus status = OperatorJobStatus.PENDING;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public static OperatorJob create(
            String executionPipelineId
            , long jobId
            , String pipelineId
            , String jobName
            , int jobOrder
            , long jenkinsInstanceId
            , String configJson
    ) {
        OperatorJob job = new OperatorJob();
        job.executionPipelineId = executionPipelineId;
        job.jobId = jobId;
        job.pipelineId = pipelineId;
        job.jobName = jobName;
        job.jobOrder = jobOrder;
        job.jenkinsInstanceId = jenkinsInstanceId;
        job.configJson = configJson;
        job.status = OperatorJobStatus.PENDING;
        job.createdAt = LocalDateTime.now();
        job.updatedAt = LocalDateTime.now();
        return job;
    }

    public void updateStatus(OperatorJobStatus newStatus) {
        this.status = newStatus;
        this.updatedAt = LocalDateTime.now();
    }
}
