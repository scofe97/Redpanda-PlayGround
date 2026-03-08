package com.study.playground.adapter.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record NexusMavenInfo(
        String groupId,
        String artifactId,
        String version,
        String extension
) {}
