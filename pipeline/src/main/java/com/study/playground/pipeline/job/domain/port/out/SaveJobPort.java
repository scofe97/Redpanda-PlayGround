package com.study.playground.pipeline.job.domain.port.out;

import com.study.playground.pipeline.job.domain.model.Job;

public interface SaveJobPort {

    Job save(Job job);
}
