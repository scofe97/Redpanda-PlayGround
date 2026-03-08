package com.study.playground.supporttool.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SupportToolRequest {
    private String toolType;
    private String name;
    private String url;
    private String username;
    private String credential;
    private boolean active = true;
}
