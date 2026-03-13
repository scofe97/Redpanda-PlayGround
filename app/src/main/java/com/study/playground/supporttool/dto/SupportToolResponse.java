package com.study.playground.supporttool.dto;

import com.study.playground.supporttool.domain.SupportTool;

import java.time.LocalDateTime;

public record SupportToolResponse(
        Long id,
        String toolType,
        String name,
        String url,
        String username,
        boolean hasCredential,
        boolean active,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static SupportToolResponse from(SupportTool tool) {
        return new SupportToolResponse(
                tool.getId()
                , tool.getToolType().name()
                , tool.getName()
                , tool.getUrl()
                , tool.getUsername()
                , tool.getCredential() != null && !tool.getCredential().isBlank()
                , tool.isActive()
                , tool.getCreatedAt()
                , tool.getUpdatedAt()
        );
    }
}
