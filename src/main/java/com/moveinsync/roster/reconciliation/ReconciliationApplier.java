package com.moveinsync.roster.reconciliation;

import com.moveinsync.roster.core.hashing.BucketAssigner;
import com.moveinsync.roster.core.ownership.FieldKey;
import com.moveinsync.roster.core.reconciliation.ExportRow;
import com.moveinsync.roster.outbox.OutboxEventType;
import com.moveinsync.roster.outbox.OutboxWriter;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Component
class ReconciliationApplier {

    private final JdbcTemplate jdbc;
    private final OutboxWriter outboxWriter;

    ReconciliationApplier(JdbcTemplate jdbc, OutboxWriter outboxWriter) {
        this.jdbc = jdbc;
        this.outboxWriter = outboxWriter;
    }

    record ChunkResult(int created, int updated, int reactivated, int skipped) {
    }

    private record Current(long hrVersion, boolean active, Instant hrUpdatedAt, String name, String email, String phone,
                           String manager, Long officeId, String shift) {
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ChunkResult applyChunk(long tenantId, Instant generatedAt, List<ExportRow> rows, Map<String, Long> officeIds, int bucketCount) {
        Instant snapshot = generatedAt.truncatedTo(ChronoUnit.MILLIS);
        Map<String, Current> current = lockCurrent(tenantId, rows);
        List<ExportRow> inserts = new ArrayList<>();
        List<ExportRow> updates = new ArrayList<>();
        List<OutboxWriter.BatchEvent> events = new ArrayList<>();
        int reactivated = 0;
        int skipped = 0;
        for (ExportRow row : rows) {
            Current existing = current.get(row.externalId());
            if (existing == null) {
                inserts.add(row);
                events.add(new OutboxWriter.BatchEvent(tenantId, row.externalId(), OutboxEventType.EMPLOYEE_CREATED, allFields(row), snapshot, 0));
            } else if (existing.hrUpdatedAt() != null && existing.hrUpdatedAt().isAfter(generatedAt)) {
                skipped++;
            } else {
                updates.add(row);
                Set<FieldKey> changed = changedFields(existing, row, officeIds.get(row.officeCode()));
                if (!existing.active()) {
                    reactivated++;
                    changed.add(FieldKey.STATUS);
                }
                events.add(new OutboxWriter.BatchEvent(tenantId, row.externalId(), OutboxEventType.EMPLOYEE_UPDATED, changed, snapshot, existing.hrVersion()));
            }
        }
        insert(tenantId, snapshot, inserts, officeIds, bucketCount);
        update(tenantId, snapshot, updates, officeIds);
        outboxWriter.writeBatch(events);
        return new ChunkResult(inserts.size(), updates.size() - reactivated, reactivated, skipped);
    }

    private Map<String, Current> lockCurrent(long tenantId, List<ExportRow> rows) {
        String placeholders = String.join(",", Collections.nCopies(rows.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        rows.forEach(row -> args.add(row.externalId()));
        Map<String, Current> current = new HashMap<>();
        jdbc.query("SELECT external_id, hr_version, status, hr_updated_at, name, email, phone, manager_external_id, office_id, shift_code"
                        + " FROM employee WHERE tenant_id = ? AND external_id IN (" + placeholders + ") ORDER BY id FOR UPDATE",
                rs -> {
                    current.put(rs.getString("external_id"), readCurrent(rs));
                }, args.toArray());
        return current;
    }

    private static Current readCurrent(ResultSet rs) throws SQLException {
        LocalDateTime hrUpdatedAt = rs.getObject("hr_updated_at", LocalDateTime.class);
        long officeId = rs.getLong("office_id");
        Long office = rs.wasNull() ? null : officeId;
        return new Current(rs.getLong("hr_version"), "ACTIVE".equals(rs.getString("status")),
                hrUpdatedAt == null ? null : hrUpdatedAt.toInstant(ZoneOffset.UTC),
                rs.getString("name"), rs.getString("email"), rs.getString("phone"), rs.getString("manager_external_id"),
                office, rs.getString("shift_code"));
    }

    private void insert(long tenantId, Instant snapshot, List<ExportRow> rows, Map<String, Long> officeIds, int bucketCount) {
        if (rows.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("""
                INSERT INTO employee (tenant_id, external_id, name, email, phone, manager_external_id, office_id, shift_code,
                                      status, hr_version, hr_updated_at, content_hash, bucket)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', 0, ?, ?, ?)
                """, rows, rows.size(), (ps, row) -> {
            ps.setLong(1, tenantId);
            ps.setString(2, row.externalId());
            ps.setString(3, row.name());
            ps.setString(4, row.email());
            ps.setString(5, row.phone());
            ps.setString(6, row.managerExternalId());
            setOffice(ps, 7, officeIds.get(row.officeCode()));
            ps.setString(8, row.shiftCode());
            ps.setObject(9, LocalDateTime.ofInstant(snapshot, ZoneOffset.UTC));
            ps.setString(10, row.contentHash());
            ps.setInt(11, BucketAssigner.bucket(row.externalId(), bucketCount));
        });
    }

    private void update(long tenantId, Instant snapshot, List<ExportRow> rows, Map<String, Long> officeIds) {
        if (rows.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("""
                UPDATE employee
                   SET name = ?, email = ?, phone = ?, manager_external_id = ?, office_id = ?, shift_code = ?,
                       status = 'ACTIVE', deactivated_at = NULL, hr_updated_at = ?, content_hash = ?
                 WHERE tenant_id = ? AND external_id = ?
                """, rows, rows.size(), (ps, row) -> {
            ps.setString(1, row.name());
            ps.setString(2, row.email());
            ps.setString(3, row.phone());
            ps.setString(4, row.managerExternalId());
            setOffice(ps, 5, officeIds.get(row.officeCode()));
            ps.setString(6, row.shiftCode());
            ps.setObject(7, LocalDateTime.ofInstant(snapshot, ZoneOffset.UTC));
            ps.setString(8, row.contentHash());
            ps.setLong(9, tenantId);
            ps.setString(10, row.externalId());
        });
    }

    private static void setOffice(PreparedStatement ps, int index, Long officeId) throws SQLException {
        if (officeId == null) {
            ps.setNull(index, Types.BIGINT);
        } else {
            ps.setLong(index, officeId);
        }
    }

    private static Set<FieldKey> allFields(ExportRow row) {
        Set<FieldKey> fields = EnumSet.noneOf(FieldKey.class);
        addIfDifferent(fields, FieldKey.NAME, null, row.name());
        addIfDifferent(fields, FieldKey.EMAIL, null, row.email());
        addIfDifferent(fields, FieldKey.PHONE, null, row.phone());
        addIfDifferent(fields, FieldKey.MANAGER_EXTERNAL_ID, null, row.managerExternalId());
        addIfDifferent(fields, FieldKey.OFFICE_CODE, null, row.officeCode());
        addIfDifferent(fields, FieldKey.SHIFT_CODE, null, row.shiftCode());
        return fields;
    }

    private static Set<FieldKey> changedFields(Current existing, ExportRow row, Long officeId) {
        Set<FieldKey> changed = EnumSet.noneOf(FieldKey.class);
        addIfDifferent(changed, FieldKey.NAME, existing.name(), row.name());
        addIfDifferent(changed, FieldKey.EMAIL, existing.email(), row.email());
        addIfDifferent(changed, FieldKey.PHONE, existing.phone(), row.phone());
        addIfDifferent(changed, FieldKey.MANAGER_EXTERNAL_ID, existing.manager(), row.managerExternalId());
        addIfDifferent(changed, FieldKey.OFFICE_CODE, existing.officeId(), officeId);
        addIfDifferent(changed, FieldKey.SHIFT_CODE, existing.shift(), row.shiftCode());
        return changed;
    }

    private static void addIfDifferent(Set<FieldKey> changed, FieldKey key, Object before, Object after) {
        if (!Objects.equals(before, after)) {
            changed.add(key);
        }
    }
}
