package com.study.playground.supporttool.domain;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
public class SupportTool {
    private Long id;
    private ToolCategory category;
    private ToolImplementation implementation;
    private String name;
    private String url;
    private AuthType authType;
    private String username;
    private String credential;
    private boolean active;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
