package com.study.playground.operator.pipeline.job.domain.port.out;

import com.study.playground.operator.pipeline.job.domain.model.Job;

public interface SaveJobPort {

    Job save(Job job);
}
