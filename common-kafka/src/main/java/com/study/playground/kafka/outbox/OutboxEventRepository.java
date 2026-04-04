package com.study.playground.kafka.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * PENDING 상태이고 재시도 시간이 도래한 이벤트를 조회하면서 PROCESSING으로 마킹한다.
     * CTE로 SELECT FOR UPDATE SKIP LOCKED + UPDATE를 원자적으로 수행하여,
     * 조회 TX 커밋 후 다른 인스턴스가 같은 이벤트를 가져가는 것을 방지한다.
     */
    @Modifying
    @Query(value = "WITH target AS ("
            + " SELECT id FROM outbox_event"
            + " WHERE status = 'PENDING'"
            + " AND (next_retry_at IS NULL OR next_retry_at <= NOW())"
            + " ORDER BY created_at"
            + " LIMIT :limit"
            + " FOR UPDATE SKIP LOCKED"
            + ")"
            + " UPDATE outbox_event SET status = 'PROCESSING'"
            + " WHERE id IN (SELECT id FROM target)"
            + " RETURNING *"
            , nativeQuery = true)
    List<OutboxEvent> findAndMarkProcessing(@Param("limit") int limit);

    @Modifying
    @Query("UPDATE OutboxEvent e SET e.status = 'SENT', e.sentAt = CURRENT_TIMESTAMP WHERE e.id IN :ids")
    void batchMarkAsSent(@Param("ids") List<Long> ids);

    @Modifying
    @Query("UPDATE OutboxEvent e SET e.status = 'PENDING', e.retryCount = e.retryCount + 1, e.nextRetryAt = :nextRetryAt WHERE e.id = :id")
    void incrementRetryAndSetNextRetryAt(@Param("id") Long id
            , @Param("nextRetryAt") LocalDateTime nextRetryAt);

    @Modifying
    @Query("UPDATE OutboxEvent e SET e.status = 'DEAD' WHERE e.id = :id")
    void markAsDead(@Param("id") Long id);

    @Modifying
    @Query("UPDATE OutboxEvent e SET e.status = 'PENDING' WHERE e.id IN :ids AND e.status = 'PROCESSING'")
    void revertToPending(@Param("ids") List<Long> ids);

    @Modifying
    @Query("DELETE FROM OutboxEvent e WHERE e.status = 'SENT' AND e.sentAt < :before")
    void deleteOlderThan(@Param("before") LocalDateTime before);

    @Query("SELECT COUNT(e) FROM OutboxEvent e WHERE e.status = 'PENDING'")
    int countPending();
}
