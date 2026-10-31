package com.moveinsync.roster.core.validation;

import java.math.BigDecimal;

public record RosterRecord(
        String eventId,
        String employeeExternalId,
        Long version,
        String source,
        String eventType,
        String name,
        String email,
        String phone,
        String officeCode,
        BigDecimal pickupLat,
        BigDecimal pickupLng,
        String managerExternalId,
        String shiftCode,
        String pickupLabel,
        String specialAssistance) {

    @Override
    public String toString() {
        return "RosterRecord[eventId=" + eventId + ", employeeExternalId=" + employeeExternalId + "]";
    }
}
