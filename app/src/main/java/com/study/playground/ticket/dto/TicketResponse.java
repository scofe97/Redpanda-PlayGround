package com.study.playground.ticket.dto;

import com.study.playground.ticket.domain.Ticket;
import com.study.playground.ticket.domain.TicketSource;

import java.time.LocalDateTime;
import java.util.List;

public record TicketResponse(
        Long id,
        String name,
        String description,
        String status,
        List<TicketSourceResponse> sources,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static TicketResponse from(Ticket ticket, List<TicketSource> sources) {
        return new TicketResponse(
                ticket.getId()
                , ticket.getName()
                , ticket.getDescription()
                , ticket.getStatus().name()
                , sources != null ? sources.stream().map(TicketSourceResponse::from).toList() : List.of()
                , ticket.getCreatedAt()
                , ticket.getUpdatedAt()
        );
    }
}
