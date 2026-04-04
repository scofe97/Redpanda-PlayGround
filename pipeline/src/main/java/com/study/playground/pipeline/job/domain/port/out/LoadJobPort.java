package com.study.playground.pipeline.job.domain.port.out;

import com.study.playground.pipeline.job.domain.model.Job;

import java.util.List;
import java.util.Optional;

public interface LoadJobPort {

    Optional<Job> findById(String jobId);

    List<Job> findByProjectId(String projectId);

    List<Job> findAll();
}
