package com.yourname.berts_vehicle_pack.entity.aircraft;

import com.atsuishio.superbwarfare.api.vehicle.flight.FixedWingAtmosphere;
import com.atsuishio.superbwarfare.api.vehicle.flight.FixedWingFlightModel;
import com.atsuishio.superbwarfare.api.vehicle.flight.FixedWingHandlingProfile;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

public final class BvpAircraftFlightProfilesTest {
    private static int assertions;

    public static void main(String[] args) throws Exception {
        JsonArray fixtures = JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonArray();
        check(fixtures.size() > 0, "At least one real aircraft fixture");
        int failures = 0;
        for (JsonElement fixture : fixtures) {
            try {
            JsonObject row = fixture.getAsJsonObject(), resource = row.getAsJsonObject("resource");
            String id = row.get("id").getAsString();
            BvpAircraftFlightProfiles.Binding binding = decode(resource);
            FixedWingHandlingProfile h = binding.handling();
            JsonObject reference = resource.getAsJsonObject("reference"), engineering = resource.getAsJsonObject("engineering");
            near(h.getSimulationLengthScale(), 0.25, 1e-12, id + " scale");
            near(h.getGravityMps2(), 9.80665 * 0.25, 1e-12, id + " gravity");
            near(h.getMaximumSpeedMps(), reference.get("maximumTrueAirspeedKmh").getAsDouble() / 3.6 * 0.25,
                    1e-10, id + " true speed");
            near(binding.reference().getMassKg(), reference.get("fullFuelMassKg").getAsDouble(), 1e-10, id + " reference mass");
            near(h.getPitchRateDegreesPerSecond(), engineering.get("pitchRateDegreesPerSecond").getAsDouble(),
                    1e-10, id + " angular rate unscaled");
            near(h.getTrimSpeedMps(), engineering.get("controlReferenceSpeedMps").getAsDouble() * 0.25,
                    1e-10, id + " control reference scaled once");
            double sourceIndicated = reference.get("maximumIndicatedAirspeedKmh").getAsDouble() / 3.6;
            double casCalibration = engineering.has("pitotCasCalibrationFactor")
                    ? engineering.get("pitotCasCalibrationFactor").getAsDouble() : 1;
            near(binding.reference().getMaxStructuralSpeedMps(), sourceIndicated, 1e-10,
                    id + " source IAS preserved");
            near(h.getMaximumIndicatedSpeedMps(), sourceIndicated * casCalibration * .25, 1e-10,
                    id + " calibrated CAS scaled once");
            if (!engineering.has("pitotCasCalibrationFactor")) {
                JsonObject identity = resource.deepCopy();
                identity.getAsJsonObject("engineering").addProperty("pitotCasCalibrationFactor", 1);
                check(decode(identity).equals(binding), id + " omitted calibration retains exact identity");
            }
            near(h.getDryAccelerationMps2(), engineering.get("staticOrEquivalentThrustNewtons").getAsDouble()
                    / binding.reference().getMassKg() * 0.25, 1e-10, id + " acceleration scaled once");
            reject(resource, value -> value.addProperty("lengthScale", 1.0));
            reject(resource, value -> value.addProperty("handlingProfileId", "berts_vehicle_pack:flight_handling/mig19"));
            reject(resource, value -> value.getAsJsonObject("engineering").addProperty("sideDragPerMetre", Double.NaN));
            reject(resource, value -> value.getAsJsonObject("reference").addProperty("fullFuelMassKg", -1));
            reject(resource, value -> value.getAsJsonObject("reference").addProperty("engineCount", 1.5));
            reject(resource, value -> value.getAsJsonObject("engineering").addProperty("staticOrEquivalentThrustNewtons", 1));
            reject(resource, value -> value.getAsJsonObject("engineering").addProperty("afterburnerEnabled", "false"));
            reject(resource, value -> value.getAsJsonObject("engineering").addProperty("unknown", 1));
            reject(resource, value -> value.getAsJsonObject("engineering").remove("sideDragPerMetre"));
            reject(resource, value -> value.getAsJsonObject("engineering").addProperty("pitotCasCalibrationFactor", .99));
            reject(resource, value -> value.getAsJsonObject("engineering").addProperty("pitotCasCalibrationFactor", 1.1001));
            reject(resource, value -> value.getAsJsonObject("engineering").addProperty("pitotCasCalibrationFactor", Double.NaN));
            reject(resource, value -> value.getAsJsonObject("engineering").addProperty("controlReferenceSpeedMps", 0));
            reject(resource, value -> value.getAsJsonObject("engineering").addProperty("controlReferenceSpeedMps",
                    reference.get("maximumTrueAirspeedKmh").getAsDouble() / 3.6));
            String envelope = row.has("testEnvelope") ? row.get("testEnvelope").getAsString() : "FIGHTER";
            check(envelope.equals("FIGHTER") || envelope.equals("TRANSPORT_BOMBER"), "Known flight test envelope");
            runModel(id, resource, h, envelope.equals("TRANSPORT_BOMBER"));
            } catch (AssertionError | IllegalArgumentException failure) {
                failures++;
                System.out.println("FAIL " + fixture.getAsJsonObject().get("id").getAsString() + ": " + failure.getMessage());
            }
        }
        check(failures == 0, failures + " aircraft fixtures failed; inspect individual diagnostics above");
        System.out.println("PASS aircraft adapter and production-model checks: " + assertions);
    }

