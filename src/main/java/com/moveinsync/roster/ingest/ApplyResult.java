package com.moveinsync.roster.ingest;

import com.moveinsync.roster.core.validation.ValidationError;
import com.moveinsync.roster.domain.EventOutcome;
import java.util.List;

record ApplyResult(EventOutcome outcome, List<ValidationError> warnings) {

    static ApplyResult of(EventOutcome outcome) {
        return new ApplyResult(outcome, List.of());
    }
}
