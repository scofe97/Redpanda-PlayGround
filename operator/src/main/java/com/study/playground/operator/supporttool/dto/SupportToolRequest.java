package com.study.playground.operator.supporttool.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SupportToolRequest {
    @NotNull
    private String category;
    @NotNull
    private String implementation;
    @NotBlank
    private String name;
    @NotBlank
    private String url;
    private String authType;
    private String username;
    private String credential;
    private boolean active = true;
}