    private static BvpAircraftFlightProfiles.Binding decode(JsonObject root) {
        return BvpAircraftFlightProfiles.decode(root, root.get("referenceProfileId").getAsString(),
                root.get("handlingProfileId").getAsString());
    }

    private static void reject(JsonObject resource, Consumer<JsonObject> mutation) {
        JsonObject invalid = resource.deepCopy();
        mutation.accept(invalid);
        try { decode(invalid); } catch (IllegalArgumentException expected) { assertions++; return; }
        throw new AssertionError("Malformed aircraft binding was accepted");
    }

    private static void runModel(String id, JsonObject resource, FixedWingHandlingProfile h, boolean transport) {
        double lastAuthority = -1;
        for (double ratio : new double[]{0, 0.2, 0.4, 0.7, 1, 2}) {
            Flight authority = new Flight(h, h.getLiftReferenceSpeedMps() * ratio, 0, 1, 288.15);
            authority.model.reset(0, 0, 0);
            authority.step(1, 1, 0, 0, false);
            double value = authority.model.getControlEffectiveness();
            check(value >= lastAuthority, id + " monotonic airflow authority");
            if (ratio <= 0.4) near(value, 0, 1e-12, id + " no authority below minimum airspeed");
            lastAuthority = value;
        }
        double referenceAltitude = resource.getAsJsonObject("reference").get("referenceAltitudeMetres").getAsDouble();
        double density = FixedWingAtmosphere.densityRatio(referenceAltitude);
        double temperature = FixedWingAtmosphere.temperatureKelvin(referenceAltitude);
        boolean hasAfterburner = resource.getAsJsonObject("engineering").get("afterburnerEnabled").getAsBoolean();
        Flight level = new Flight(h, h.getMaximumSpeedMps(), 0, density, temperature);
        level.afterburner = hasAfterburner;
        double targetAltitude = level.altitude;
        for (int tick = 0; tick < 2400; tick++) level.levelStep(targetAltitude, 0, 1, 1);
        System.out.printf("%s reference level: %.6f km/h target %.6f, altitude drift %.4f, AoA %.4f, stall %s%n",
                id, level.model.getSpeedMps() * 3.6, h.getMaximumSpeedMps() * 3.6,
                level.altitude - targetAltitude, level.model.getAngleOfAttackDegrees(), level.model.getStallActive());
        near(level.model.getSpeedMps(), h.getMaximumSpeedMps(), h.getMaximumSpeedMps() * 0.05, id + " maximum speed equilibrium");
        check(Math.abs(level.altitude - targetAltitude) < 2, id + " altitude hold");
        check(!level.model.getStallActive(), id + " reference level flight not stalled");

        if (hasAfterburner) {
            Flight dry = new Flight(h, h.getTrimSpeedMps(), 0, 1, 288.15);
            Flight burning = new Flight(h, h.getTrimSpeedMps(), 0, 1, 288.15);
            burning.afterburner = true;
            for (int tick = 0; tick < 400; tick++) {
                dry.levelStep(500, 0, 1, 1);
                burning.levelStep(500, 0, 1, 1);
            }
            check(burning.model.getAfterburnerActive(), id + " afterburner engages at full throttle");
            check(!dry.model.getAfterburnerActive(), id + " dry throttle does not engage afterburner");
            check(burning.model.getSpeedMps() > dry.model.getSpeedMps(), id + " afterburner improves acceleration");
            burning.afterburner = false;
            burning.levelStep(500, 0, 1, 1);
            check(!burning.model.getAfterburnerActive(), id + " afterburner cancels immediately");
        }

        for (double onset : new double[]{0.8, 0.9, 1.0}) {
            Flight takeoff = new Flight(h, 0, 0, 1, 288.15);
            takeoff.afterburner = hasAfterburner;
            takeoff.altitude = 0;
            double run = 0, separation = Double.NaN, clearance = Double.NaN;
            for (int tick = 0; tick < (transport ? 2400 : 800); tick++) {
                boolean grounded = takeoff.altitude <= 0;
                double pitch = Math.hypot(takeoff.vx, takeoff.vz) > h.getLiftReferenceSpeedMps() * onset
                        ? takeoff.pitchTargetForAttitude(-12) : 0;
                takeoff.step(pitch, 0, 1, 1, grounded);
                run += Math.hypot(takeoff.vx, takeoff.vz) * 0.05;
                if (!Double.isFinite(separation) && takeoff.altitude > 1e-6) separation = run;
                if (takeoff.altitude > 0.1) { clearance = run; break; }
            }
            System.out.printf("%s takeoff rotation %.1f VL: separation %.4f clearance %.4f blocks, speed %.4f m/s, AoA %.4f, pitch-rate %.4f, authority %.4f%n",
                    id, onset, separation, clearance, takeoff.model.getSpeedMps(), takeoff.model.getAngleOfAttackDegrees(),
                    takeoff.model.getPitchRateDegreesPerSecond(), takeoff.model.getControlEffectiveness());
            check(Double.isFinite(clearance), id + " must leave the ground");
        }

        Flight straight = new Flight(h, h.getTrimSpeedMps(), 0, 1, 288.15);
        Flight roll = new Flight(h, h.getTrimSpeedMps(), 0, 1, 288.15);
        Flight pitch = new Flight(h, h.getTrimSpeedMps(), 0, 1, 288.15);
        for (int tick = 0; tick < 200; tick++) {
            straight.step(0, 0, 0, 0, false);
            roll.step(0, 0.65, 0, 0, false);
            pitch.step(0.65, 0, 0, 0, false);
        }
        System.out.printf("%s aerodynamic work/energy: straight %.4f roll %.4f pitch %.4f%n",
                id, straight.dragLoss, roll.dragLoss, pitch.dragLoss);
        check(pitch.dragLoss > roll.dragLoss, id + " sustained pitch costs more than roll");
        check(roll.dragLoss < straight.dragLoss * 1.35, id + " roll preserves energy");

        for (double bank : transport ? new double[]{-30, 0, 30} : new double[]{-60, 60, 180}) {
            Flight banked = new Flight(h, h.getTrimSpeedMps(), bank, 1, 288.15);
            double altitude = banked.altitude;
            for (int tick = 0; tick < (transport ? 1200 : 600); tick++) banked.levelStep(altitude, bank, 1, 1);
            System.out.printf("%s bank %.0f: speed %.4f, altitude drift %.4f, bank %.4f, stall %s%n", id, bank,
                    banked.model.getSpeedMps(), banked.altitude - altitude, banked.model.getRollDegrees(), banked.model.getStallActive());
            check(!banked.model.getStallActive(), id + " bank recovers attached airflow");
            check(Math.abs(banked.altitude - altitude) < 10, id + " bank/inverted lift supports flight");
        }

        if (transport) {
            Flight inverted = new Flight(h, h.getTrimSpeedMps(), 180, 1, 288.15);
            inverted.altitude = 3000;
            double minimumLift = resource.getAsJsonObject("reference").get("negativeGLimit").getAsDouble()
                    * h.getGravityMps2();
            for (int tick = 0; tick < 600; tick++) {
                inverted.levelStep(3000, 180, 1, 1);
                check(inverted.model.getLiftAccelerationMps2() >= minimumLift - 1e-8,
                        id + " inverted demand respects negative load cap");
            }
            check(inverted.altitude < 2990, id + " low-load transport cannot sustain inverted altitude hold");
            System.out.printf("%s inverted overload: altitude loss %.4f, negative load limit %.2f%n",
                    id, 3000 - inverted.altitude, minimumLift / h.getGravityMps2());
        }

        for (double engine : new double[]{0, 1}) {
            Flight stalled = new Flight(h, h.getLiftReferenceSpeedMps() * 0.6, 0, 1, 288.15);
            stalled.model.reset(0, -35, 0);
            if (transport) stalled.altitude = 3000;
            double altitude = stalled.altitude;
            boolean sawStall = false, recovered = false;
            for (int tick = 0; tick < (transport ? 1800 : 600); tick++) {
                double recoveryPitch = clamp(-stalled.model.getAngleOfAttackDegrees() * 0.12
                        - stalled.model.getPitchRateDegreesPerSecond() * 0.015, -0.85, 0.85);
                if (tick == 0) recoveryPitch = -0.85;
                if (recovered && !stalled.model.getStallActive() && Math.abs(stalled.model.getRollDegrees()) < 60) {
                    recoveryPitch = clamp((stalled.model.getPitchDegrees() - 5) * 0.04
                            - stalled.model.getPitchRateDegreesPerSecond() * 0.02, -0.85, 0.85);
                    if (stalled.model.getAngleOfAttackDegrees() > 12 && recoveryPitch > 0) recoveryPitch = 0;
                }
                stalled.step(recoveryPitch, clamp(wrap(stalled.model.getRollDegrees()) * 0.015, -0.65, 0.65), engine, engine, false);
                sawStall |= stalled.model.getStallActive();
                if (tick % 100 == 0) System.out.printf("%s recovery t=%d engine=%.0f speed=%.3f pitch=%.3f alpha=%.3f stall=%s authority=%.4f%n",
                        id, tick, engine, stalled.model.getSpeedMps(), stalled.model.getPitchDegrees(),
                        stalled.model.getAngleOfAttackDegrees(), stalled.model.getStallActive(), stalled.model.getControlEffectiveness());
                if (sawStall && !stalled.model.getStallActive()
                        && stalled.model.getSpeedMps() >= h.getRecoverySpeedMps()) recovered = true;
            }
            System.out.printf("%s stall engine %.0f: observed %s recovered %s altitude loss %.4f speed %.4f%n",
                    id, engine, sawStall, recovered, altitude - stalled.altitude, stalled.model.getSpeedMps());
            check(sawStall && recovered, id + " powered/unpowered stall recovery");
            check(stalled.altitude < altitude, id + " stall recovery costs altitude");
        }

        double bestClimb = 0, bestAngle = 0;
        double[] climbAngles = hasAfterburner ? new double[]{5, 10, 15, 20, 25, 30, 40, 50, 60, 75}
                : new double[]{5, 10, 15, 20, 25, 30};
        int climbTicks = hasAfterburner ? 3600 : 1200;
        double climbEntrySpeed = hasAfterburner
                ? Math.min(0.65 * h.getMaximumSpeedMps(), 0.8 * h.getMaximumIndicatedSpeedMps())
                : h.getTrimSpeedMps() * 1.3;
        for (double angle : climbAngles) {
            Flight climb = new Flight(h, climbEntrySpeed, 0, 1, 288.15);
            climb.afterburner = hasAfterburner;
            double sum = 0, speedSum = 0, pathSum = 0;
            for (int tick = 0; tick < climbTicks; tick++) {
                climb.pathStep(angle, 1, 1);
                if (tick >= climbTicks - 200) {
                    sum += climb.vy;
                    speedSum += climb.model.getSpeedMps();
                    pathSum += Math.toDegrees(Math.atan2(climb.vy, Math.hypot(climb.vx, climb.vz)));
                }
            }
            double mean = sum / 200;
            if (hasAfterburner) System.out.printf("%s climb command %.0f: settled vertical %.4f, speed %.4f m/s, path %.4f degrees%n",
                    id, angle, mean, speedSum / 200, pathSum / 200);
            if (mean > bestClimb) { bestClimb = mean; bestAngle = angle; }
        }
        double targetClimb = resource.getAsJsonObject("reference").get("climbMetresPerSecond").getAsDouble() * 0.25;
        System.out.printf("%s sea-level-condition climb: %.4f m/s target %.4f at %.0f-degree path%n", id, bestClimb, targetClimb, bestAngle);
        check(bestClimb > targetClimb * 0.6, id + " sustained powered climb");

        double[] speeds = new double[3];
        for (int i = 0; i < speeds.length; i++) {
            Flight variation = new Flight(h, h.getTrimSpeedMps(), 0, 1, 288.15);
            double desired = new double[]{0, 0.5, 1}[i];
            for (int tick = 0; tick < 400; tick++) variation.pathStep(-5,
                    Math.signum(desired - variation.model.getThrottle()), 1);
            speeds[i] = variation.model.getSpeedMps();
        }
        System.out.printf("%s descending throttle 0/.5/1 speeds: %.4f %.4f %.4f m/s%n", id, speeds[0], speeds[1], speeds[2]);
        check(speeds[0] < speeds[1] && speeds[1] < speeds[2], id + " throttle changes energy gain");

        Flight landing = new Flight(h, h.getLiftReferenceSpeedMps() * 1.35, 0, 1, 288.15);
        landing.altitude = 10;
        double touchdownVertical = Double.NaN;
        for (int tick = 0; tick < 2400; tick++) {
            if (landing.altitude > 0) {
                landing.pathStep(landing.altitude < 1.5 ? 0 : -3, -1, 0);
                if (landing.altitude <= 0) {
                    touchdownVertical = landing.vy;
                    landing.altitude = 0; landing.vy = 0;
                }
            } else {
                landing.brake = true;
                landing.step(0, 0, -1, 0, true);
                if (Math.hypot(landing.vx, landing.vz) < 0.1) break;
            }
        }
        System.out.printf("%s landing: touchdown %.4f m/s, final speed %.4f m/s%n", id, touchdownVertical, landing.model.getSpeedMps());
        check(Double.isFinite(touchdownVertical) && touchdownVertical > -1.5, id + " bounded landing sink rate");
        check(landing.model.getSpeedMps() < 0.5, id + " brakes stop landing roll");
    }

