package com.yourname.berts_vehicle_pack.client;

import com.yourname.berts_vehicle_pack.armor.BvpImpactFragmentPlanner;
import com.yourname.berts_vehicle_pack.effects.TracerInterpolation;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/** Headless checks against the production planner, pool, motion, and size envelope. */
public final class BvpClientImpactFragmentsTest {
    private static final Vec3 ORIGIN = new Vec3(0, 0, 0);
    private static final Vec3 INCOMING = new Vec3(1, -0.4, 0);
    private static final Vec3 NORMAL = new Vec3(0, 1, 0);

    public static void main(String[] args) {
        check(plan(12.7F, 0, 1).fragments().isEmpty(), "small-arms exclusion");
        check(plan(80, 2, 1).fragments().size() == 12, "small rocket count");
        check(plan(100, 3, 1).fragments().size() == 18, "large rocket count");
        check(plan(125, 4, 1).fragments().size() == 24, "ATGM count");
        var heavy = plan(380, 6, 1);
        check(heavy.fragments().size() == 64 && heavy.renderScale() == 0.10F, "small bounded heavy burst");
        for(var fragment:heavy.fragments()) {
            check(fragment.speedBlocksPerTick() >= 4 && fragment.speedBlocksPerTick() <= 6, "fast bounded heavy fragment");
            check(fragment.lifetimeTicks() == 4, "heavy fragment short lifetime");
            check(Math.abs(fragment.direction().m_82556_()-1) < 1e-9, "unit spherical direction");
        }
        var heavyPool = new BvpClientImpactFragments.Pool();
        check(heavyPool.admit(ORIGIN,heavy), "heavy recipe admitted");
        check(heavyPool.admit(ORIGIN,heavy), "second heavy recipe admitted");
        check(!heavyPool.admit(ORIGIN,heavy), "heavy recipe retains global pool bound");
        for(int tick=0;tick<4;tick++)heavyPool.tick();
        check(heavyPool.bodies.isEmpty(), "heavy fragment lifetime expires");
        var first = plan(125, 0, 1);
        var second = plan(125, 0, 2);
        check(!first.fragments().get(0).direction()
                .equals(second.fragments().get(0).direction()), "local-seed variation");
        for (var fragment : first.fragments()) {
            check(fragment.direction().m_82526_(NORMAL) > 0, "outward ground fan");
            check(fragment.lifetimeTicks() == 10 || fragment.lifetimeTicks() == 12
                    || fragment.lifetimeTicks() == 14, "retained visual lifetime");
        }
        for (int life : new int[] {10, 12, 14}) {
            check(TracerInterpolation.fragmentScale(life * 0.75, life) == 1, "75% hold");
            check(TracerInterpolation.fragmentScale(life * 0.875, life) == 0.5F, "shrink");
            check(TracerInterpolation.fragmentScale(life, life) == 0, "zero at expiry");
            var pool = new BvpClientImpactFragments.Pool();
            var fragment = new BvpImpactFragmentPlanner.Fragment(NORMAL, 1, life, 1);
            check(pool.admit(ORIGIN,
                    new BvpImpactFragmentPlanner.Plan(1, List.of(fragment))), "admit");
            var body = pool.bodies.get(0);
            pool.tick();
            check(body.position.f_82480_ == 1, "visual integration");
            check(body.velocity.f_82480_ < 1, "visual gravity");
            check(body.path.first().f_82480_ > 0
                    && body.path.second().f_82480_ < 1, "intermediate samples");
            for (int tick = 1; tick < life; tick++) pool.tick();
            check(pool.bodies.isEmpty(), "bounded expiry");
        }
        var pool = new BvpClientImpactFragments.Pool();
        for (int i = 0; i < 16; i++) check(pool.reserveRecipe(), "recipe admission");
        check(!pool.reserveRecipe(), "recipe cap");
        pool.tick();
        check(pool.reserveRecipe(), "next-tick recovery");
        var rocket = plan(125, 4, 1);
        for (int i = 0; i < 5; i++) check(pool.admit(ORIGIN, rocket), "whole fan");
        check(!pool.admit(ORIGIN, rocket), "per-tick atomic rejection");
        check(pool.bodies.size() == 120, "no partial fan");
        for (int tick = 0; tick < 40; tick++) {
            pool.tick();
            for (int i = 0; i < 16; i++) {
                if (pool.reserveRecipe()) pool.admit(ORIGIN, rocket);
            }
            check(pool.bodies.size() <= 512, "live cap");
        }
        pool.clear();
        check(pool.bodies.isEmpty() && pool.recipes == 0 && pool.admitted == 0,
                "level/reload reset");
        var invalid = new BvpImpactFragmentPlanner.Fragment(
                new Vec3(Double.NaN, 0, 0), 1, 10, 1);
        check(!pool.admit(ORIGIN,
                new BvpImpactFragmentPlanner.Plan(1, List.of(invalid))), "invalid fan");
        check(pool.bodies.isEmpty(), "invalid fan is atomic");
        System.out.println("Client impact fragment behavior checks passed");
    }

    private static BvpImpactFragmentPlanner.Plan plan(float caliber, int policy, long seed) {
        return BvpClientImpactFragments.plan(caliber, policy, INCOMING, NORMAL, seed);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
