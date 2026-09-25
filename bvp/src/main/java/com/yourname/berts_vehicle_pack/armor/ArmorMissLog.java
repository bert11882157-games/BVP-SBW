package com.yourname.berts_vehicle_pack.armor;

import java.util.Locale;

/**
 * Always-on server log line for "Shot missed!" results, independent of Elite diagnostics.
 *
 * <p>A miss means the coarse vehicle OBB accepted a projectile contact but no authored armor plate
 * lies on the shell ray. Each line carries what is needed to find the gap in the armor: the world
 * and armor-local contact, the local shot direction, the nearest plate with its closest-approach
 * gap, the turret yaw, the hull speed, the projectile identity and the OBB part hit. Lines are
 * rate limited; suppressed misses are counted into the next line that is written.</p>
 */
final class ArmorMissLog {
    static final int LINES_PER_WINDOW = 12;
    static final long WINDOW_NANOS = 10_000_000_000L;

    private static final RateLimiter LIMITER = new RateLimiter(LINES_PER_WINDOW, WINDOW_NANOS);

    private ArmorMissLog() {
    }

    /** Returns the line to log now, or null when the rate limit suppresses it. */
    static String admit(Fields fields, long nowNanos) {
        long suppressed = LIMITER.tryAcquire(nowNanos);
        return suppressed < 0L ? null : format(fields, suppressed);
    }

    static String format(Fields f, long suppressedSinceLastLine) {
        StringBuilder line = new StringBuilder(320);
        line.append("[BVP Armor] Shot missed: no armor plate on the shell ray (strict profile '")
                .append(f.profileId()).append("'")
                .append(f.meshSource() == null ? ", box volumes" : ", mesh " + f.meshSource())
                .append("). vehicle=").append(f.vehicle())
                .append(" projectile=").append(f.projectileType())
                .append(" projectile_profile=").append(f.projectileProfile() == null ? "none" : f.projectileProfile())
                .append(" obb_part=").append(f.obbPart() == null ? "unknown" : f.obbPart())
                .append(" hit_world=").append(vector(f.hitWorld()))
                .append(" armor_local=").append(vector(f.armorLocal()))
                .append(" local_dir=").append(vector(f.localDirection()));
        if (f.nearestPlate() == null) {
            line.append(" nearest_plate=none");
        } else {
            line.append(" nearest_plate=").append(f.nearestPlate())
                    .append(" [").append(f.nearestPlateFrame()).append("]")
                    .append(String.format(Locale.ROOT, " gap=%.3f at_ray=%.3f", f.gap(), f.rayDistance()));
        }
        line.append(String.format(Locale.ROOT, " turret_yaw=%.1f hull_speed=%.3f b/t (%.1f m/s)",
                f.turretYawDegrees(), f.hullSpeedBlocksPerTick(), f.hullSpeedBlocksPerTick() * 20.0D));
        if (suppressedSinceLastLine > 0L) {
            line.append(" (+").append(suppressedSinceLastLine).append(" miss(es) not logged since the previous line)");
        }
        return line.toString();
    }

    private static String vector(double[] value) {
        if (value == null || value.length < 3) return "n/a";
        return String.format(Locale.ROOT, "(%.3f, %.3f, %.3f)", value[0], value[1], value[2]);
    }

    /** Values of one miss. Vectors are {x, y, z}; distances in blocks. */
    record Fields(String profileId, String meshSource, String vehicle, String projectileType,
                  String projectileProfile, String obbPart, double[] hitWorld, double[] armorLocal,
                  double[] localDirection, String nearestPlate, String nearestPlateFrame, double gap,
                  double rayDistance, double turretYawDegrees, double hullSpeedBlocksPerTick) {
    }

    /** At most {@code lines} lines per {@code windowNanos}; counts what it refuses. */
    static final class RateLimiter {
        private final int lines;
        private final long windowNanos;
        private long windowStart;
        private boolean started;
        private int used;
        private long suppressed;

        RateLimiter(int lines, long windowNanos) {
            this.lines = lines;
            this.windowNanos = windowNanos;
        }

        /** -1 when refused, otherwise the number of refusals since the previous admitted line. */
        synchronized long tryAcquire(long nowNanos) {
            if (!started || nowNanos - windowStart >= windowNanos || nowNanos < windowStart) {
                started = true;
                windowStart = nowNanos;
                used = 0;
            }
            if (used >= lines) {
                suppressed++;
                return -1L;
            }
            used++;
            long previous = suppressed;
            suppressed = 0L;
            return previous;
        }
    }
}
