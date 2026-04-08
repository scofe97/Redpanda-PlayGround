package com.study.playground.operator.pipeline.job.domain.service;

import com.study.playground.operator.pipeline.job.domain.model.Job;

public class JobService {

    public void validateDeletable(Job job) {
        if (job.isLocked()) {
            throw new IllegalStateException("Job is locked (running): " + job.getJobId());
        }
        if (job.isDeleted()) {
            throw new IllegalStateException("Job already deleted: " + job.getJobId());
        }
    }
}
