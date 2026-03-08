package com.study.playground.common.idempotency;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ProcessedEventMapper {
    boolean existsByCorrelationIdAndEventType(
            @Param("correlationId") String correlationId,
            @Param("eventType") String eventType);
    int insert(ProcessedEvent event);
}
