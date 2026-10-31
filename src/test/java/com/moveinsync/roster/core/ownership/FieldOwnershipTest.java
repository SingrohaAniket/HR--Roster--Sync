package com.moveinsync.roster.core.ownership;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class FieldOwnershipTest {

    @ParameterizedTest
    @EnumSource(value = FieldKey.class, names = {"NAME", "EMAIL", "PHONE", "MANAGER_EXTERNAL_ID", "OFFICE_CODE", "SHIFT_CODE", "STATUS"})
    void hrOwnsItsFields(FieldKey field) {
        assertEquals(Owner.HR, FieldOwnership.ownerOf(field));
    }

    @ParameterizedTest
    @EnumSource(value = FieldKey.class, names = {"PICKUP_LAT", "PICKUP_LNG", "PICKUP_LABEL", "SPECIAL_ASSISTANCE"})
    void transportOwnsItsFields(FieldKey field) {
        assertEquals(Owner.TRANSPORT, FieldOwnership.ownerOf(field));
    }

    @Test
    void hrPayloadCarryingAPickupPointDropsIt() {
        OwnershipSplit split = FieldOwnership.split(Owner.HR,
                EnumSet.of(FieldKey.NAME, FieldKey.OFFICE_CODE, FieldKey.PICKUP_LAT, FieldKey.PICKUP_LNG, FieldKey.PICKUP_LABEL));

        assertEquals(EnumSet.of(FieldKey.NAME, FieldKey.OFFICE_CODE), split.toWrite());
        assertEquals(EnumSet.of(FieldKey.PICKUP_LAT, FieldKey.PICKUP_LNG, FieldKey.PICKUP_LABEL), split.dropped());
    }

    @Test
    void transportPayloadCarryingANameDropsIt() {
        OwnershipSplit split = FieldOwnership.split(Owner.TRANSPORT, EnumSet.of(FieldKey.NAME, FieldKey.PICKUP_LABEL));

        assertEquals(EnumSet.of(FieldKey.PICKUP_LABEL), split.toWrite());
        assertEquals(EnumSet.of(FieldKey.NAME), split.dropped());
    }

    @Test
    void payloadWithNoOwnedFieldsYieldsAnEmptyWriteSet() {
        OwnershipSplit split = FieldOwnership.split(Owner.HR, EnumSet.of(FieldKey.PICKUP_LABEL, FieldKey.SPECIAL_ASSISTANCE));

        assertTrue(split.toWrite().isEmpty());
        assertEquals(EnumSet.of(FieldKey.PICKUP_LABEL, FieldKey.SPECIAL_ASSISTANCE), split.dropped());
    }

    @Test
    void emptyPayloadWritesAndDropsNothing() {
        OwnershipSplit split = FieldOwnership.split(Owner.TRANSPORT, Set.of());

        assertTrue(split.toWrite().isEmpty());
        assertTrue(split.dropped().isEmpty());
    }

    @Test
    void droppedFieldsComeBackInAFixedOrder() {
        OwnershipSplit split = FieldOwnership.split(Owner.TRANSPORT, Set.of(FieldKey.SHIFT_CODE, FieldKey.NAME, FieldKey.EMAIL));

        assertEquals(List.of(FieldKey.NAME, FieldKey.EMAIL, FieldKey.SHIFT_CODE), List.copyOf(split.dropped()));
    }

    @Test
    void splitCannotBeModifiedAfterwards() {
        OwnershipSplit split = FieldOwnership.split(Owner.HR, EnumSet.of(FieldKey.NAME));

        assertThrows(UnsupportedOperationException.class, () -> split.toWrite().add(FieldKey.PICKUP_LAT));
    }
}
