package com.study.playground.kafka.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * 각 aggregate의 "처리 가능한 맨 앞 이벤트(head)" id를 잠금과 함께 조회한다.
     *
     * <p>보장 규칙:
     * <ul>
     *   <li>동일 aggregate에 PROCESSING이 있으면 조회하지 않는다.</li>
     *   <li>동일 aggregate에 더 이른 PENDING/PROCESSING이 있으면 조회하지 않는다.</li>
     * </ul>
     *
     * <p>즉 aggregate별로 선행 이벤트가 비워진 "head"만 가져오므로,
     * 배치 경계를 넘어도 aggregate 내 순서 역전을 방지한다.
     */
    @Query(value = "SELECT oe.id FROM outbox_event oe"
            + " WHERE oe.status = 'PENDING'"
            + " AND (oe.next_retry_at IS NULL OR oe.next_retry_at <= NOW())"
            + " AND NOT EXISTS ("
            + "   SELECT 1 FROM outbox_event inflight"
            + "   WHERE inflight.aggregate_id = oe.aggregate_id"
            + "   AND inflight.status = 'PROCESSING'"
            + " )"
            + " AND NOT EXISTS ("
            + "   SELECT 1 FROM outbox_event prev"
            + "   WHERE prev.aggregate_id = oe.aggregate_id"
            + "   AND prev.status IN ('PENDING', 'PROCESSING')"
            + "   AND (prev.created_at < oe.created_at"
            + "        OR (prev.created_at = oe.created_at AND prev.id < oe.id))"
            + " )"
            + " ORDER BY oe.created_at, oe.id"
            + " LIMIT :limit"
            + " FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<Long> findHeadPendingIdsForProcessing(@Param("limit") int limit);

    @Modifying
    @Query("UPDATE OutboxEvent e SET e.status = 'PROCESSING' WHERE e.id IN :ids AND e.status = 'PENDING'")
    int markAsProcessingByIds(@Param("ids") List<Long> ids);

    @Query("SELECT e FROM OutboxEvent e WHERE e.id IN :ids ORDER BY e.createdAt ASC, e.id ASC")
    List<OutboxEvent> findAllByIdInOrderByCreatedAtAscIdAsc(@Param("ids") List<Long> ids);

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
