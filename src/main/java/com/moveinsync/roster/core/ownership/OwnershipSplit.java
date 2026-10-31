package com.moveinsync.roster.core.ownership;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

public record OwnershipSplit(Set<FieldKey> toWrite, Set<FieldKey> dropped) {

    public OwnershipSplit {
        toWrite = frozen(toWrite);
        dropped = frozen(dropped);
    }

    private static Set<FieldKey> frozen(Set<FieldKey> fields) {
        EnumSet<FieldKey> copy = EnumSet.noneOf(FieldKey.class);
        copy.addAll(fields);
        return Collections.unmodifiableSet(copy);
    }
}
