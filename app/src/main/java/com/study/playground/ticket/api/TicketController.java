package com.study.playground.ticket.api;

import com.study.playground.common.dto.ApiResponse;
import com.study.playground.ticket.dto.TicketCreateRequest;
import com.study.playground.ticket.dto.TicketListResponse;
import com.study.playground.ticket.dto.TicketResponse;
import com.study.playground.ticket.service.TicketService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/tickets")
@RequiredArgsConstructor
public class TicketController {

    private final TicketService ticketService;

    @GetMapping
    public ApiResponse<List<TicketListResponse>> findAll() {
        return ApiResponse.success(ticketService.findAll());
    }

    @GetMapping("/{id}")
    public ApiResponse<TicketResponse> findById(@PathVariable Long id) {
        return ApiResponse.success(ticketService.findById(id));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<TicketResponse>> create(@Valid @RequestBody TicketCreateRequest request) {
        TicketResponse response = ticketService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }

    @PutMapping("/{id}")
    public ApiResponse<TicketResponse> update(@PathVariable Long id, @Valid @RequestBody TicketCreateRequest request) {
        return ApiResponse.success(ticketService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        ticketService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
