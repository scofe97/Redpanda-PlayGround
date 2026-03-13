package com.study.playground.ticket.dto;

import com.study.playground.ticket.domain.TicketSource;

public record TicketSourceResponse(
        Long id,
        String sourceType,
        String repoUrl,
        String branch,
        String artifactCoordinate,
        String imageName
) {
    public static TicketSourceResponse from(TicketSource source) {
        return new TicketSourceResponse(
                source.getId()
                , source.getSourceType().name()
                , source.getRepoUrl()
                , source.getBranch()
                , source.getArtifactCoordinate()
                , source.getImageName()
        );
    }
}
