package com.study.playground.ticket.dto;

import com.study.playground.ticket.domain.Ticket;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class TicketListResponse {
    private Long id;
    private String name;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static TicketListResponse from(Ticket ticket) {
        return TicketListResponse.builder()
                .id(ticket.getId())
                .name(ticket.getName())
                .status(ticket.getStatus().name())
                .createdAt(ticket.getCreatedAt())
                .updatedAt(ticket.getUpdatedAt())
                .build();
    }
}
