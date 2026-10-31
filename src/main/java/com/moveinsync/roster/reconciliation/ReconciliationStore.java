package com.moveinsync.roster.reconciliation;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
class ReconciliationStore {

    private final JdbcTemplate jdbc;

    ReconciliationStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    record ParkedCandidate(long id, long employeeId, Instant hrUpdatedAt) {
    }

    void park(long runId, List<Long> employeeIds) {
        if (!employeeIds.isEmpty()) {
            jdbc.batchUpdate("INSERT INTO reconciliation_pending_deactivation (run_id, employee_id) VALUES (?, ?)",
                    employeeIds, employeeIds.size(), (ps, employeeId) -> {
                        ps.setLong(1, runId);
                        ps.setLong(2, employeeId);
                    });
        }
    }

    List<ParkedCandidate> page(long runId, long afterId, int limit) {
        return jdbc.query("""
                SELECT p.id, p.employee_id, e.hr_updated_at
                  FROM reconciliation_pending_deactivation p
                  JOIN employee e ON e.id = p.employee_id
                 WHERE p.run_id = ? AND p.id > ?
                 ORDER BY p.id
                 LIMIT ?
                """, (rs, n) -> {
            LocalDateTime hrUpdatedAt = rs.getObject("hr_updated_at", LocalDateTime.class);
            return new ParkedCandidate(rs.getLong("id"), rs.getLong("employee_id"),
                    hrUpdatedAt == null ? null : hrUpdatedAt.toInstant(ZoneOffset.UTC));
        }, runId, afterId, limit);
    }

    void discard(long runId) {
        jdbc.update("DELETE FROM reconciliation_pending_deactivation WHERE run_id = ?", runId);
    }
}
