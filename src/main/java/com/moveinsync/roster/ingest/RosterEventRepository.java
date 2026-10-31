package com.moveinsync.roster.ingest;

import com.moveinsync.roster.domain.RosterEvent;
import java.util.Optional;
import org.springframework.data.repository.Repository;

public interface RosterEventRepository extends Repository<RosterEvent, Long> {

    RosterEvent save(RosterEvent event);

    Optional<RosterEvent> findByTenantIdAndIdempotencyKey(Long tenantId, String idempotencyKey);
}
