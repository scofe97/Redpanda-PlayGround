package com.study.playground.pipeline.adapter;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record JenkinsBuildInfo(
        int number,
        String result,
        String url,
        long duration,
        long timestamp,
        boolean building
) {}
