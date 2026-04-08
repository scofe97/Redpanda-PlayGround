package com.study.playground.operator.purpose.dto;

import com.study.playground.operator.purpose.domain.Purpose;
import com.study.playground.operator.purpose.domain.PurposeEntry;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public record PurposeResponse(
        Long id,
        String name,
        String description,
        Long projectId,
        List<EntryResponse> entries,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public record EntryResponse(
            Long id,
            String category,
            Long toolId,
            String toolName,
            String toolUrl,
            String toolImplementation
    ) {
        public static EntryResponse from(PurposeEntry entry, ToolInfo toolInfo) {
            return new EntryResponse(
                    entry.getId()
                    , entry.getCategory().name()
                    , entry.getToolId()
                    , toolInfo != null ? toolInfo.name() : null
                    , toolInfo != null ? toolInfo.url() : null
                    , toolInfo != null ? toolInfo.implementation() : null
            );
        }
    }

    /**
     * 도구 정보를 전달하기 위한 경량 레코드.
     * purpose 패키지가 supporttool 도메인에 직접 의존하지 않도록 분리.
     */
    public record ToolInfo(String name, String url, String implementation) {}

    public static PurposeResponse from(Purpose purpose, Map<Long, ToolInfo> toolInfoMap) {
        List<EntryResponse> entryResponses = purpose.getEntries() != null
                ? purpose.getEntries().stream()
                        .map(e -> EntryResponse.from(e, toolInfoMap.get(e.getToolId())))
                        .toList()
                : List.of();
        return new PurposeResponse(
                purpose.getId()
                , purpose.getName()
                , purpose.getDescription()
                , purpose.getProjectId()
                , entryResponses
                , purpose.getCreatedAt()
                , purpose.getUpdatedAt()
        );
    }
}
