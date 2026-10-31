package com.moveinsync.roster.core.validation;

import com.moveinsync.roster.core.ownership.FieldKey;
import com.moveinsync.roster.core.ownership.FieldOwnership;
import com.moveinsync.roster.core.ownership.Owner;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public final class RecordValidator {

    private static final int MAX_EVENT_ID_LENGTH = 100;
    private static final int MAX_EXTERNAL_ID_LENGTH = 64;
    private static final int MAX_NAME_LENGTH = 255;
    private static final int MAX_EMAIL_LENGTH = 320;
    private static final int MAX_PHONE_LENGTH = 16;
    private static final int MAX_MANAGER_LENGTH = 64;
    private static final int MAX_SHIFT_CODE_LENGTH = 32;
    private static final int MAX_PICKUP_LABEL_LENGTH = 255;
    private static final int MAX_SPECIAL_ASSISTANCE_LENGTH = 500;

    private static final Pattern E164_PHONE = Pattern.compile("\\+[1-9]\\d{7,14}");
    private static final Pattern EMAIL = Pattern.compile("[^\\s@]+@[^\\s@]+\\.[^\\s@]+");
    private static final Set<String> EVENT_TYPES = Set.of("CREATE", "UPDATE", "DEACTIVATE");
    private static final BigDecimal MAX_LATITUDE = BigDecimal.valueOf(90);
    private static final BigDecimal MAX_LONGITUDE = BigDecimal.valueOf(180);

    private RecordValidator() {
    }

    public static List<ValidationError> validate(RosterRecord record) {
        List<ValidationError> errors = new ArrayList<>();

        if (isBlank(record.eventId()) || record.eventId().length() > MAX_EVENT_ID_LENGTH) {
            errors.add(new ValidationError(ErrorCode.MISSING_EVENT_ID, ValidatedField.EVENT_ID));
        }
        if (isBlank(record.employeeExternalId()) || record.employeeExternalId().length() > MAX_EXTERNAL_ID_LENGTH) {
            errors.add(new ValidationError(ErrorCode.MISSING_EXTERNAL_ID, ValidatedField.EMPLOYEE_EXTERNAL_ID));
        }
        if (record.version() == null || record.version() < 1) {
            errors.add(new ValidationError(ErrorCode.INVALID_VERSION, ValidatedField.VERSION));
        }
        Owner sender = parseOwner(record.source());
        if (sender == null) {
            errors.add(new ValidationError(ErrorCode.INVALID_SOURCE, ValidatedField.SOURCE));
        }
        if (record.eventType() == null || !EVENT_TYPES.contains(record.eventType())) {
            errors.add(new ValidationError(ErrorCode.INVALID_EVENT_TYPE, ValidatedField.EVENT_TYPE));
        }

        checkLength(errors, sender, FieldKey.NAME, record.name(), MAX_NAME_LENGTH);
        checkLength(errors, sender, FieldKey.MANAGER_EXTERNAL_ID, record.managerExternalId(), MAX_MANAGER_LENGTH);
        checkLength(errors, sender, FieldKey.SHIFT_CODE, record.shiftCode(), MAX_SHIFT_CODE_LENGTH);
        checkLength(errors, sender, FieldKey.PICKUP_LABEL, record.pickupLabel(), MAX_PICKUP_LABEL_LENGTH);
        checkLength(errors, sender, FieldKey.SPECIAL_ASSISTANCE, record.specialAssistance(), MAX_SPECIAL_ASSISTANCE_LENGTH);

        if (checkLength(errors, sender, FieldKey.PHONE, record.phone(), MAX_PHONE_LENGTH)
                && isWritten(sender, FieldKey.PHONE) && record.phone() != null
                && !E164_PHONE.matcher(record.phone()).matches()) {
            errors.add(new ValidationError(ErrorCode.INVALID_PHONE, ValidatedField.PHONE));
        }
        if (checkLength(errors, sender, FieldKey.EMAIL, record.email(), MAX_EMAIL_LENGTH)
                && isWritten(sender, FieldKey.EMAIL) && record.email() != null
                && !EMAIL.matcher(record.email()).matches()) {
            errors.add(new ValidationError(ErrorCode.INVALID_EMAIL, ValidatedField.EMAIL));
        }
        if (isWritten(sender, FieldKey.PICKUP_LAT) && isOutside(record.pickupLat(), MAX_LATITUDE)) {
            errors.add(new ValidationError(ErrorCode.INVALID_COORDINATE, ValidatedField.PICKUP_LAT));
        }
        if (isWritten(sender, FieldKey.PICKUP_LNG) && isOutside(record.pickupLng(), MAX_LONGITUDE)) {
            errors.add(new ValidationError(ErrorCode.INVALID_COORDINATE, ValidatedField.PICKUP_LNG));
        }

        if ("CREATE".equals(record.eventType())) {
            if (isBlank(record.name())) {
                errors.add(new ValidationError(ErrorCode.INCOMPLETE_CREATE, ValidatedField.NAME));
            }
            if (isBlank(record.email())) {
                errors.add(new ValidationError(ErrorCode.INCOMPLETE_CREATE, ValidatedField.EMAIL));
            }
            if (isBlank(record.officeCode())) {
                errors.add(new ValidationError(ErrorCode.INCOMPLETE_CREATE, ValidatedField.OFFICE_CODE));
            }
        }
        return errors;
    }

    private static boolean checkLength(List<ValidationError> errors, Owner sender, FieldKey field, String value, int max) {
        if (value == null || !isWritten(sender, field) || value.codePointCount(0, value.length()) <= max) {
            return true;
        }
        errors.add(new ValidationError(ErrorCode.FIELD_TOO_LONG, ValidatedField.of(field)));
        return false;
    }

    private static Owner parseOwner(String source) {
        if ("HR".equals(source)) {
            return Owner.HR;
        }
        return "TRANSPORT".equals(source) ? Owner.TRANSPORT : null;
    }

    private static boolean isWritten(Owner sender, FieldKey field) {
        return sender == null || FieldOwnership.ownerOf(field) == sender;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean isOutside(BigDecimal coordinate, BigDecimal limit) {
        return coordinate != null && coordinate.abs().compareTo(limit) > 0;
    }
}
