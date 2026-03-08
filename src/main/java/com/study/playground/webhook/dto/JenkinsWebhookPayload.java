package com.study.playground.webhook.dto;

public record JenkinsWebhookPayload(
        String executionId,
        Integer stepOrder,
        String jobName,
        int buildNumber,
        String result,
        long duration,
        String url
) {}
