package com.study.playground.pipeline.pipeline.domain.port.out;

import com.study.playground.pipeline.pipeline.domain.model.Pipeline;
import com.study.playground.pipeline.pipeline.domain.model.PipelineVersion;

public interface SavePipelinePort {

    Pipeline save(Pipeline pipeline);

    PipelineVersion saveVersion(PipelineVersion version);
}
