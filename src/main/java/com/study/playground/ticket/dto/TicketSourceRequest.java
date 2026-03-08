package com.study.playground.ticket.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class TicketSourceRequest {

    @NotNull(message = "소스 유형은 필수입니다")
    private String sourceType;

    private String repoUrl;
    private String branch;
    private String artifactCoordinate;
    private String imageName;
}
