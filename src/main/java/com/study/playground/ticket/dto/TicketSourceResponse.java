package com.study.playground.ticket.dto;

import com.study.playground.ticket.domain.TicketSource;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class TicketSourceResponse {
    private Long id;
    private String sourceType;
    private String repoUrl;
    private String branch;
    private String artifactCoordinate;
    private String imageName;

    public static TicketSourceResponse from(TicketSource source) {
        return TicketSourceResponse.builder()
                .id(source.getId())
                .sourceType(source.getSourceType().name())
                .repoUrl(source.getRepoUrl())
                .branch(source.getBranch())
                .artifactCoordinate(source.getArtifactCoordinate())
                .imageName(source.getImageName())
                .build();
    }
}
