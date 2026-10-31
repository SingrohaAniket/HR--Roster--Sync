package com.moveinsync.roster.core.ownership;

import java.util.EnumSet;
import java.util.Set;

public final class FieldOwnership {

    private FieldOwnership() {
    }

    public static Owner ownerOf(FieldKey field) {
        return switch (field) {
            case NAME, EMAIL, PHONE, MANAGER_EXTERNAL_ID, OFFICE_CODE, SHIFT_CODE, STATUS -> Owner.HR;
            case PICKUP_LAT, PICKUP_LNG, PICKUP_LABEL, SPECIAL_ASSISTANCE -> Owner.TRANSPORT;
        };
    }

    public static OwnershipSplit split(Owner sender, Set<FieldKey> present) {
        Set<FieldKey> toWrite = EnumSet.noneOf(FieldKey.class);
        Set<FieldKey> dropped = EnumSet.noneOf(FieldKey.class);
        for (FieldKey field : present) {
            (ownerOf(field) == sender ? toWrite : dropped).add(field);
        }
        return new OwnershipSplit(toWrite, dropped);
    }
}
