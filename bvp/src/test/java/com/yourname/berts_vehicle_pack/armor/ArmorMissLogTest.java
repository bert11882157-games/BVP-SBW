package com.yourname.berts_vehicle_pack.armor;

import static com.yourname.berts_vehicle_pack.armor.ArmorMeshTestSupport.check;

/** Headless checks for the always-on "Shot missed!" log line: contents and rate limiting. */
public final class ArmorMissLogTest {
    public static void main(String[] args) {
        lineCarriesEveryField();
        rateLimiterCountsSuppressedLines();
        System.out.println("PASS miss log: fields, nearest plate gap, rate limit and suppressed counts");
    }

    private static void lineCarriesEveryField() {
        ArmorMissLog.Fields fields = new ArmorMissLog.Fields("t72b", "armor_mesh/t72b.geo.json",
                "berts_vehicle_pack:t72b#42", "superbwarfare:cannon_shell", "berts_vehicle_pack:bm42",
                "TURRET", new double[] {100.5, 64.25, -30.125}, new double[] {0.25, 1.5, -2.75},
                new double[] {0.0, -0.1, 0.995}, "turret_cheek_03", "turret", 0.1834, 4.0213, 12.5, 0.12);
        String line = ArmorMissLog.format(fields, 3);
        for (String expected : new String[] {"Shot missed", "'t72b'", "mesh armor_mesh/t72b.geo.json",
                "vehicle=berts_vehicle_pack:t72b#42", "projectile=superbwarfare:cannon_shell",
                "projectile_profile=berts_vehicle_pack:bm42", "obb_part=TURRET",
                "hit_world=(100.500, 64.250, -30.125)", "armor_local=(0.250, 1.500, -2.750)",
                "local_dir=(0.000, -0.100, 0.995)", "nearest_plate=turret_cheek_03 [turret]",
                "gap=0.183", "at_ray=4.021", "turret_yaw=12.5", "hull_speed=0.120 b/t (2.4 m/s)",
                "+3 miss(es) not logged"}) {
            check(line.contains(expected), "line has '" + expected + "': " + line);
        }
        String bare = ArmorMissLog.format(new ArmorMissLog.Fields("mi24v", null, "v#1", "t", null, null,
                null, null, null, null, null, Double.NaN, Double.NaN, 0.0, 0.0), 0);
        check(bare.contains("box volumes") && bare.contains("nearest_plate=none") && bare.contains("obb_part=unknown")
                && bare.contains("projectile_profile=none") && !bare.contains("not logged"), "missing values: " + bare);
    }

    private static void rateLimiterCountsSuppressedLines() {
        ArmorMissLog.RateLimiter limiter = new ArmorMissLog.RateLimiter(3, 1_000L);
        check(limiter.tryAcquire(0) == 0 && limiter.tryAcquire(10) == 0 && limiter.tryAcquire(20) == 0,
                "first lines pass");
        check(limiter.tryAcquire(30) == -1 && limiter.tryAcquire(40) == -1, "window full");
        check(limiter.tryAcquire(1_000) == 2, "next window reports two suppressed lines");
        check(limiter.tryAcquire(1_010) == 0, "counter reset after reporting");
    }
}
