package com.moveinsync.roster.core.reconciliation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ExportCsvTest {

    private static final String VALID = "EMP-1001,Demo Employee One,emp1001@acme.example,+919000001001,EMP-1005,BLR-WHF,S1";

    @Test
    void theHeaderIsExact() {
        assertTrue(ExportCsv.isValidHeader("externalId,name,email,phone,managerExternalId,officeCode,shiftCode"));
        assertTrue(ExportCsv.isValidHeader("﻿externalId,name,email,phone,managerExternalId,officeCode,shiftCode"));
        assertFalse(ExportCsv.isValidHeader("name,externalId,email,phone,managerExternalId,officeCode,shiftCode"));
        assertFalse(ExportCsv.isValidHeader(""));
        assertFalse(ExportCsv.isValidHeader(null));
    }

    @Test
    void aSeededRowProducesTheHashAlreadyInTheDatabase() {
        ExportRow row = ExportCsv.parse(VALID).row();

        assertNotNull(row);
        assertEquals("b6673abe149e162110eedd39d3313cbd043915615e676e4039aa23a9619ec994", row.contentHash());
    }

    @Test
    void anEmptyCellIsANull() {
        ExportRow row = ExportCsv.parse("EMP-1005,Demo Manager Five,emp1005@acme.example,+919000001005,,BLR-WHF,S1").row();

        assertNull(row.managerExternalId());
        assertEquals("0a306b9d76f57a802ecfa96894de614e1d124d2dbb890fe3f26dea633dc97fef", row.contentHash());
    }

    @Test
    void quotedCellsMayContainCommasAndQuotes() {
        ExportRow row = ExportCsv.parse("EMP-1,\"Doe, \"\"Jay\"\" J\",a@acme.example,,,BLR-WHF,S1").row();

        assertEquals("Doe, \"Jay\" J", row.name());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "EMP-1,Name,a@acme.example",
            "EMP-1,Name,a@acme.example,,,BLR-WHF,S1,extra",
            "EMP-1,Name,not-an-email,,,BLR-WHF,S1",
            "EMP-1,Name,a@acme.example,12345,,BLR-WHF,S1",
            "EMP-1,,a@acme.example,,,BLR-WHF,S1",
            "EMP-1,Name,,,,BLR-WHF,S1",
            "EMP-1,Name,a@acme.example,,,,S1"
    })
    void aMalformedRowWithAReadableIdKeepsTheId(String line) {
        ExportCsv.ParsedLine parsed = ExportCsv.parse(line);

        assertNull(parsed.row());
        assertEquals("EMP-1", parsed.externalId());
    }

    @ParameterizedTest
    @ValueSource(strings = {",Name,a@acme.example,,,BLR-WHF,S1", "   ,Name,a@acme.example,,,BLR-WHF,S1"})
    void aRowWithNoIdHasNothingToKeep(String line) {
        assertNull(ExportCsv.parse(line).externalId());
    }

    @Test
    void anIdTooLongForTheColumnIsNotReadable() {
        assertNull(ExportCsv.parse("x".repeat(65) + ",Name,a@acme.example,,,BLR-WHF,S1").externalId());
    }

    @Test
    void anUnterminatedQuoteIsMalformed() {
        ExportCsv.ParsedLine parsed = ExportCsv.parse("EMP-1,\"Name,a@acme.example,,,BLR-WHF,S1");

        assertNull(parsed.row());
        assertNull(parsed.externalId());
    }

    @Test
    void theHashSeparatorIsNotAllowedInAValue() {
        assertNull(ExportCsv.parse("EMP-1,Na\u001Fme,a@acme.example,,,BLR-WHF,S1").row());
    }

    @Test
    void toStringPrintsTheIdOnly() {
        String printed = ExportCsv.parse(VALID).row().toString();

        assertTrue(printed.contains("EMP-1001"));
        assertFalse(printed.contains("Demo Employee One"));
        assertFalse(printed.contains("+919000001001"));
    }
}
