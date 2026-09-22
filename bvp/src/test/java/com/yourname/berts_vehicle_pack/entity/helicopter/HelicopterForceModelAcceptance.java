package com.yourname.berts_vehicle_pack.entity.helicopter;

import net.minecraft.world.phys.Vec3;

/**
 * Force-model acceptance with physical inputs and an unconstrained cruise trim search.
 * No world, entity movement, camera, or client prediction is simulated here.
 */
public final class HelicopterForceModelAcceptance {
    public static void main(String[] args) {
        run(HelicopterFlightProfile.mi24a(), HelicopterFlightProfile.mi24d(),
                HelicopterFlightProfile.ah1f(), HelicopterFlightProfile.mi26());
        System.out.println("PASS four helicopter force-model envelopes; world contact acceptance pending");
    }

    private static final double G = 9.80665;
    private static final Vec3 ZERO = new Vec3(0, 0, 0);

    private record Basis(Vec3 look, Vec3 forward, Vec3 right,
                         Vec3 up, double roll) {}
    private record Trim(double pitch, double collective) {}

    private static final class Case {
        final String id;
        final HelicopterFlightProfile profile;
        final HelicopterForceModel model;
        final double topKmh;
        final double bankDegrees;
        final double spoolDownPerSecond;

        Case(String id, HelicopterFlightProfile profile, double topKmh,
             double bankDegrees, double spoolDownPerSecond) {
            this.id = id;
            this.profile = profile;
            this.model = new HelicopterForceModel(profile);
            this.topKmh = topKmh;
            this.bankDegrees = bankDegrees;
            this.spoolDownPerSecond = spoolDownPerSecond;
        }
    }

    public static void run(HelicopterFlightProfile mi24a,
                           HelicopterFlightProfile mi24d,
                           HelicopterFlightProfile ah1f,
                           HelicopterFlightProfile mi26) {
        Case[] cases = {
            new Case("mi_24a", mi24a, 83.75, 30, 0.22),
            new Case("mi_24d", mi24d, 83.75, 30, 0.20),
            new Case("ah_1f", ah1f, 69.25, 35, 0.32),
            new Case("mi_26", mi26, 67.50, 25, 0.08)
        };

        for (Case c : cases) {
            basisChecks();
            hover(c);
            poweredTakeoff(c);
            cruise(c);
            bank(c);
            collectiveReduction(c);
            powerLoss(c);
            gravityOnce(c);
            currentMotionAuthority(c);
            landingAndGroundRest(c);
        }
    }

    /*
     * Native matrix order: R_y(-yaw) R_x(pitch) R_z(roll).
     * Positive pitch is nose down.
     * BVP's horizontal right is (-forward.z, 0, forward.x).
     * Translation/rotation-pivot offsets cancel for directions.
     */
    private static Basis basis(double yaw, double pitch, double roll) {
        double y = Math.toRadians(yaw);
        double p = Math.toRadians(pitch);
        double r = Math.toRadians(roll);
        double sy = Math.sin(y), cy = Math.cos(y);
        double sp = Math.sin(p), cp = Math.cos(p);
        double sr = Math.sin(r), cr = Math.cos(r);

        Vec3 forward = new Vec3(-sy, 0, cy);
        Vec3 right = new Vec3(-cy, 0, -sy);
        Vec3 look = new Vec3(-sy * cp, -sp, cy * cp);
        Vec3 up = new Vec3(
            -cy * sr - sy * sp * cr,
            cp * cr,
            -sy * sr + cy * sp * cr
        );
        return new Basis(look, forward, right, up, roll);
    }

    private static void basisChecks() {
        for (double yaw : new double[] {0, 90, -90, 179, -179}) {
            for (double pitch : new double[] {-30, 0, 15, 30}) {
                for (double roll : new double[] {-35, 0, 35}) {
                    Basis b = basis(yaw, pitch, roll);
                    near(dot(b.look, b.look), 1, 1e-12, "look unit");
                    near(dot(b.up, b.up), 1, 1e-12, "up unit");
                    near(dot(b.look, b.up), 0, 1e-12, "look/up orthogonal");
                    near(dot(b.forward, b.right), 0, 1e-12, "horizontal basis");
                }
            }
        }
    }

