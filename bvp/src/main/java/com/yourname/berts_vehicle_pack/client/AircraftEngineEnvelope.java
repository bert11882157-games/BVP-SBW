package com.yourname.berts_vehicle_pack.client;

/** Client-tick spool and sample crossfade. Never feeds back into aircraft physics. */
final class AircraftEngineEnvelope {
    private float spool;

    AircraftEngineEnvelope(float initialThrottle) {
        spool = clamp(initialThrottle);
    }

    void tick(float throttle) {
        float target = clamp(throttle);
        // TaP's loop smooths RPM by 0.1 per tick; rundown is slightly slower.
        spool += (target - spool) * (target > spool ? 0.1F : 0.06F);
    }

    float runningPitch(boolean afterburner) {
        return 0.8F + 0.5F * spool + (afterburner ? 0.08F : 0F);
    }

    float idlePitch() {
        return 0.85F + 0.15F * spool;
    }

    float runningGain(boolean separateIdle, boolean afterburner) {
        return (separateIdle ? mix() : 1F)
                * (0.32F + 0.68F * (float) Math.pow(spool, 0.7))
                * (afterburner ? 1.15F : 1F);
    }

    float idleGain() {
        return 0.45F * (1F - mix());
    }

    private float mix() {
        float blend = clamp((spool - 0.08F) / 0.47F);
        return blend * blend * (3F - 2F * blend);
    }

    private static float clamp(float value) {
        return Float.isFinite(value) ? Math.max(0F, Math.min(1F, value)) : 0F;
    }
}
