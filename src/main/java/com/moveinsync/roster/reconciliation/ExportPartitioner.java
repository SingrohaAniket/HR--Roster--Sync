package com.moveinsync.roster.reconciliation;

import com.moveinsync.roster.core.hashing.BucketAssigner;
import com.moveinsync.roster.core.reconciliation.ExportCsv;
import java.io.BufferedReader;
import java.io.IOException;
import java.util.Set;

final class ExportPartitioner {

    private ExportPartitioner() {
    }

    record Result(int rows, int malformed, int unknownOffice) {
    }

    static Result partition(BufferedReader export, SpillWorkspace spill, Set<String> activeOfficeCodes, int bucketCount)
            throws IOException {
        int rows = 0;
        int malformed = 0;
        int unknownOffice = 0;
        for (String line = export.readLine(); line != null; line = export.readLine()) {
            if (line.isBlank()) {
                continue;
            }
            rows++;
            ExportCsv.ParsedLine parsed = ExportCsv.parse(line);
            if (parsed.row() == null) {
                malformed++;
                if (parsed.externalId() != null) {
                    spill.appendUnusable(BucketAssigner.bucket(parsed.externalId(), bucketCount), parsed.externalId());
                }
            } else if (!activeOfficeCodes.contains(parsed.row().officeCode())) {
                unknownOffice++;
                spill.appendUnusable(BucketAssigner.bucket(parsed.externalId(), bucketCount), parsed.externalId());
            } else {
                spill.appendRow(BucketAssigner.bucket(parsed.externalId(), bucketCount), parsed.row());
            }
        }
        spill.finishPartitioning();
        return new Result(rows, malformed, unknownOffice);
    }
}
