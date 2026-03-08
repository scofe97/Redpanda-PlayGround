package com.study.playground.ticket.domain;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
public class Ticket {
    private Long id;
    private String name;
    private String description;
    private TicketStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
