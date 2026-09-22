package com.atsuishio.superbwarfare.api.diagnostics;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Immutable artifact policy, deliberately independent of game state and resource packs. */
public final class DebugFeaturePolicy {
    public static final String PLAYTEST_MARKER = "/META-INF/superbwarfare/playtest-variant.json";
    private static final int MAX_MARKER_BYTES = 1024;
    private static final boolean ALLOWED = readArtifactPolicy();

    private DebugFeaturePolicy() { }

    public static boolean allowsDebugTools() { return ALLOWED; }

    /** Existing opt-in fixture flags cannot override a packaged playtest policy. */
    public static boolean isDiagnosticPropertyEnabled(String name) {
        return ALLOWED && Boolean.getBoolean(name);
    }

    private static boolean readArtifactPolicy() {
        try (InputStream stream = DebugFeaturePolicy.class.getResourceAsStream(PLAYTEST_MARKER)) {
            if (stream == null) return true;
            byte[] bytes = stream.readNBytes(MAX_MARKER_BYTES + 1);
            if (bytes.length > MAX_MARKER_BYTES) throw new IllegalArgumentException("marker exceeds 1024 bytes");
            JsonObject marker = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            if (marker.size() != 3
                    || !marker.has("schema") || !marker.get("schema").isJsonPrimitive()
                    || !marker.getAsJsonPrimitive("schema").isNumber()
                    || !marker.get("schema").getAsString().equals("1")
                    || !marker.has("variant") || !marker.get("variant").isJsonPrimitive()
                    || !marker.getAsJsonPrimitive("variant").isString()
                    || !marker.get("variant").getAsString().equals("reduced-roster-playtest")
                    || !marker.has("disableDebugTools") || !marker.get("disableDebugTools").isJsonPrimitive()
                    || !marker.getAsJsonPrimitive("disableDebugTools").isBoolean()
                    || !marker.get("disableDebugTools").getAsBoolean()) {
                throw new IllegalArgumentException("unsupported playtest marker");
            }
            return false;
        } catch (Exception invalid) {
            // A malformed present marker cannot accidentally enable tooling in a testing artifact.
            // Static initialization bounds this warning to one per process/classloader.
            LogUtils.getLogger().warn("Playtest marker is unreadable or invalid; debug tools are disabled", invalid);
            return false;
        }
    }
}
