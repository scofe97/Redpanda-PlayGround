package com.study.playground.supporttool.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SupportToolRequest {
    @NotNull
    private String toolType;
    @NotBlank
    private String name;
    @NotBlank
    private String url;
    private String username;
    private String credential;
    private boolean active = true;
}
