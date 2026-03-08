package com.study.playground.adapter.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record NexusAsset(
        String downloadUrl,
        String path,
        String repository,
        @JsonProperty("maven2") NexusMavenInfo maven2
) {}
