package com.study.playground.pipeline.dag.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ParameterSchemaRequest {
    private String name;
    private String type;
    private String defaultValue;
    private boolean required;
}
