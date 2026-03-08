package com.study.playground.adapter.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record NexusSearchResponse(
        List<NexusAsset> items
) {}
