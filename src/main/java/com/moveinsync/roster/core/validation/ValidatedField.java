package com.moveinsync.roster.core.validation;

import com.moveinsync.roster.core.ownership.FieldKey;

public enum ValidatedField {
    EVENT_ID("eventId"),
    EMPLOYEE_EXTERNAL_ID("employeeExternalId"),
    VERSION("version"),
    SOURCE("source"),
    EVENT_TYPE("eventType"),
    NAME("name"),
    EMAIL("email"),
    PHONE("phone"),
    MANAGER_EXTERNAL_ID("managerExternalId"),
    OFFICE_CODE("officeCode"),
    SHIFT_CODE("shiftCode"),
    STATUS("status"),
    PICKUP_LAT("pickupLat"),
    PICKUP_LNG("pickupLng"),
    PICKUP_LABEL("pickupLabel"),
    SPECIAL_ASSISTANCE("specialAssistance");

    private final String apiName;

    ValidatedField(String apiName) {
        this.apiName = apiName;
    }

    public String apiName() {
        return apiName;
    }

    public static ValidatedField of(FieldKey key) {
        return switch (key) {
            case NAME -> NAME;
            case EMAIL -> EMAIL;
            case PHONE -> PHONE;
            case MANAGER_EXTERNAL_ID -> MANAGER_EXTERNAL_ID;
            case OFFICE_CODE -> OFFICE_CODE;
            case SHIFT_CODE -> SHIFT_CODE;
            case STATUS -> STATUS;
            case PICKUP_LAT -> PICKUP_LAT;
            case PICKUP_LNG -> PICKUP_LNG;
            case PICKUP_LABEL -> PICKUP_LABEL;
            case SPECIAL_ASSISTANCE -> SPECIAL_ASSISTANCE;
        };
    }
}
