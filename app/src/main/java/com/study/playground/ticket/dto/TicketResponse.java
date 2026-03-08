package com.study.playground.ticket.dto;

import com.study.playground.ticket.domain.Ticket;
import com.study.playground.ticket.domain.TicketSource;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Builder
public class TicketResponse {
    private Long id;
    private String name;
    private String description;
    private String status;
    private List<TicketSourceResponse> sources;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static TicketResponse from(Ticket ticket, List<TicketSource> sources) {
        return TicketResponse.builder()
                .id(ticket.getId())
                .name(ticket.getName())
                .description(ticket.getDescription())
                .status(ticket.getStatus().name())
                .sources(sources != null ? sources.stream().map(TicketSourceResponse::from).toList() : List.of())
                .createdAt(ticket.getCreatedAt())
                .updatedAt(ticket.getUpdatedAt())
                .build();
    }
}
