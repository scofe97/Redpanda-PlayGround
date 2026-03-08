package com.study.playground.connector.domain;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
public class ConnectorConfig {
    private Long id;
    private String streamId;
    private Long toolId;
    private String yamlConfig;
    private String direction;
    private LocalDateTime createdAt;
}
