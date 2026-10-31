package com.moveinsync.roster.outbox;

import com.moveinsync.roster.domain.OutboxMessage;
import com.moveinsync.roster.domain.OutboxStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface OutboxRepository extends Repository<OutboxMessage, Long> {

    OutboxMessage save(OutboxMessage message);

    @Query(value = """
            SELECT * FROM outbox
             WHERE status = 'PENDING' AND next_attempt_at <= CURRENT_TIMESTAMP(3)
             ORDER BY next_attempt_at, id
             LIMIT :batchSize
             FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxMessage> claimBatch(@Param("batchSize") int batchSize);

    @Modifying
    @Query(value = "UPDATE outbox SET next_attempt_at = TIMESTAMPADD(SECOND, :leaseSeconds, CURRENT_TIMESTAMP(3)) WHERE id IN (:ids)",
            nativeQuery = true)
    int extendLease(@Param("ids") Collection<Long> ids, @Param("leaseSeconds") int leaseSeconds);

    @Modifying
    @Query(value = "UPDATE outbox SET status = 'PUBLISHED', published_at = UTC_TIMESTAMP(3) WHERE id IN (:ids) AND status <> 'PUBLISHED'",
            nativeQuery = true)
    int markPublished(@Param("ids") Collection<Long> ids);

    @Modifying
    @Query("""
            UPDATE OutboxMessage o
               SET o.attempts = o.attempts + 1, o.nextAttemptAt = :nextAttemptAt
             WHERE o.id = :id
               AND o.status = com.moveinsync.roster.domain.OutboxStatus.PENDING
            """)
    int markRetry(@Param("id") Long id, @Param("nextAttemptAt") Instant nextAttemptAt);

    @Modifying
    @Query("""
            UPDATE OutboxMessage o
               SET o.attempts = o.attempts + 1, o.status = com.moveinsync.roster.domain.OutboxStatus.FAILED
             WHERE o.id = :id
               AND o.status = com.moveinsync.roster.domain.OutboxStatus.PENDING
            """)
    int markFailed(@Param("id") Long id);

    @Modifying
    @Query(value = """
            UPDATE outbox
               SET status = 'PENDING', attempts = 0, next_attempt_at = CURRENT_TIMESTAMP(3)
             WHERE tenant_id = :tenantId AND status = 'FAILED'
            """, nativeQuery = true)
    int requeueFailed(@Param("tenantId") Long tenantId);

    long countByStatus(OutboxStatus status);

    @Query("""
            SELECT MIN(o.createdAt) FROM OutboxMessage o
             WHERE o.status = com.moveinsync.roster.domain.OutboxStatus.PENDING
            """)
    Optional<Instant> findOldestPendingCreatedAt();
}