    private static final class Flight {
        final FixedWingHandlingProfile h;
        final FixedWingFlightModel model;
        final double density, temperature;
        double vx, vy, vz, altitude = 500, dragLoss;
        boolean brake, afterburner;
        long tick;

        Flight(FixedWingHandlingProfile handling, double speed, double roll, double density, double temperature) {
            h = handling; model = new FixedWingFlightModel(h); this.density = density; this.temperature = temperature;
            double alpha = speed > 0 ? Math.pow(h.getLiftReferenceSpeedMps() / speed, 2)
                    / (density * h.getNormalizedLiftSlopePerDegree()) : 0;
            model.reset(0, -alpha, roll); vz = speed;
        }

        void levelStep(double targetAltitude, double targetRoll, double throttle, double engine) {
            double speed = Math.max(Math.hypot(Math.hypot(vx, vy), vz), h.getMinimumControlSpeedMps());
            double bank = -model.getRollDegrees() * Math.PI / 180;
            double elevation = -model.getPitchDegrees() * Math.PI / 180;
            double cosine = Math.cos(bank), targetCosine = Math.cos(targetRoll * Math.PI / 180);
            double alpha = clamp(Math.pow(h.getLiftReferenceSpeedMps() / speed, 2)
                    / (density * h.getNormalizedLiftSlopePerDegree() * targetCosine),
                    -0.98 * h.getStallAngleDegrees(), 0.98 * h.getStallAngleDegrees());
            double trim = Math.atan(targetCosine * Math.tan(alpha * Math.PI / 180));
            double climb = clamp((targetAltitude - altitude) * 0.35, -5, 5);
            double requestedPitch = -(trim + Math.asin(clamp(climb / speed, -0.8, 0.8))) * 180 / Math.PI;
            double pitchRate = ((model.getPitchDegrees() - requestedPitch) * 3
                    + model.getYawRateDegreesPerSecond() * Math.sin(bank)) / cosine;
            double rollRate = wrap(model.getRollDegrees() - targetRoll) * 3
                    - Math.tan(elevation) * (model.getPitchRateDegreesPerSecond() * Math.sin(bank)
                    + model.getYawRateDegreesPerSecond() * cosine);
            double authority = Math.max(model.getControlEffectiveness(), 0.05);
            double demand = clamp(pitchRate / (h.getPitchRateDegreesPerSecond() * authority), -1, 1);
            double linear = h.getPitchResponseLinearFraction();
            double magnitude = (Math.sqrt(linear * linear + 4 * (1 - linear) * Math.abs(demand)) - linear)
                    / (2 * (1 - linear));
            step(surface(Math.signum(demand) * magnitude), surface(rollRate / (h.getRollRateDegreesPerSecond() * authority)),
                    throttle, engine, false);
        }

