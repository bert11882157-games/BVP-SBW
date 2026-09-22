package com.yourname.berts_vehicle_pack.armor;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class EraBrickIds {
    private static final Pattern NON_ID_CHARS = Pattern.compile("[^a-z0-9]+");

    private EraBrickIds() {
    }

    public static String stateId(String id) {
        return sanitize(id, "");
    }

    public static String boneSuffix(String id) {
        return sanitize(id, "unnamed");
    }

    public static Set<String> parseStateIds(String value) {
        Set<String> ids = new HashSet<>();
        if (value == null || value.isBlank()) {
            return ids;
        }
        for (String id : value.split(",")) {
            String normalized = stateId(id);
            if (!normalized.isEmpty()) {
                ids.add(normalized);
            }
        }
        return ids;
    }

    private static String sanitize(String id, String emptyFallback) {
        if (id == null || id.isBlank()) {
            return emptyFallback;
        }
        String sanitized = NON_ID_CHARS.matcher(id.trim().toLowerCase(Locale.ROOT)).replaceAll("_");
        return sanitized.isEmpty() ? emptyFallback : sanitized;
    }
}
