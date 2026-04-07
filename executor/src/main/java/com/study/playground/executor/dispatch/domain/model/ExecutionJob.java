package com.study.playground.executor.dispatch.domain.model;

import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 순수 도메인 모델 — JPA 어노테이션 없음.
 * 상태 전이, 재시도, 빌드번호 채번 등 비즈니스 규칙을 캡슐화한다.
 */
@Getter
public class ExecutionJob {

    private String jobExcnId;
    private String pipelineExcnId;
    private String jobId;
    private Integer buildNo;
    private ExecutionJobStatus status;
    private int priority;
    private LocalDateTime priorityDt;
    private int retryCnt;
    private LocalDateTime bgngDt;
    private LocalDateTime endDt;
    private String logFileYn;
    private LocalDateTime regDt;
    private String rgtrId;
    private LocalDateTime mdfcnDt;
    private String mdfrId;
    private long version;


    // === Factory method ===
    public static ExecutionJob create(
            String jobExcnId
            , String pipelineExcnId
            , String jobId
            , int priority
            , LocalDateTime priorityDt
            , String rgtrId
    ) {
        ExecutionJob job = new ExecutionJob();
        job.jobExcnId = jobExcnId;
        job.pipelineExcnId = pipelineExcnId;
        job.jobId = jobId;
        job.priority = priority;
        job.priorityDt = priorityDt;
        job.status = ExecutionJobStatus.PENDING;
        job.retryCnt = 0;
        job.logFileYn = "N";
        job.regDt = LocalDateTime.now();
        job.mdfcnDt = LocalDateTime.now();
        job.rgtrId = rgtrId;
        job.mdfrId = rgtrId;
        return job;
    }

    /**
     * DB에서 로드한 값으로 도메인 모델을 복원한다.
     * create()와 달리 상태/시각을 그대로 주입하며, 검증을 수행하지 않는다.
     */
    public static ExecutionJob reconstitute(
            String jobExcnId
            , String pipelineExcnId
            , String jobId
            , Integer buildNo
            , ExecutionJobStatus status
            , int priority
            , LocalDateTime priorityDt
            , int retryCnt
            , LocalDateTime bgngDt
            , LocalDateTime endDt
            , String logFileYn
            , LocalDateTime regDt
            , String rgtrId
            , LocalDateTime mdfcnDt
            , String mdfrId
            , long version
    ) {
        var job = new ExecutionJob();
        job.jobExcnId = jobExcnId;
        job.pipelineExcnId = pipelineExcnId;
        job.jobId = jobId;
        job.buildNo = buildNo;
        job.status = status;
        job.priority = priority;
        job.priorityDt = priorityDt;
        job.retryCnt = retryCnt;
        job.bgngDt = bgngDt;
        job.endDt = endDt;
        job.logFileYn = logFileYn;
        job.regDt = regDt;
        job.rgtrId = rgtrId;
        job.mdfcnDt = mdfcnDt;
        job.mdfrId = mdfrId;
        job.version = version;
        return job;
    }


    // === Status transitions ===
    public void transitionTo(ExecutionJobStatus newStatus) {
        ExecutionJobStatus.validateTransition(this.status, newStatus);
        this.status = newStatus;
        this.mdfcnDt = LocalDateTime.now();

        if (newStatus == ExecutionJobStatus.RUNNING) {
            this.bgngDt = LocalDateTime.now();
        }
        if (newStatus.isTerminal()) {
            this.endDt = LocalDateTime.now();
        }
    }

    public void recordBuildNo(int buildNo) {
        this.buildNo = buildNo;
        this.mdfcnDt = LocalDateTime.now();
    }

    public void incrementRetry() {
        this.retryCnt++;
        this.mdfcnDt = LocalDateTime.now();
    }

    public void markLogFileUploaded() {
        this.logFileYn = "Y";
        this.mdfcnDt = LocalDateTime.now();
    }

    public boolean canRetry(int maxRetries) {
        return this.retryCnt < maxRetries;
    }

    protected ExecutionJob() {}
}