        double surface(double value) {
            return value == 0 ? 0 : Math.signum(value) * (h.getStickDeadzone()
                    + (1 - h.getStickDeadzone()) * Math.min(1, Math.abs(value)));
        }

        double pitchTargetForAttitude(double targetPitch) {
            double demand = clamp((model.getPitchDegrees() - targetPitch) * 3
                    / (h.getPitchRateDegreesPerSecond() * Math.max(model.getControlEffectiveness(), 0.05)), -1, 1);
            double linear = h.getPitchResponseLinearFraction();
            double magnitude = (Math.sqrt(linear * linear + 4 * (1 - linear) * Math.abs(demand)) - linear)
                    / (2 * (1 - linear));
            return surface(Math.signum(demand) * magnitude);
        }

        void pathStep(double flightPathDegrees, double throttle, double engine) {
            double speed = Math.max(Math.hypot(Math.hypot(vx, vy), vz), h.getMinimumControlSpeedMps());
            double alpha = clamp(Math.pow(h.getLiftReferenceSpeedMps() / speed, 2)
                    / (density * h.getNormalizedLiftSlopePerDegree()), 0, h.getPitchProtectionAngleDegrees());
            step(pitchTargetForAttitude(-(flightPathDegrees + alpha)),
                    clamp(wrap(model.getRollDegrees()) * 0.015, -0.65, 0.65), throttle, engine, false);
        }

