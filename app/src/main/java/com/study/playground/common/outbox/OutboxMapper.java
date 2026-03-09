package com.study.playground.common.outbox;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface OutboxMapper {
    void insert(OutboxEvent event);

    List<OutboxEvent> findPendingEvents(@Param("limit") int limit);

    void markAsSent(@Param("id") Long id);

    void incrementRetryCount(@Param("id") Long id);

    void markAsDead(@Param("id") Long id);

    void deleteOlderThan(@Param("before") LocalDateTime before);
}
