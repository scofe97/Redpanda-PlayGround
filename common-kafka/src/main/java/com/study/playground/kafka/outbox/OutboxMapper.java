package com.study.playground.kafka.outbox;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface OutboxMapper {
    void insert(OutboxEvent event);

    List<OutboxEvent> findPendingEvents(@Param("limit") int limit);

    void batchMarkAsSent(@Param("ids") List<Long> ids);

    void incrementRetryAndSetNextRetryAt(@Param("id") Long id
            , @Param("nextRetryAt") LocalDateTime nextRetryAt);

    void markAsDead(@Param("id") Long id);

    void deleteOlderThan(@Param("before") LocalDateTime before);

    int countPending();
}
