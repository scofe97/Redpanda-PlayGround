package com.study.playground.adapter.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GitLabProject(
        Long id,
        String name,
        @JsonProperty("name_with_namespace") String nameWithNamespace,
        @JsonProperty("web_url") String webUrl,
        @JsonProperty("default_branch") String defaultBranch
) {}
