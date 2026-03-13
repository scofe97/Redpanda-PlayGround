package com.study.playground.ticket.dto;

import com.study.playground.ticket.domain.Ticket;

import java.time.LocalDateTime;

public record TicketListResponse(
        Long id,
        String name,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static TicketListResponse from(Ticket ticket) {
        return new TicketListResponse(
                ticket.getId()
                , ticket.getName()
                , ticket.getStatus().name()
                , ticket.getCreatedAt()
                , ticket.getUpdatedAt()
        );
    }
}
