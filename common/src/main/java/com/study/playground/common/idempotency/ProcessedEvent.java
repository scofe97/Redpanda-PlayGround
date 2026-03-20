package com.study.playground.common.idempotency;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
public class ProcessedEvent {
    private String eventId;
    private LocalDateTime processedAt;
}
