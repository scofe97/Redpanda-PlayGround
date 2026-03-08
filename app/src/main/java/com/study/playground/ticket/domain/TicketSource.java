package com.study.playground.ticket.domain;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
public class TicketSource {
    private Long id;
    private Long ticketId;
    private SourceType sourceType;
    private String repoUrl;
    private String branch;
    private String artifactCoordinate;
    private String imageName;
    private LocalDateTime createdAt;
}
