package com.moveinsync.roster.core.hashing;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class ContentHasher {

    private static final char SEPARATOR = '\u001F';

    private ContentHasher() {
    }

    public static String hash(String name, String email, String phone,
                              String managerExternalId, String officeCode, String shiftCode) {
        String canonical = new StringBuilder()
                .append(orEmpty(name)).append(SEPARATOR)
                .append(orEmpty(email)).append(SEPARATOR)
                .append(orEmpty(phone)).append(SEPARATOR)
                .append(orEmpty(managerExternalId)).append(SEPARATOR)
                .append(orEmpty(officeCode)).append(SEPARATOR)
                .append(orEmpty(shiftCode))
                .toString();
        return HexFormat.of().formatHex(sha256().digest(canonical.getBytes(StandardCharsets.UTF_8)));
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every Java platform is required to provide SHA-256", e);
        }
    }
}
