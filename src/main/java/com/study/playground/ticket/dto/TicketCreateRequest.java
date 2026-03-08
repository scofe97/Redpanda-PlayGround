package com.study.playground.ticket.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class TicketCreateRequest {

    @NotBlank(message = "티켓명은 필수입니다")
    @Size(max = 200, message = "티켓명은 200자 이하입니다")
    private String name;

    private String description;

    @Valid
    private List<TicketSourceRequest> sources;
}
