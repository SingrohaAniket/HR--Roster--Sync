package com.moveinsync.roster.reconciliation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.moveinsync.roster.core.hashing.BucketAssigner;
import com.moveinsync.roster.core.hashing.ContentHasher;
import com.moveinsync.roster.core.reconciliation.ExportCsv;
import com.moveinsync.roster.ingest.AbstractIngestIT;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;

abstract class AbstractReconciliationIT extends AbstractIngestIT {

    protected static final Instant GENERATED_AT = Instant.parse("2026-10-30T00:00:00Z");
    protected static final Instant BEFORE_EXPORT = GENERATED_AT.minusSeconds(3600);
    protected static final Instant AFTER_EXPORT = GENERATED_AT.plusSeconds(3600);
    protected static final long SEEDED_HR_VERSION = 7;
    protected static final String OFFICE = "OFC-A";
    protected static final String OTHER_OFFICE = "OFC-B";

    @Autowired
    private PasswordEncoder passwordEncoder;

    protected record TestTenant(long id, String code, String token, long officeId) {
    }

    protected TestTenant newTenant() throws Exception {
        String code = unique("R").toUpperCase();
        jdbc.update("INSERT INTO tenant (code, name) VALUES (?, 'Reconciliation Test Tenant')", code);
        long id = jdbc.queryForObject("SELECT id FROM tenant WHERE code = ?", Long.class, code);
        jdbc.update("INSERT INTO office (tenant_id, code, name, city) VALUES (?, ?, 'A', 'X'), (?, ?, 'B', 'X')", id, OFFICE, id, OTHER_OFFICE);
        jdbc.update("INSERT INTO app_user (tenant_id, username, password_hash, role) VALUES (?, 'op', ?, 'OPERATOR')", id, passwordEncoder.encode(PASSWORD));
        long officeId = jdbc.queryForObject("SELECT id FROM office WHERE tenant_id = ? AND code = ?", Long.class, id, OFFICE);
        return new TestTenant(id, code, tokenFor(code, "op"), officeId);
    }

    protected record Person(String id, String shift) {
        String csv() {
            return id + ",Name " + id + "," + id.toLowerCase() + "@test.example,,," + OFFICE + "," + shift;
        }

        String hash() {
            return ContentHasher.hash("Name " + id, id.toLowerCase() + "@test.example", null, null, OFFICE, shift);
        }
    }

    protected void seed(TestTenant tenant, List<Person> people, String status, Instant hrUpdatedAt) {
        jdbc.batchUpdate("""
                INSERT INTO employee (tenant_id, external_id, name, email, office_id, shift_code, status, hr_version, hr_updated_at,
                                      content_hash, bucket, deactivated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, people, 1000, (ps, person) -> {
            ps.setLong(1, tenant.id());
            ps.setString(2, person.id());
            ps.setString(3, "Name " + person.id());
            ps.setString(4, person.id().toLowerCase() + "@test.example");
            ps.setLong(5, tenant.officeId());
            ps.setString(6, person.shift());
            ps.setString(7, status);
            ps.setLong(8, SEEDED_HR_VERSION);
            ps.setObject(9, LocalDateTime.ofInstant(hrUpdatedAt, ZoneOffset.UTC));
            ps.setString(10, person.hash());
            ps.setInt(11, BucketAssigner.bucket(person.id(), 256));
            ps.setObject(12, "INACTIVE".equals(status) ? LocalDateTime.ofInstant(hrUpdatedAt, ZoneOffset.UTC) : null);
        });
    }

    protected static List<Person> people(String prefix, int count, String shift) {
        List<Person> people = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            people.add(new Person(prefix + "-" + i, shift));
        }
        return people;
    }

    protected static String csv(List<Person> people) {
        StringBuilder csv = new StringBuilder(ExportCsv.HEADER).append('\n');
        people.forEach(person -> csv.append(person.csv()).append('\n'));
        return csv.toString();
    }

    protected MockHttpServletResponse upload(String token, String csv, Instant generatedAt) throws Exception {
        return mockMvc.perform(multipart("/api/v1/reconciliation/runs")
                        .file(new MockMultipartFile("file", "export.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
                        .param("generatedAt", generatedAt.toString())
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse();
    }

    protected JsonNode run(TestTenant tenant, String csv) throws Exception {
        MockHttpServletResponse response = upload(tenant.token(), csv, GENERATED_AT);
        if (response.getStatus() != 201) {
            throw new IllegalStateException("Upload failed with " + response.getStatus());
        }
        return body(response);
    }

    protected MockHttpServletResponse resolve(String token, long runId, String action) throws Exception {
        return mockMvc.perform(post("/api/v1/reconciliation/runs/" + runId + "/" + action).header("Authorization", "Bearer " + token))
                .andReturn().getResponse();
    }

    protected int count(TestTenant tenant, String status) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM employee WHERE tenant_id = ? AND status = ?", Integer.class, tenant.id(), status);
    }

    protected int outboxOf(TestTenant tenant, String eventType) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM outbox WHERE tenant_id = ? AND event_type = ?", Integer.class, tenant.id(), eventType);
    }

    protected List<Map<String, Object>> employeesOf(TestTenant tenant) {
        return jdbc.queryForList("SELECT * FROM employee WHERE tenant_id = ? ORDER BY id", tenant.id());
    }

    protected int parked(long runId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM reconciliation_pending_deactivation WHERE run_id = ?", Integer.class, runId);
    }

    protected String seedCutOffFile(TestTenant tenant) {
        List<Person> everyone = people("CUT", 2000, "S1");
        seed(tenant, everyone, "ACTIVE", BEFORE_EXPORT);
        return csv(everyone.subList(0, 1200));
    }
}
