package com.study.playground.supporttool.dto;

import com.study.playground.supporttool.domain.SupportTool;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class SupportToolResponse {
    private Long id;
    private String toolType;
    private String name;
    private String url;
    private String username;
    private String credential;
    private boolean active;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static SupportToolResponse from(SupportTool tool) {
        return SupportToolResponse.builder()
                .id(tool.getId())
                .toolType(tool.getToolType().name())
                .name(tool.getName())
                .url(tool.getUrl())
                .username(tool.getUsername())
                .credential(tool.getCredential() != null ? "****" : null)
                .active(tool.isActive())
                .createdAt(tool.getCreatedAt())
                .updatedAt(tool.getUpdatedAt())
                .build();
    }
}
