package com.moveinsync.roster.reconciliation;

import com.moveinsync.roster.domain.ReconciliationRun;
import java.util.Optional;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface ReconciliationRunRepository extends Repository<ReconciliationRun, Long> {

    ReconciliationRun save(ReconciliationRun run);

    Optional<ReconciliationRun> findByTenantIdAndId(Long tenantId, Long id);

    @Modifying
    @Query(value = """
            UPDATE reconciliation_run
               SET status = 'RUNNING', last_progress_at = CURRENT_TIMESTAMP(3)
             WHERE tenant_id = :tenantId AND id = :id AND status = 'AWAITING_APPROVAL'
            """, nativeQuery = true)
    int claimForResolution(@Param("tenantId") Long tenantId, @Param("id") Long id);

    @Modifying
    @Query(value = "UPDATE reconciliation_run SET last_progress_at = CURRENT_TIMESTAMP(3) WHERE tenant_id = :tenantId AND id = :id",
            nativeQuery = true)
    int heartbeat(@Param("tenantId") Long tenantId, @Param("id") Long id);

    @Modifying
    @Query(value = """
            UPDATE reconciliation_run
               SET status = 'FAILED', finished_at = CURRENT_TIMESTAMP(3), abort_reason = 'ABANDONED'
             WHERE tenant_id = :tenantId
               AND status = 'RUNNING'
               AND last_progress_at < TIMESTAMPADD(SECOND, -:staleSeconds, CURRENT_TIMESTAMP(3))
            """, nativeQuery = true)
    int failAbandoned(@Param("tenantId") Long tenantId, @Param("staleSeconds") long staleSeconds);
}
