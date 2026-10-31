package com.moveinsync.roster.reconciliation;

import com.moveinsync.roster.core.reconciliation.DiffAction;
import com.moveinsync.roster.core.reconciliation.DiffClassifier;
import com.moveinsync.roster.domain.EmployeeStatus;
import com.moveinsync.roster.employee.EmployeeDiffRow;
import com.moveinsync.roster.employee.EmployeeRepository;
import java.io.BufferedReader;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;

@Component
class BucketDiffer {

    private final EmployeeRepository employees;
    private final ReconciliationStore store;

    BucketDiffer(EmployeeRepository employees, ReconciliationStore store) {
        this.employees = employees;
        this.store = store;
    }

    static final class Counts {
        int unchanged;
        int skipped;
        int duplicates;
        int candidates;
    }

    Counts diff(long runId, long tenantId, Instant generatedAt, int bucketCount, int pageSize, SpillWorkspace spill, Runnable heartbeat) throws IOException {
        Counts counts = new Counts();
        for (int bucket = 0; bucket < bucketCount; bucket++) {
            heartbeat.run();
            Map<String, String> exported = loadBucket(bucket, spill, counts);
            List<Long> candidates = new ArrayList<>();
            long afterId = 0;
            List<EmployeeDiffRow> page;
            do {
                page = employees.findDiffRows(tenantId, bucket, afterId, Limit.of(pageSize));
                for (EmployeeDiffRow stored : page) {
                    afterId = stored.getId();
                    String line = exported.remove(stored.getExternalId());
                    DiffAction action = DiffClassifier.classify(line != null, line == null ? null : SpillWorkspace.hash(line),
                            new DiffClassifier.StoreRow(stored.getStatus() == EmployeeStatus.ACTIVE, stored.getContentHash(), stored.getHrUpdatedAt()),
                            generatedAt);
                    record(action, line, stored.getId(), candidates, counts, spill);
                }
            } while (page.size() == pageSize);
            for (String line : exported.values()) {
                record(DiffClassifier.classify(true, SpillWorkspace.hash(line), null, generatedAt), line, null, candidates, counts, spill);
            }
            store.park(runId, candidates);
            counts.candidates += candidates.size();
        }
        return counts;
    }

    private static Map<String, String> loadBucket(int bucket, SpillWorkspace spill, Counts counts) throws IOException {
        Map<String, String> rows = new HashMap<>();
        try (BufferedReader reader = spill.readBucket(bucket)) {
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                if (rows.putIfAbsent(SpillWorkspace.externalId(line), line) != null) {
                    counts.duplicates++;
                }
            }
        }
        return rows;
    }

    private static void record(DiffAction action, String line, Long employeeId, List<Long> candidates, Counts counts,
                               SpillWorkspace spill) throws IOException {
        switch (action) {
            case CREATE, UPDATE, REACTIVATE -> spill.appendChange(line);
            case DEACTIVATION_CANDIDATE -> candidates.add(employeeId);
            case UNCHANGED -> counts.unchanged++;
            case SKIPPED_NEWER_THAN_EXPORT -> counts.skipped++;
            case UNUSABLE_ROW -> { }
        }
    }
}
