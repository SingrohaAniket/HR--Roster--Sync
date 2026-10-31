package com.moveinsync.roster.outbox;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/outbox")
public class OutboxAdminController {

    private final OutboxAdminService outboxAdmin;

    public OutboxAdminController(OutboxAdminService outboxAdmin) {
        this.outboxAdmin = outboxAdmin;
    }

    @PostMapping("/failed/requeue")
    @PreAuthorize("hasRole('OPERATOR')")
    public RequeueResponse requeueFailed() {
        return new RequeueResponse(outboxAdmin.requeueFailed());
    }

    public record RequeueResponse(int requeued) {
    }
}
