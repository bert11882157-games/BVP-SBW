package com.yourname.berts_vehicle_pack.armor;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Process-wide counters for server armor resolutions, read by diagnostics and acceptance scenarios.
 *
 * <p>Every projectile contact that reaches BVP armor resolution records exactly one
 * {@linkplain Outcome#terminal() terminal} outcome. Fallback and running-gear markers are recorded
 * in addition to the terminal outcome of the same contact.</p>
 */
public final class ArmorImpactStats {
    public enum Outcome {
        /**
         * "Shot missed!": a strict profile accepted a vehicle OBB contact but no armor plate lies on
         * the shell ray. Every miss is also written to the server log (rate limited).
         */
        MISS(true),
        /** The projectile was stopped by armor without penetrating. */
        NON_PENETRATION(true),
        RICOCHET(true),
        PENETRATION(true),
        TRACK_HIT(true),
        MODULE_HIT(true),
        /** A deflected round re-entered the plate that just deflected it and was consumed. */
        RICOCHET_REENTRY(true),
        /** A strict profile stopped a projectile that has no BVP penetration model. */
        UNMODELED_BLOCK(true),
        /** A hidden crew contact outside every vehicle OBB; the projectile continued. */
        PASSENGER_REDIRECT_SKIPPED(true),
        /** Retired: strict profiles no longer snap a miss to the nearest plate. Kept for stable snapshot keys. */
        FALLBACK_NEAREST_PLATE(false),
        /** No plate on the shell ray of a non-strict profile; resolved as a penetrating unboxed hull hit. */
        FALLBACK_UNBOXED(false),
        /** Retired: the running-gear shortcut for misses was removed. Kept for stable snapshot keys. */
        RUNNING_GEAR(false);

        private final boolean terminal;

        Outcome(boolean terminal) {
            this.terminal = terminal;
        }

        public boolean terminal() {
            return terminal;
        }
    }

    private static final AtomicLong[] COUNTERS = new AtomicLong[Outcome.values().length];

    static {
        for (int index = 0; index < COUNTERS.length; index++) {
            COUNTERS[index] = new AtomicLong();
        }
    }

    private ArmorImpactStats() {
    }

    static void record(Outcome outcome) {
        if (outcome != null) {
            COUNTERS[outcome.ordinal()].incrementAndGet();
        }
    }

    public static long count(Outcome outcome) {
        return outcome == null ? 0L : COUNTERS[outcome.ordinal()].get();
    }

    /** Sum of terminal outcomes: one per contact that reached armor resolution. */
    public static long terminalTotal() {
        long total = 0L;
        for (Outcome outcome : Outcome.values()) {
            if (outcome.terminal()) total += COUNTERS[outcome.ordinal()].get();
        }
        return total;
    }

    public static void reset() {
        for (AtomicLong counter : COUNTERS) {
            counter.set(0L);
        }
    }

    /** Stable enum-ordered copy keyed by {@link Outcome#name()}. */
    public static Map<String, Long> snapshot() {
        Map<String, Long> values = new LinkedHashMap<>();
        for (Outcome outcome : Outcome.values()) {
            values.put(outcome.name(), COUNTERS[outcome.ordinal()].get());
        }
        return Collections.unmodifiableMap(values);
    }
}
