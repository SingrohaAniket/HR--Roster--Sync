package com.moveinsync.roster.ingest;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.moveinsync.roster.core.validation.ValidationError;
import com.moveinsync.roster.domain.EventOutcome;
import java.util.List;

public record EventResult(
        int index,
        String eventId,
        String employeeExternalId,
        EventOutcome outcome,
        @JsonInclude(JsonInclude.Include.NON_NULL) EventOutcome originalOutcome,
        List<FieldIssue> errors,
        List<FieldIssue> warnings) {

    static EventResult of(int index, String eventId, String employeeExternalId, EventOutcome outcome,
                          EventOutcome originalOutcome, List<ValidationError> errors, List<ValidationError> warnings) {
        return new EventResult(index, eventId, employeeExternalId, outcome, originalOutcome,
                errors.stream().map(FieldIssue::new).toList(),
                warnings.stream().map(FieldIssue::new).toList());
    }
}
