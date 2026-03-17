package com.study.playground.preset.dto;

import com.study.playground.preset.domain.MiddlewarePreset;
import com.study.playground.preset.domain.PresetEntry;

import java.time.LocalDateTime;
import java.util.List;

public record PresetResponse(
        Long id,
        String name,
        String description,
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
        public static EntryResponse from(PresetEntry entry) {
            return new EntryResponse(
                    entry.getId()
                    , entry.getCategory().name()
                    , entry.getToolId()
                    , entry.getToolName()
                    , entry.getToolUrl()
                    , entry.getToolImplementation() != null
                            ? entry.getToolImplementation().name()
                            : null
            );
        }
    }

    public static PresetResponse from(MiddlewarePreset preset) {
        List<EntryResponse> entryResponses = preset.getEntries() != null
                ? preset.getEntries().stream().map(EntryResponse::from).toList()
                : List.of();
        return new PresetResponse(
                preset.getId()
                , preset.getName()
                , preset.getDescription()
                , entryResponses
                , preset.getCreatedAt()
                , preset.getUpdatedAt()
        );
    }
}
