package com.yourname.berts_vehicle_pack.diagnostics;

import com.google.gson.Gson;
import com.atsuishio.superbwarfare.client.input.FixedWingMouseAimMath;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** Bounded world-aim input plans: registered key edges and raw mouse pixels, never flight poses. */
public final class BvpFlightClientPlan {
    public static final int MAX_BYTES = 65_536;
    public static final int MAX_TICKS = 4_600;
    public boolean clientInput;
    public boolean worldAim;
    public boolean preparePoseOnClientArm;
    public double yaw;
    public double pitch;
    public double roll;
    public String vehicleId = "berts_vehicle_pack:mig19";
    public List<Stage> stages;

    public enum Throttle { UP, HOLD, DOWN }
    public enum View { FIRST_PERSON, THIRD_PERSON_BACK, THIRD_PERSON_FRONT }

    public static final class Stage {
        public String name;
        public int ticks;
        public Throttle clientThrottle = Throttle.HOLD;
        public double clientMouseX;
        public double clientMouseY;
        public int clientMouseTicks;
        /** Opt-in pixels per 20 Hz tick, distributed over actual rendered frames. */
        public boolean clientMouseRenderRate;
        /** Optional world waypoint, approached only through bounded ordinary mouse callbacks. */
        public Double clientAimYaw;
        public Double clientAimPitch;
        public double clientAimToleranceDegrees = 0.75;
        /** Pitch down/up and roll left/right use bits 1, 2, 4 and 8 (default W/S/D/A). */
        public int manualMask;
        public int rudder;
        public boolean brake;
        public boolean afterburner;
        public boolean recenter;
        public boolean landingGear;
        public boolean freeCamera;
        public int clientGuiTicks;
        public boolean expectFocusLoss;
        public boolean dismount;
        public boolean disableEngine;
        public View clientView = View.THIRD_PERSON_BACK;

        public void validate() {
            if (name == null || !name.matches("[A-Za-z0-9_-]{1,40}")
                    || ticks < 1 || ticks > 1_200 || clientThrottle == null || clientView == null
                    || rudder != 0 || (manualMask & ~15) != 0 || clientMouseTicks < 0 || clientMouseTicks > ticks
                    || clientGuiTicks < 0 || clientGuiTicks > 20 || clientGuiTicks >= ticks
                    || !Double.isFinite(clientMouseX) || Math.abs(clientMouseX) > FixedWingMouseAimMath.MAX_PIXELS_PER_SAMPLE
                    || !Double.isFinite(clientMouseY) || Math.abs(clientMouseY) > FixedWingMouseAimMath.MAX_PIXELS_PER_SAMPLE
                    || dismount || disableEngine)
                throw new IllegalArgumentException("Invalid client flight stage");
            if (clientMouseTicks == 0 && (clientMouseX != 0 || clientMouseY != 0))
                throw new IllegalArgumentException("Mouse pulse requires a bounded duration");
            if (clientMouseRenderRate && clientMouseTicks == 0)
                throw new IllegalArgumentException("Render-rate mouse requires a bounded pulse");
            if (clientAimYaw != null || clientAimPitch != null) {
                if (clientAimYaw == null || clientAimPitch == null
                        || !Double.isFinite(clientAimYaw) || Math.abs(clientAimYaw) > 180
                        || !Double.isFinite(clientAimPitch) || Math.abs(clientAimPitch) > 80
                        || !Double.isFinite(clientAimToleranceDegrees)
                        || clientAimToleranceDegrees < 0.1 || clientAimToleranceDegrees > 2
                        || clientMouseTicks == 0 || clientMouseX != 0 || clientMouseY != 0
                        || clientMouseRenderRate || freeCamera || expectFocusLoss || clientGuiTicks > 0)
                    throw new IllegalArgumentException("Waypoint requires a bounded isolated tick mouse interval");
            }
            if (recenter && (clientMouseX != 0 || clientMouseY != 0))
                throw new IllegalArgumentException("Recenter and mouse pulse must be separate stages");
            if (afterburner && (ticks < 6 || clientThrottle != Throttle.HOLD || recenter || landingGear))
                throw new IllegalArgumentException("Afterburner uses a separate bounded Shift double-tap stage");
            if (clientGuiTicks > 0 && (manualMask != 0 || clientMouseTicks != 0 || recenter || landingGear
                    || afterburner || clientThrottle != Throttle.HOLD || brake || expectFocusLoss))
                throw new IllegalArgumentException("GUI release must be an otherwise neutral stage");
            if (expectFocusLoss && (clientMouseTicks != 0 || recenter || landingGear || afterburner))
                throw new IllegalArgumentException("Focus-loss observation cannot enqueue transient actions");
            if (freeCamera && (manualMask != 0 || clientGuiTicks != 0 || expectFocusLoss || recenter || landingGear || afterburner))
                throw new IllegalArgumentException("Free-camera probe requires an isolated held-view stage");
        }

