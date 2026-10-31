package com.moveinsync.roster.outbox;

import com.moveinsync.roster.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OutboxAdminService {

    private static final Logger log = LoggerFactory.getLogger(OutboxAdminService.class);

    private final OutboxRepository outbox;

    public OutboxAdminService(OutboxRepository outbox) {
        this.outbox = outbox;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public int requeueFailed() {
        long tenantId = TenantContext.require();
        int moved = outbox.requeueFailed(tenantId);
        log.info("Operator requeued {} FAILED outbox row(s) for tenant {}", moved, tenantId);
        return moved;
    }
}