        void step(double pitch, double roll, double throttle, double engine, boolean grounded) {
            double decay = Math.exp(-h.getAutomaticReturnPerSecond() * 0.05);
            double pitchDelta = (pitch - model.getVirtualPitchTarget() * decay) / h.getStickSensitivity();
            double rollDelta = (roll - model.getVirtualRollTarget() * decay) / h.getStickSensitivity();
            check(model.step(++tick, vx, vy, vz, grounded, true, throttle, pitchDelta, rollDelta,
                    0, brake, afterburner, false, engine, 0, density, temperature), "Model admission");
            vx = model.getVelocityX(); vy = model.getVelocityY(); vz = model.getVelocityZ();
            altitude += vy * 0.05;
            if (grounded && altitude < 0) { altitude = 0; vy = Math.max(0, vy); }
            dragLoss -= model.getStepDragWorkPerKg() + model.getStepSideWorkPerKg();
            check(Double.isFinite(altitude) && Double.isFinite(model.getSpeedMps()), "Finite model state");
        }
    }

    private static double wrap(double angle) { return ((angle + 180) % 360 + 360) % 360 - 180; }
    private static double clamp(double value, double low, double high) { return Math.max(low, Math.min(high, value)); }
    private static void near(double value, double expected, double tolerance, String label) {
        check(Math.abs(value - expected) <= tolerance, label + ": " + value + " versus " + expected);
    }
    private static void check(boolean condition, String message) {
        assertions++; if (!condition) throw new AssertionError(message);
    }
}
