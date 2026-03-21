package com.study.playground.pipeline.dag.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.Map;

@Getter
@Setter
public class PipelineExecuteRequest {
    private Map<String, String> params;
}
