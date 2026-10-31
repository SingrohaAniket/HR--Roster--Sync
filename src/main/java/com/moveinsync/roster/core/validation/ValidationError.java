package com.moveinsync.roster.core.validation;

public record ValidationError(ErrorCode code, ValidatedField field) {
}
