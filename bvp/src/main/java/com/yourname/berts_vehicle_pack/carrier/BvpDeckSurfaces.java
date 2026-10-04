package com.yourname.berts_vehicle_pack.carrier;

import com.atsuishio.superbwarfare.api.vehicle.deck.DeckSurface;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Carrier deck heightfields, read once from this mod's jar (data/berts_vehicle_pack/sbw/decks/<id>.json, written by
 * tools/carrier/carrier_gen.py). Client and server read the same file, so both resolve identical deck columns
 * without any datapack sync. A missing or bad file leaves that vehicle without a deck (logged once).
 */
public final class BvpDeckSurfaces {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();
    private static final Pattern ID = Pattern.compile("[a-z0-9_]{1,64}");
    private static final ConcurrentHashMap<String, Optional<DeckSurface>> CACHE = new ConcurrentHashMap<>();

    private BvpDeckSurfaces() {
    }

    /** The deck of vehicle [id], or null when it has none. */
    public static DeckSurface get(String id) {
        if (id == null || !ID.matcher(id).matches()) return null;
        return CACHE.computeIfAbsent(id, BvpDeckSurfaces::load).orElse(null);
    }

    private static Optional<DeckSurface> load(String id) {
        String resource = "/data/berts_vehicle_pack/sbw/decks/" + id + ".json";
        try (InputStream stream = BvpDeckSurfaces.class.getResourceAsStream(resource)) {
            if (stream == null) return Optional.empty();
            JsonObject json = GSON.fromJson(new InputStreamReader(stream, StandardCharsets.UTF_8), JsonObject.class);
            return Optional.of(DeckSurface.parse(json));
        } catch (Exception exception) {
            LOGGER.error("[BVP Carrier] Deck surface {} failed to load; the hull has no walkable deck.", resource,
                    exception);
            return Optional.empty();
        }
    }
}
