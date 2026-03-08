package com.study.playground.ticket.service;

import com.study.playground.avro.ticket.TicketCreatedEvent;
import com.study.playground.common.audit.AuditEventPublisher;
import com.study.playground.common.dto.CommonErrorCode;
import com.study.playground.common.exception.BusinessException;
import com.study.playground.common.outbox.EventPublisher;
import com.study.playground.kafka.serialization.AvroSerializer;
import com.study.playground.kafka.topic.Topics;
import com.study.playground.ticket.domain.Ticket;
import com.study.playground.ticket.domain.TicketSource;
import com.study.playground.ticket.domain.TicketStatus;
import com.study.playground.ticket.domain.SourceType;
import com.study.playground.ticket.dto.*;
import com.study.playground.ticket.mapper.TicketMapper;
import com.study.playground.ticket.mapper.TicketSourceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TicketService {

    private final TicketMapper ticketMapper;
    private final TicketSourceMapper ticketSourceMapper;
    private final EventPublisher eventPublisher;
    private final AuditEventPublisher auditEventPublisher;

    @Transactional(readOnly = true)
    public List<TicketListResponse> findAll() {
        return ticketMapper.findAll().stream()
                .map(TicketListResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public TicketResponse findById(Long id) {
        Ticket ticket = getTicketOrThrow(id);
        List<TicketSource> sources = ticketSourceMapper.findByTicketId(id);
        return TicketResponse.from(ticket, sources);
    }

    @Transactional
    public TicketResponse create(TicketCreateRequest request) {
        Ticket ticket = new Ticket();
        ticket.setName(request.getName());
        ticket.setDescription(request.getDescription());
        ticket.setStatus(TicketStatus.DRAFT);

        ticketMapper.insert(ticket);

        if (request.getSources() != null && !request.getSources().isEmpty()) {
            List<TicketSource> sources = request.getSources().stream()
                    .map(this::toTicketSource)
                    .toList();
            ticketSourceMapper.insertBatch(ticket.getId(), sources);
        }

        List<TicketSource> savedSources = ticketSourceMapper.findByTicketId(ticket.getId());

        // TicketCreatedEvent 발행
        List<com.study.playground.avro.common.SourceType> sourceTypeList = savedSources.stream()
                .map(s -> com.study.playground.avro.common.SourceType.valueOf(s.getSourceType().name()))
                .toList();

        String correlationId = UUID.randomUUID().toString();

        TicketCreatedEvent event = TicketCreatedEvent.newBuilder()
                .setTicketId(ticket.getId())
                .setName(ticket.getName())
                .setSourceTypes(sourceTypeList)
                .build();

        eventPublisher.publish("TICKET", String.valueOf(ticket.getId()),
                "TICKET_CREATED", AvroSerializer.serialize(event),
                Topics.TICKET_EVENTS, correlationId);

        // Audit 이벤트 발행
        auditEventPublisher.publish("system", "CREATE", "TICKET",
                String.valueOf(ticket.getId()), ticket.getName());

        return TicketResponse.from(ticket, savedSources);
    }

    @Transactional
    public TicketResponse update(Long id, TicketCreateRequest request) {
        Ticket ticket = getTicketOrThrow(id);
        validateModifiable(ticket);

        ticket.setName(request.getName());
        ticket.setDescription(request.getDescription());

        ticketMapper.update(ticket);

        ticketSourceMapper.deleteByTicketId(id);
        if (request.getSources() != null && !request.getSources().isEmpty()) {
            List<TicketSource> sources = request.getSources().stream()
                    .map(this::toTicketSource)
                    .toList();
            ticketSourceMapper.insertBatch(id, sources);
        }

        List<TicketSource> savedSources = ticketSourceMapper.findByTicketId(id);
        return TicketResponse.from(ticket, savedSources);
    }

    @Transactional
    public void delete(Long id) {
        Ticket ticket = getTicketOrThrow(id);
        validateModifiable(ticket);
        ticketMapper.deleteById(id);

        // Audit 이벤트 발행
        auditEventPublisher.publish("system", "DELETE", "TICKET",
                String.valueOf(id), null);
    }

    private Ticket getTicketOrThrow(Long id) {
        Ticket ticket = ticketMapper.findById(id);
        if (ticket == null) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND,
                    "티켓을 찾을 수 없습니다: " + id);
        }
        return ticket;
    }

    private void validateModifiable(Ticket ticket) {
        if (ticket.getStatus() == TicketStatus.DEPLOYING) {
            throw new BusinessException(CommonErrorCode.INVALID_STATE,
                    "배포 중인 티켓은 수정할 수 없습니다: " + ticket.getId());
        }
    }

    private TicketSource toTicketSource(TicketSourceRequest req) {
        TicketSource source = new TicketSource();
        String validTypes = Arrays.stream(SourceType.values())
                .map(Enum::name)
                .collect(Collectors.joining(", "));
        try {
            source.setSourceType(SourceType.valueOf(req.getSourceType()));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT,
                    "유효하지 않은 소스 타입입니다: " + req.getSourceType() + " (허용: " + validTypes + ")");
        }
        source.setRepoUrl(req.getRepoUrl());
        source.setBranch(req.getBranch());
        source.setArtifactCoordinate(req.getArtifactCoordinate());
        source.setImageName(req.getImageName());
        return source;
    }
}
