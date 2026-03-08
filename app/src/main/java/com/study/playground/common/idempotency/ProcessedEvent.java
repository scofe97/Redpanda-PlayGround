package com.study.playground.common.idempotency;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
public class ProcessedEvent {
    private String correlationId;
    private String eventType;
    private LocalDateTime processedAt;
}
