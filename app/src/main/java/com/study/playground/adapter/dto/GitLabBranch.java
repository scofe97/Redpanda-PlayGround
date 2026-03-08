package com.study.playground.adapter.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GitLabBranch(
        String name,
        @JsonProperty("merged") boolean merged,
        @JsonProperty("protected") boolean isProtected
) {}
