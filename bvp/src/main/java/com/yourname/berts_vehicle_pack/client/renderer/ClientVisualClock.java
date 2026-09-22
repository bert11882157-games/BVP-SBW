package com.yourname.berts_vehicle_pack.client.renderer;

/** Client tick time for short visual effects, independent of synchronized world age. */
final class ClientVisualClock {
    private static long ticks;

    private ClientVisualClock() { }

    static void advance() { ticks++; }
    static void reset() { ticks = 0L; }
    static long now() { return ticks; }

    static float partial(float partialTick) {
        return Float.isFinite(partialTick) ? Math.max(0.0F, Math.min(1.0F, partialTick)) : 0.0F;
    }

    static double elapsed(long now, long started, float startedPartial, float currentPartial) {
        return Math.max(0.0D, (double) (now - started)
                + (double) partial(currentPartial) - (double) partial(startedPartial));
    }
}
