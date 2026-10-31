package com.moveinsync.roster.core.reconciliation;

import com.moveinsync.roster.core.validation.RecordValidator;
import com.moveinsync.roster.core.validation.RosterRecord;
import java.util.ArrayList;
import java.util.List;

public final class ExportCsv {

    public static final String HEADER = "externalId,name,email,phone,managerExternalId,officeCode,shiftCode";

    private static final int COLUMNS = 7;
    private static final char SEPARATOR_IN_HASH = '\u001F';
    private static final int MAX_EXTERNAL_ID_LENGTH = 64;

    private ExportCsv() {
    }

    public record ParsedLine(String externalId, ExportRow row) {
    }

    public static boolean isValidHeader(String line) {
        return line != null && HEADER.equals(line.startsWith("﻿") ? line.substring(1) : line);
    }

    public static ParsedLine parse(String line) {
        List<String> cells = split(line);
        if (cells == null || line.indexOf(SEPARATOR_IN_HASH) >= 0) {
            return new ParsedLine(null, null);
        }
        String externalId = readableId(cells);
        if (cells.size() != COLUMNS || externalId == null) {
            return new ParsedLine(externalId, null);
        }
        ExportRow row = new ExportRow(cells.get(0), cells.get(1), cells.get(2), cells.get(3), cells.get(4), cells.get(5), cells.get(6));
        return new ParsedLine(externalId, isValid(row) ? row : null);
    }

    private static String readableId(List<String> cells) {
        String id = cells.isEmpty() ? null : cells.get(0);
        return id == null || id.isBlank() || id.length() > MAX_EXTERNAL_ID_LENGTH ? null : id;
    }

    private static boolean isValid(ExportRow row) {
        return RecordValidator.validate(new RosterRecord("export-row", row.externalId(), 1L, "HR", "CREATE",
                row.name(), row.email(), row.phone(), row.officeCode(), null, null,
                row.managerExternalId(), row.shiftCode(), null, null)).isEmpty();
    }

    private static List<String> split(String line) {
        List<String> cells = new ArrayList<>(COLUMNS);
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c != '"') {
                    cell.append(c);
                } else if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else {
                    quoted = false;
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                cells.add(emptyToNull(cell));
                cell.setLength(0);
            } else {
                cell.append(c);
            }
        }
        if (quoted) {
            return null;
        }
        cells.add(emptyToNull(cell));
        return cells;
    }

    private static String emptyToNull(StringBuilder cell) {
        return cell.isEmpty() ? null : cell.toString();
    }
}
