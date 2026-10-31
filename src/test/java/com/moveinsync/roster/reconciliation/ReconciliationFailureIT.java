package com.moveinsync.roster.reconciliation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;

class ReconciliationFailureIT extends AbstractReconciliationIT {

    @MockitoSpyBean
    private ReconciliationApplier applier;

    private ReconciliationApplier spy() {
        return AopTestUtils.getUltimateTargetObject(applier);
    }

    private static List<Path> workspaces() throws Exception {
        try (Stream<Path> paths = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return paths.filter(path -> path.getFileName().toString().startsWith("roster-reconciliation-")).toList();
        }
    }

    @Test
    void aFailedRunIsMarkedFailedCleansUpAndLeavesTheTenantAbleToRunAgain() throws Exception {
        TestTenant tenant = newTenant();
        seed(tenant, people("KEEP", 10, "S1"), "ACTIVE", BEFORE_EXPORT);
        String export = csv(List.of(new Person("KEEP-0", "S2"), new Person("KEEP-1", "S1"), new Person("KEEP-2", "S1"), new Person("KEEP-3", "S1"),
                new Person("KEEP-4", "S1"), new Person("KEEP-5", "S1"), new Person("KEEP-6", "S1"), new Person("KEEP-7", "S1"), new Person("KEEP-8", "S1")));
        List<Path> before = workspaces();
        doThrow(new IllegalStateException("Secret-Failure-Detail")).when(spy()).applyChunk(anyLong(), any(), any(), any(), anyInt());

        MockHttpServletResponse failed = upload(tenant.token(), export, GENERATED_AT);
        reset(spy());

        assertEquals(500, failed.getStatus());
        assertEquals("INTERNAL_ERROR", body(failed).get("code").asText());
        assertFalse(failed.getContentAsString().contains("Secret-Failure-Detail"));
        assertEquals(before, workspaces(), "spill files are deleted when the run fails too");
        assertEquals("INTERNAL_ERROR", jdbc.queryForObject(
                "SELECT abort_reason FROM reconciliation_run WHERE tenant_id = ? AND status = 'FAILED'", String.class, tenant.id()));
        assertEquals(10, count(tenant, "ACTIVE"), "nothing was deactivated");
        assertEquals(0, jdbc.queryForObject("""
                SELECT COUNT(*) FROM reconciliation_pending_deactivation p JOIN reconciliation_run r ON r.id = p.run_id WHERE r.tenant_id = ?
                """, Integer.class, tenant.id()), "candidates of a failed run are discarded");

        MockHttpServletResponse retried = upload(tenant.token(), export, GENERATED_AT);

        assertEquals(201, retried.getStatus(), "the failed run does not block the next one");
        assertEquals(1, body(retried).get("updatedCount").asInt());
        assertEquals(1, body(retried).get("deactivatedCount").asInt());
    }
}
