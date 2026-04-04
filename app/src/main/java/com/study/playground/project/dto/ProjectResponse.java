package com.study.playground.project.dto;

import com.study.playground.project.domain.Project;

import java.time.LocalDateTime;

public record ProjectResponse(
        Long id,
        String name,
        String description,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static ProjectResponse from(Project p) {
        return new ProjectResponse(
                p.getId()
                , p.getName()
                , p.getDescription()
                , p.getCreatedAt()
                , p.getUpdatedAt()
        );
    }
}
