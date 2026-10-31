package com.moveinsync.roster.core.reconciliation;

import com.moveinsync.roster.core.hashing.ContentHasher;

public record ExportRow(
        String externalId,
        String name,
        String email,
        String phone,
        String managerExternalId,
        String officeCode,
        String shiftCode) {

    public String contentHash() {
        return ContentHasher.hash(name, email, phone, managerExternalId, officeCode, shiftCode);
    }

    @Override
    public String toString() {
        return "ExportRow[externalId=" + externalId + "]";
    }
}
