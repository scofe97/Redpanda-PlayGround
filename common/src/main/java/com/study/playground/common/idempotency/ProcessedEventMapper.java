package com.study.playground.common.idempotency;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ProcessedEventMapper {
    boolean existsByEventId(@Param("eventId") String eventId);
    int insert(ProcessedEvent event);
}