    private static HelicopterForceModel.Result evaluate(
            Case c, Vec3 previous, Vec3 requested, Basis b,
            double collective, double rotor, boolean wreck) {
        require(collective >= 0 && collective <= 1, "invalid collective");
        require(rotor >= 0 && rotor <= 1, "invalid rotor");

        // In the active force branch, Input.enginePower is physical collective,
        // not the native throttle/power field. Controller must remain null.
        HelicopterFlightController.Input input =
            new HelicopterFlightController.Input(
                previous, requested, b.look, b.forward, b.right, b.up,
                b.roll, rotor, collective,
                true, wreck,
                collective > 0.5, collective < 0.5,
                false, false, false
            );

        HelicopterForceModel.Result result = c.model.evaluate(input, null);
        finite(result.predictedMotion, c.id + " motion");
        finite(rawAcceleration(result), c.id + " raw acceleration");
        return result;
    }

    private static HelicopterForceModel.Result evaluate(
            Case c, Vec3 motion, Basis b, double collective, double rotor) {
        return evaluate(c, motion, motion, b, collective, rotor, false);
    }

    // Do not use netAccelMps2 for trim: it includes vertical envelope clamping.
    private static Vec3 rawAcceleration(HelicopterForceModel.Result r) {
        return add(add(add(add(r.gravityAccelMps2, r.mainRotorAccelMps2),
            r.wingLiftAccelMps2), r.tailRotorAccelMps2), r.dragAccelMps2);
    }

    private static void hover(Case c) {
        require(c.profile.maxMainRotorForceN() >
                c.profile.massKg() * G, c.id + " insufficient rotor force");

        for (double yaw : new double[] {0, 90, 179, -179}) {
            Basis b = basis(yaw, 0, 0);
            Vec3 motion = ZERO;
            double height = 100;
            for (int tick = 0; tick < 1200; tick++) {
                HelicopterForceModel.Result r =
                    evaluate(c, motion, b, 0.5, 1);
                near(length(rawAcceleration(r)), 0, 1e-9, c.id + " hover force");
                motion = r.predictedMotion;
                height += motion.f_82480_; // blocks/tick, not metres/second
            }
            near(height, 100, 1e-6, c.id + " 60-second hover");
            near(length(motion), 0, 1e-8, c.id + " hover motion");
        }
    }

    private static void poweredTakeoff(Case c) {
        // Rated rotor, full collective, collision-free clearance above a floor.
        // This proves thrust margin, not BvpHelicopterEntity's spool plumbing.
        Basis b = basis(0, 0, 0);
        Vec3 motion = ZERO;
        double height = 0;
        for (int tick = 0; tick < 200; tick++) {
            motion = evaluate(c, motion, b, 1, 1).predictedMotion;
            require(motion.f_82480_ > 0, c.id + " failed powered takeoff");
            require(motion.f_82480_ * 20 <=
                    c.profile.climbHardCapMps() + 1e-9, c.id + " climb bound");
            height += motion.f_82480_;
        }
        require(height > 10, c.id + " inadequate ten-second takeoff");
    }

    private static double verticalTrim(Case c, Basis b, Vec3 motion) {
        double lo = 0, hi = 1;
        double low = rawAcceleration(evaluate(c, motion, b, lo, 1)).f_82480_;
        double high = rawAcceleration(evaluate(c, motion, b, hi, 1)).f_82480_;
        if (low > 0 || high < 0) return Double.NaN;

        for (int i = 0; i < 48; i++) {
            double mid = (lo + hi) * 0.5;
            double ay = rawAcceleration(
                evaluate(c, motion, b, mid, 1)).f_82480_;
            if (ay < 0) lo = mid;
            else hi = mid;
        }
        return (lo + hi) * 0.5;
    }

