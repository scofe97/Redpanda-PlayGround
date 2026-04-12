package com.study.playground.operator.supporttool.dto;

import com.study.playground.operator.supporttool.domain.SupportTool;

import java.time.LocalDateTime;

public record SupportToolResponse(
        Long id,
        String category,
        String implementation,
        String name,
        String url,
        String authType,
        String username,
        boolean hasCredential,
        boolean hasApiToken,
        String healthStatus,
        LocalDateTime healthCheckedAt,
        boolean active,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static SupportToolResponse from(SupportTool tool) {
        return new SupportToolResponse(
                tool.getId()
                , tool.getCategory() != null ? tool.getCategory().name() : null
                , tool.getImplementation() != null ? tool.getImplementation().name() : null
                , tool.getName()
                , tool.getUrl()
                , tool.getAuthType() != null ? tool.getAuthType().name() : null
                , tool.getUsername()
                , tool.getCredential() != null && !tool.getCredential().isBlank()
                , tool.getApiToken() != null && !tool.getApiToken().isBlank()
                , tool.getHealthStatus() != null ? tool.getHealthStatus().name() : null
                , tool.getHealthCheckedAt()
                , tool.isActive()
                , tool.getCreatedAt()
                , tool.getUpdatedAt()
        );
    }
}
