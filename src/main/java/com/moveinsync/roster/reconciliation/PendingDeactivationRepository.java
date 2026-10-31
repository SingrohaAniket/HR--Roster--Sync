package com.moveinsync.roster.reconciliation;

import com.moveinsync.roster.domain.PendingDeactivation;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface PendingDeactivationRepository extends Repository<PendingDeactivation, Long> {

    PendingDeactivation save(PendingDeactivation pendingDeactivation);

    <S extends PendingDeactivation> List<S> saveAll(Iterable<S> pendingDeactivations);

    @Query("""
            SELECT p FROM PendingDeactivation p
             WHERE p.run.id = :runId
               AND p.run.tenantId = :tenantId
            """)
    List<PendingDeactivation> findByTenantIdAndRunId(@Param("tenantId") Long tenantId, @Param("runId") Long runId);
}
