package com.moveinsync.roster.reconciliation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.moveinsync.roster.core.hashing.BucketAssigner;
import com.moveinsync.roster.core.reconciliation.ExportRow;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class ExportPartitionerTest {

    private static final int BUCKETS = 8;
    private static final Set<String> OFFICES = Set.of("BLR-WHF");

    private static List<String> linesIn(SpillWorkspace spill, int bucket) throws IOException {
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = spill.readBucket(bucket)) {
            reader.lines().forEach(lines::add);
        }
        return lines;
    }

    private static List<Path> workspaces() throws IOException {
        try (Stream<Path> paths = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return paths.filter(path -> path.getFileName().toString().startsWith("roster-reconciliation-")).toList();
        }
    }

    @Test
    void goodRowsGoToTheirBucketWithTheirHashAndValues() throws IOException {
        try (SpillWorkspace spill = SpillWorkspace.create(BUCKETS)) {
            ExportPartitioner.Result result = ExportPartitioner.partition(new BufferedReader(new StringReader(
                    "EMP-1001,Demo Employee One,emp1001@acme.example,+919000001001,EMP-1005,BLR-WHF,S1\n")), spill, OFFICES, BUCKETS);

            assertEquals(new ExportPartitioner.Result(1, 0, 0), result);
            String line = linesIn(spill, BucketAssigner.bucket("EMP-1001", BUCKETS)).get(0);
            assertEquals("EMP-1001", SpillWorkspace.externalId(line));
            assertEquals("b6673abe149e162110eedd39d3313cbd043915615e676e4039aa23a9619ec994", SpillWorkspace.hash(line));
            ExportRow row = SpillWorkspace.row(line);
            assertEquals("Demo Employee One", row.name());
            assertEquals("EMP-1005", row.managerExternalId());
        }
    }

    @Test
    void anEmptyCellSurvivesTheSpillAsANull() throws IOException {
        try (SpillWorkspace spill = SpillWorkspace.create(BUCKETS)) {
            ExportPartitioner.partition(new BufferedReader(new StringReader("E1,Name,a@acme.example,,,BLR-WHF,\n")), spill, OFFICES, BUCKETS);

            ExportRow row = SpillWorkspace.row(linesIn(spill, BucketAssigner.bucket("E1", BUCKETS)).get(0));
            assertNull(row.phone());
            assertNull(row.managerExternalId());
            assertNull(row.shiftCode());
            assertEquals("BLR-WHF", row.officeCode());
        }
    }

    @Test
    void badRowsAreCountedAndAReadableIdIsStillMarkedPresent() throws IOException {
        try (SpillWorkspace spill = SpillWorkspace.create(BUCKETS)) {
            ExportPartitioner.Result result = ExportPartitioner.partition(new BufferedReader(new StringReader("""
                    E1,Name,not-an-email,,,BLR-WHF,S1
                    E2,Name,a@acme.example,,,NO-SUCH-OFFICE,S1
                    ,no,id,at,all,x,y

                    E3,Name,a@acme.example,,,BLR-WHF,S1
                    """)), spill, OFFICES, BUCKETS);

            assertEquals(new ExportPartitioner.Result(4, 2, 1), result, "the blank line is not a row");
            assertNull(SpillWorkspace.hash(linesIn(spill, BucketAssigner.bucket("E1", BUCKETS)).get(0)), "E1 is present but unusable");
            assertNull(SpillWorkspace.hash(linesIn(spill, BucketAssigner.bucket("E2", BUCKETS)).get(0)), "E2 is present but unusable");
        }
    }

    @Test
    void theWorkspaceIsDeletedOnCloseIncludingAfterAFailure() throws IOException {
        List<Path> before = workspaces();

        assertThrows(IllegalStateException.class, () -> {
            try (SpillWorkspace spill = SpillWorkspace.create(BUCKETS)) {
                assertEquals(before.size() + 1, workspaces().size());
                spill.appendRow(0, new ExportRow("E1", "N", "a@acme.example", null, null, "BLR-WHF", null));
                throw new IllegalStateException("the run failed");
            }
        });

        assertEquals(before, workspaces());
    }
}