    private static Trim findCruiseTrim(Case c) {
        Vec3 motion = new Vec3(0, 0, c.topKmh / 72.0);
        double previousPitch = Double.NaN;
        double previousFx = Double.NaN;

        // First feasible low-pitch sign bracket; never bridge an infeasible gap.
        for (int pitch = 0; pitch <= 30; pitch++) {
            Basis b = basis(0, pitch, 0);
            double collective = verticalTrim(c, b, motion);
            if (!Double.isFinite(collective)) {
                previousPitch = previousFx = Double.NaN;
                continue;
            }
            double fx = dot(rawAcceleration(
                evaluate(c, motion, b, collective, 1)), b.forward);

            if (Double.isFinite(previousFx) && previousFx <= 0 && fx >= 0) {
                double lo = previousPitch, hi = pitch;
                for (int n = 0; n < 40; n++) {
                    double mid = (lo + hi) * 0.5;
                    Basis mb = basis(0, mid, 0);
                    double mc = verticalTrim(c, mb, motion);
                    require(Double.isFinite(mc), c.id + " infeasible trim bracket");
                    double mx = dot(rawAcceleration(
                        evaluate(c, motion, mb, mc, 1)), mb.forward);
                    if (mx < 0) lo = mid;
                    else hi = mid;
                }
                double solvedPitch = (lo + hi) * 0.5;
                double solvedCollective =
                    verticalTrim(c, basis(0, solvedPitch, 0), motion);
                require(solvedPitch <= 25, c.id + " excessive cruise attitude");
                require(solvedCollective > 0.05 && solvedCollective < 0.95,
                        c.id + " nonphysical/saturated cruise collective");
                return new Trim(solvedPitch, solvedCollective);
            }
            previousPitch = pitch;
            previousFx = fx;
        }
        throw new AssertionError(c.id + " no physical cruise trim");
    }

    private static void cruise(Case c) {
        Trim trim = findCruiseTrim(c);
        for (double yaw : new double[] {0, 90, -179}) {
            Basis b = basis(yaw, trim.pitch, 0);
            Vec3 motion = scale(b.forward, c.topKmh / 72.0);
            double height = 100;
            for (int tick = 0; tick < 1200; tick++) {
                HelicopterForceModel.Result r =
                    evaluate(c, motion, b, trim.collective, 1);
                near(length(rawAcceleration(r)), 0, 1e-6, c.id + " raw trim");
                motion = r.predictedMotion; // no external speed/height clamp
                height += motion.f_82480_;
            }
            near(dot(motion, b.forward) * 72, c.topKmh, 0.01, c.id + " cruise");
            near(dot(motion, b.right), 0, 1e-8, c.id + " cruise sideslip");
            near(height, 100, 0.01, c.id + " cruise altitude");
        }
        System.out.printf("%s trim pitch=%.9f collective=%.12f%n",
                c.id, trim.pitch, trim.collective);
    }

    private static void bank(Case c) {
        for (double sign : new double[] {-1, 1}) {
            Basis b = basis(37, 0, sign * c.bankDegrees);
            double collective = verticalTrim(c, b, ZERO);
            require(Double.isFinite(collective) && collective < 1,
                    c.id + " bank cannot support weight");
            Vec3 acceleration =
                rawAcceleration(evaluate(c, ZERO, b, collective, 1));
            near(acceleration.f_82480_, 0, 1e-8, c.id + " bank vertical balance");
            require(dot(acceleration, b.right) * sign > 0,
                    c.id + " bank force direction");
        }
        // Actual yaw/roll response is native EngineInfo/entity behavior,
        // not something this translational kernel can certify.
    }

    private static void collectiveReduction(Case c) {
        Basis b = basis(0, 0, 0);
        Vec3 motion = ZERO;
        for (int tick = 0; tick < 400; tick++)
            motion = evaluate(c, motion, b, 0.49, 1).predictedMotion;
        double sink = motion.f_82480_ * 20;
        require(sink < -0.05 && sink > -1.0, c.id + " shallow descent");
    }

    private static void powerLoss(Case c) {
        Basis b = basis(0, 0, 0);
        Vec3 motion = ZERO;
        for (int tick = 1; tick <= 400; tick++) {
            // Prescribed input decay, not a duplicate controller implementation.
            double rotor = Math.max(0,
                1 - tick * c.spoolDownPerSecond / 20);
            HelicopterForceModel.Result r =
                evaluate(c, motion, b, 0.5, rotor);
            motion = r.predictedMotion;
            if (rotor == 0) {
                near(length(r.mainRotorAccelMps2), 0, 1e-12, "power-off rotor");
                near(length(r.wingLiftAccelMps2), 0, 1e-12, "power-off wing");
                near(length(r.tailRotorAccelMps2), 0, 1e-12, "power-off tail");
            }
        }
        require(motion.f_82480_ * 20 < -5, c.id + " power loss retained lift");
        require(motion.f_82480_ * 20 >= -13.5 - 1e-9, c.id + " descent bound");
        // The descent bound is not proof of autorotation or survivable landing.
    }

    private static void gravityOnce(Case c) {
        HelicopterForceModel.Result r = evaluate(
            c, ZERO, ZERO, basis(0, 0, 0), 1, 1, true);
        near(rawAcceleration(r).f_82480_, -G, 1e-12, c.id + " wreck gravity");
        near(r.predictedMotion.f_82480_, -G / 400, 1e-12, c.id + " one integration");
    }

