package com.moveinsync.roster.ingest;

import com.moveinsync.roster.core.validation.ErrorCode;
import com.moveinsync.roster.core.validation.ValidationError;

public final class FieldIssue {

    private final ValidationError issue;

    FieldIssue(ValidationError issue) {
        this.issue = issue;
    }

    public ErrorCode getCode() {
        return issue.code();
    }

    public String getField() {
        return issue.field().apiName();
    }
}
