package com.moveinsync.roster.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.moveinsync.roster.core.ownership.FieldKey;
import com.moveinsync.roster.core.ownership.Owner;
import com.moveinsync.roster.core.validation.ErrorCode;
import com.moveinsync.roster.core.validation.RecordValidator;
import com.moveinsync.roster.core.validation.RosterRecord;
import com.moveinsync.roster.core.validation.ValidatedField;
import com.moveinsync.roster.core.validation.ValidationError;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

record IngestEvent(
        String eventId,
        String employeeExternalId,
        Long version,
        String source,
        String eventType,
        Instant occurredAt,
        Map<FieldKey, Object> fields,
        Set<ValidatedField> unreadableCoordinates,
        String payloadHash) {

    private static final String DEACTIVATE = "DEACTIVATE";

    static IngestEvent parse(JsonNode raw) {
        String eventType = text(raw.path("eventType"));
        JsonNode payload = raw.path("payload");
        Map<FieldKey, Object> fields = new EnumMap<>(FieldKey.class);
        Set<ValidatedField> unreadable = EnumSet.noneOf(ValidatedField.class);
        if (!DEACTIVATE.equals(eventType)) {
            for (FieldKey key : FieldKey.values()) {
                readField(key, payload, fields, unreadable);
            }
        }
        return new IngestEvent(
                text(raw.path("eventId")),
                text(raw.path("employeeExternalId")),
                version(raw.path("version")),
                text(raw.path("source")),
                eventType,
                instant(raw.path("occurredAt")),
                fields,
                unreadable,
                sha256(payload.toString()));
    }

    private static void readField(FieldKey key, JsonNode payload, Map<FieldKey, Object> fields,
                                  Set<ValidatedField> unreadable) {
        if (key == FieldKey.STATUS) {
            return;
        }
        ValidatedField field = ValidatedField.of(key);
        if (!payload.has(field.apiName())) {
            return;
        }
        JsonNode value = payload.get(field.apiName());
        if (key == FieldKey.PICKUP_LAT || key == FieldKey.PICKUP_LNG) {
            if (!value.isNull() && !value.isNumber()) {
                unreadable.add(field);
            }
            fields.put(key, value.isNumber() ? value.decimalValue() : null);
        } else if (!value.isContainerNode()) {
            fields.put(key, text(value));
        }
    }

    List<ValidationError> validate() {
        List<ValidationError> errors = new ArrayList<>(RecordValidator.validate(new RosterRecord(
                eventId, employeeExternalId, version, source, eventType,
                text(FieldKey.NAME), text(FieldKey.EMAIL), text(FieldKey.PHONE), text(FieldKey.OFFICE_CODE),
                decimal(FieldKey.PICKUP_LAT), decimal(FieldKey.PICKUP_LNG),
                text(FieldKey.MANAGER_EXTERNAL_ID), text(FieldKey.SHIFT_CODE),
                text(FieldKey.PICKUP_LABEL), text(FieldKey.SPECIAL_ASSISTANCE))));
        if (!Owner.HR.name().equals(source)) {
            unreadableCoordinates.forEach(field -> errors.add(new ValidationError(ErrorCode.INVALID_COORDINATE, field)));
        }
        return errors;
    }

    Set<FieldKey> presentFields() {
        return isDeactivate() ? Set.of(FieldKey.STATUS) : fields.keySet();
    }

    boolean isCreate() {
        return "CREATE".equals(eventType);
    }

    boolean isDeactivate() {
        return DEACTIVATE.equals(eventType);
    }

    String text(FieldKey key) {
        return (String) fields.get(key);
    }

    BigDecimal decimal(FieldKey key) {
        return (BigDecimal) fields.get(key);
    }

    private static String text(JsonNode node) {
        return node.isValueNode() && !node.isNull() ? node.asText() : null;
    }

    private static Long version(JsonNode node) {
        return node.isIntegralNumber() && node.canConvertToLong() ? node.longValue() : null;
    }

    private static Instant instant(JsonNode node) {
        String value = text(node);
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every Java platform is required to provide SHA-256", e);
        }
    }

    @Override
    public String toString() {
        return "IngestEvent[eventId=" + eventId + ", employeeExternalId=" + employeeExternalId + "]";
    }
}