    private static void currentMotionAuthority(Case c) {
        Basis b = basis(0, 0, 0);
        for (double speed : new double[] {-0.20, -0.06, -0.05, -0.02, -0.00001,
                                          0.00001, 0.02, 0.05, 0.06, 0.20}) {
            for (Vec3 previous : new Vec3[] {
                    new Vec3(speed, 0, 0), new Vec3(0, speed, 0), new Vec3(0, 0, speed)}) {
                // Floor, ceiling and wall responses must not depend on a velocity threshold.
                HelicopterForceModel.Result stopped = evaluate(c, previous, ZERO, b, 0.5, 1, false);
                near(length(stopped.predictedMotion), 0, 1e-12, c.id + " collision stop");
            }
        }
        Vec3 previous = new Vec3(0.01, -0.02, 0.03);
        for (Vec3 current : new Vec3[] {
                new Vec3(0.005, -0.01, 0.02), new Vec3(-0.01, 0.02, -0.03),
                new Vec3(0.02, -0.03, 0.04), ZERO}) {
            HelicopterForceModel.Result actual = evaluate(c, previous, current, b, 0.49, 1, false);
            HelicopterForceModel.Result reference = evaluate(c, current, current, b, 0.49, 1, false);
            near(length(add(actual.predictedMotion, scale(reference.predictedMotion, -1))),
                    0, 0, c.id + " newer current sample");
            near(length(add(rawAcceleration(actual), scale(rawAcceleration(reference), -1))),
                    0, 0, c.id + " current-sample force parity");
        }
    }

    private static void landingAndGroundRest(Case c) {
        Basis b = basis(0, 0, 0);
        for (double rotor : new double[] {0, 1}) {
            Vec3 motion = new Vec3(0, -0.02, 0);
            double height = 0.2;
            int firstContact = -1;
            for (int tick = 0; tick < 600; tick++) {
                // The real tick samples deltaMovementO from the post-collision current value.
                // This fixture models one flat-floor collision transaction, not Minecraft geometry.
                Vec3 previous = motion;
                Vec3 requested = motion;
                Vec3 proposed = evaluate(c, previous, requested, b, 0.5, rotor, false).predictedMotion;
                require(proposed.f_82480_ <= 1e-12, c.id + " uncommanded upward impulse");
                double resolvedY = Math.max(-height, proposed.f_82480_);
                height += resolvedY;
                boolean collision = resolvedY != proposed.f_82480_;
                motion = collision ? new Vec3(proposed.f_82479_, 0, proposed.f_82481_) : proposed;
                if (collision && firstContact < 0) firstContact = tick;
                require(height >= -1e-12, c.id + " floor penetration");
                if (firstContact >= 0) {
                    near(height, 0, 1e-10, c.id + " ground-rest height");
                    near(length(motion), 0, 1e-10, c.id + " post-collision rest");
                }
            }
            require(firstContact >= 0 && firstContact < 40, c.id + " missed shallow landing");
            Vec3 takeoff = evaluate(c, motion, motion, b, 1, 1, false).predictedMotion;
            require(takeoff.f_82480_ > 0, c.id + " ground contact retained after takeoff command");
        }
    }

    private static Vec3 add(Vec3 a, Vec3 b) {
        return new Vec3(a.f_82479_ + b.f_82479_,
                        a.f_82480_ + b.f_82480_,
                        a.f_82481_ + b.f_82481_);
    }
    private static Vec3 scale(Vec3 a, double s) {
        return new Vec3(a.f_82479_ * s, a.f_82480_ * s, a.f_82481_ * s);
    }
    private static double dot(Vec3 a, Vec3 b) {
        return a.f_82479_ * b.f_82479_ +
               a.f_82480_ * b.f_82480_ +
               a.f_82481_ * b.f_82481_;
    }
    private static double length(Vec3 v) { return Math.sqrt(dot(v, v)); }
    private static void finite(Vec3 v, String message) {
        require(Double.isFinite(v.f_82479_) && Double.isFinite(v.f_82480_) &&
                Double.isFinite(v.f_82481_), message);
    }
    private static void near(double a, double b, double epsilon, String message) {
        require(Double.isFinite(a) && Math.abs(a - b) <= epsilon,
                message + ": actual=" + a + " expected=" + b);
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