        public double mouseX(int tick) { return tick < clientMouseTicks ? clientMouseX : 0; }
        public double mouseY(int tick) { return tick < clientMouseTicks ? clientMouseY : 0; }
        public boolean gui(int tick) { return tick < clientGuiTicks; }
        public boolean center(int tick) { return recenter && tick == 0; }
        public boolean gear(int tick) { return landingGear && tick == 0; }
        public Throttle throttle(int tick) {
            return afterburner ? (tick == 0 || tick == 2 ? Throttle.UP : Throttle.HOLD) : clientThrottle;
        }
        /** Mapping order is Shift, Ctrl (throttle/brakes), W, S, D, A, G, C, Home. */
        public boolean[] keyStates(int tick) {
            if (gui(tick)) return new boolean[9];
            return new boolean[]{throttle(tick) == Throttle.UP, throttle(tick) == Throttle.DOWN || brake,
                    (manualMask & 1) != 0, (manualMask & 2) != 0, (manualMask & 4) != 0,
                    (manualMask & 8) != 0, gear(tick), freeCamera, center(tick)};
        }
        public int expectedBits(int tick) {
            if (gui(tick)) return 0;
            Throttle throttle = throttle(tick);
            int bits = throttle == Throttle.UP ? 4 : 0;
            if (throttle == Throttle.DOWN || brake) bits |= 8 | 32;
            // Shared plane bindings also emit the normal movement packet's lateral bits.
            if ((manualMask & 4) != 0) bits |= 1;
            if ((manualMask & 8) != 0) bits |= 2;
            return bits;
        }
    }

    public void validate() {
        if (preparePoseOnClientArm && (!Double.isFinite(yaw) || Math.abs(yaw) > 180
                || !Double.isFinite(pitch) || Math.abs(pitch) > 80
                || !Double.isFinite(roll) || Math.abs(roll) > 180))
            throw new IllegalArgumentException("Invalid declared initial attitude");
        if (!clientInput || !worldAim || vehicleId == null || vehicleId.length() > 128
                || !vehicleId.matches("berts_vehicle_pack:[a-z0-9_./-]+")
                || stages == null || stages.isEmpty() || stages.size() > 32)
            throw new IllegalArgumentException("Expected 1-32 explicit world-aim client stages and exact BVP aircraft");
        int ticks = 0;
        for (Stage stage : stages) {
            if (stage == null) throw new IllegalArgumentException("Null stage");
            stage.validate(); ticks += stage.ticks;
            if (stage.expectFocusLoss && stage != stages.get(stages.size() - 1))
                throw new IllegalArgumentException("Expected focus loss must be the final stage");
        }
        if (ticks > MAX_TICKS) throw new IllegalArgumentException("Client plan exceeds tick bound");
    }

    /** Readiness compares wrapped Euler branches in the fixture's declared initial pose. */
    public boolean matchesSetupPose(double actualYaw, double actualPitch, double actualRoll) {
        return angularDistance(actualYaw, yaw) <= 2.5 && angularDistance(actualPitch, pitch) <= 2.5
                && angularDistance(actualRoll, roll) <= 2.5;
    }

    private static double angularDistance(double actual, double expected) {
        if (!Double.isFinite(actual) || !Double.isFinite(expected)) return Double.POSITIVE_INFINITY;
        return Math.abs(Math.IEEEremainder(actual - expected, 360.0));
    }

    public record Loaded(BvpFlightClientPlan plan, String sha256) { }

    public static Loaded load(Path configRoot, String label) throws IOException {
        if (label == null || !label.matches("[A-Za-z0-9_-]{1,40}"))
            throw new IllegalArgumentException("Invalid plan label");
        Path file = configRoot.resolve(label + ".json");
        if (Files.size(file) > MAX_BYTES) throw new IllegalArgumentException("Client plan too large");
        byte[] bytes;
        try (var input = Files.newInputStream(file)) { bytes = input.readNBytes(MAX_BYTES + 1); }
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("Client plan grew beyond bound");
        BvpFlightClientPlan plan = new Gson().fromJson(new String(bytes, StandardCharsets.UTF_8), BvpFlightClientPlan.class);
        if (plan == null) throw new IllegalArgumentException("Empty client plan");
        plan.validate();
        try {
            return new Loaded(plan, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
