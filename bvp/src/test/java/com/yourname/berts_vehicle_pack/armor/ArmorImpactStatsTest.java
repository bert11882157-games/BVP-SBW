package com.yourname.berts_vehicle_pack.armor;

import java.util.Map;

/** Headless checks for the armor outcome counters used by acceptance scenarios. */
public final class ArmorImpactStatsTest {
    public static void main(String[] args) {
        ArmorImpactStats.reset();
        Map<String, Long> empty = ArmorImpactStats.snapshot();
        check(empty.size() == ArmorImpactStats.Outcome.values().length, "every outcome is reported");
        check(empty.values().stream().allMatch(value -> value == 0L), "reset clears every counter");

        ArmorImpactStats.record(ArmorImpactStats.Outcome.FALLBACK_NEAREST_PLATE);
        ArmorImpactStats.record(ArmorImpactStats.Outcome.PENETRATION);
        ArmorImpactStats.record(ArmorImpactStats.Outcome.RUNNING_GEAR);
        ArmorImpactStats.record(ArmorImpactStats.Outcome.NON_PENETRATION);
        ArmorImpactStats.record(null);

        Map<String, Long> snapshot = ArmorImpactStats.snapshot();
        check(snapshot.get("PENETRATION") == 1L && snapshot.get("NON_PENETRATION") == 1L, "terminal counts");
        check(snapshot.get("FALLBACK_NEAREST_PLATE") == 1L && snapshot.get("RUNNING_GEAR") == 1L, "marker counts");
        check(snapshot.get("MISS") == 0L, "no miss recorded");
        check(ArmorImpactStats.terminalTotal() == 2L, "markers are not terminal outcomes");
        check(ArmorImpactStats.count(ArmorImpactStats.Outcome.PENETRATION) == 1L, "single counter read");
        try {
            snapshot.put("MISS", 5L);
            throw new AssertionError("snapshot must be read-only");
        } catch (UnsupportedOperationException expected) {
            // read-only copy
        }
        ArmorImpactStats.record(ArmorImpactStats.Outcome.PENETRATION);
        check(snapshot.get("PENETRATION") == 1L, "snapshot is a copy");
        ArmorImpactStats.reset();
        check(ArmorImpactStats.terminalTotal() == 0L, "reset clears terminal total");
        System.out.println("PASS armor outcome counters, markers, snapshot copy and reset");
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
