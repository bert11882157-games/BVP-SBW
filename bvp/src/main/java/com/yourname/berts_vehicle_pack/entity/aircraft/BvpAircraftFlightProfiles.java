package com.yourname.berts_vehicle_pack.entity.aircraft;

import com.atsuishio.superbwarfare.api.vehicle.flight.FixedWingAtmosphere;
import com.atsuishio.superbwarfare.api.vehicle.flight.FixedWingFlightProfile;
import com.atsuishio.superbwarfare.api.vehicle.flight.FixedWingHandlingProfile;
import com.atsuishio.superbwarfare.api.vehicle.flight.FixedWingReferenceHandling;
import com.atsuishio.superbwarfare.api.vehicle.flight.FixedWingTakeoffHandling;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.DoubleFunction;

/** Loads immutable, packaged aircraft reference/handling pairs; no fallback aircraft is substituted. */
public final class BvpAircraftFlightProfiles {
    private static final String SCHEMA = "berts_vehicle_pack:aircraft_flight_binding/v1";
    private static final double GRAVITY = 9.80665;
    private static final Set<String> REFERENCE_FIELDS = fields(
            "maximumTrueAirspeedKmh referenceAltitudeMetres climbMetresPerSecond turnSeconds "
            + "takeoffRunMetres baseMassKg mainFuelMassKg fullFuelWingLoadingKgPerSquareMetre "
            + "maximumIndicatedAirspeedKmh negativeGLimit positiveGLimit engineCount "
            + "dryThrustKgfPerEngine afterburnerThrustKgfPerEngine normalPowerHpPerEngine "
            + "maximumPowerHpPerEngine fullFuelMassKg wingAreaSquareMetres");
    private static final Set<String> ENGINEERING_FIELDS = fields(
            "effectivePropulsiveEfficiency zeroLiftDragCoefficient inducedDragFactor maximumLiftCoefficient "
            + "liftSlopePerRadian stallAngleDegrees recoveryAngleDegrees protectionAngleDegrees "
            + "stallDragCoefficient stallRecoveryTicks assumedOswaldEfficiency referenceWingSpanMetres "
            + "pitchRateDegreesPerSecond rollRateDegreesPerSecond rudderRateDegreesPerSecond "
            + "controlResponsePerSecond controlReferenceSpeedMps propellerPowerReferenceSpeedMps staticOrEquivalentThrustNewtons "
            + "thrustDensityExponent thrustDensityKneeReferenceAltitudeMetres dryMachThrustFactor "
            + "afterburnerMachThrustFactor waveDragCoefficient afterburnerEnabled afterburnerMultiplier "
            + "throttleSpoolUpPerSecond throttleSpoolDownPerSecond sideDragPerMetre overspeedDragPerMetre "
            + "airbrakeDragPerMetre rollingResistanceMps2 groundBrakingMps2 taxiFullSteeringSpeedMps "
            + "minimumStallStateSpeedMps hpToWatts normalPowerThrottleFraction");

    // Classpath resources are immutable for the process lifetime. Failed loads are never cached.
    private static final Map<String, Binding> CACHE = new ConcurrentHashMap<>();

    public record Binding(FixedWingFlightProfile reference, FixedWingHandlingProfile handling) {}

    private BvpAircraftFlightProfiles() {}

    public static Binding requireBinding(ResourceLocation referenceId, ResourceLocation handlingId) {
        String reference = referenceId.toString();
        String handling = handlingId.toString();
        validateIdentity(reference, handling);
        return CACHE.computeIfAbsent(reference, key -> load(reference, handling));
    }

    private static Binding load(String referenceId, String handlingId) {
        String resource = "/data/" + referenceId.replace(':', '/') + ".json";
        try (InputStream stream = BvpAircraftFlightProfiles.class.getResourceAsStream(resource)) {
            if (stream == null) throw new IllegalArgumentException("Missing aircraft flight resource " + resource);
            JsonElement parsed = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) throw new IllegalArgumentException("Invalid aircraft flight root " + resource);
            return decode(parsed.getAsJsonObject(), referenceId, handlingId);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read aircraft flight resource " + resource, exception);
        }
    }

    /** Package-visible admission seam also exercises constructor validation in focused model tests. */
    static Binding decode(JsonObject root, String referenceId, String handlingId) {
        validateIdentity(referenceId, handlingId);
        exactFields(root, fields("schema referenceProfileId handlingProfileId lengthScale reference engineering"));
        require(SCHEMA.equals(string(root, "schema")), "Unsupported aircraft flight schema");
        require(referenceId.equals(string(root, "referenceProfileId"))
                && handlingId.equals(string(root, "handlingProfileId")), "Aircraft flight profile pair mismatch");
        double scale = number(root, "lengthScale");
        require(scale == 0.25, "Aircraft flight binding requires quarter-distance scaling");
        JsonObject r = object(root, "reference"), e = object(root, "engineering");
        exactFields(r, REFERENCE_FIELDS);
        Set<String> engineeringFields = new java.util.HashSet<>(e.keySet());
        engineeringFields.remove("pitotCasCalibrationFactor");
        engineeringFields.remove("takeoffPitchReferenceSpeedMps");
        engineeringFields.remove("lowSpeedThrustMultiplier");
        // owner's speed balance (tools/flight_balance): the aircraft's first soft cap in HUD km/h, and an earlier
        // transonic drag rise (propeller compressibility)
        engineeringFields.remove("firstSoftCapKmh");
        engineeringFields.remove("waveDragOnsetMach");
        require(engineeringFields.equals(ENGINEERING_FIELDS), "Aircraft flight fields do not match schema");
        validateScalarRecord(r, Set.of("dryThrustKgfPerEngine", "afterburnerThrustKgfPerEngine",
                "normalPowerHpPerEngine", "maximumPowerHpPerEngine"), Set.of());
        validateScalarRecord(e, Set.of("effectivePropulsiveEfficiency", "normalPowerThrottleFraction",
                "thrustDensityKneeReferenceAltitudeMetres"),
                Set.of("afterburnerEnabled"));

        double mass = number(r, "fullFuelMassKg"), area = number(r, "wingAreaSquareMetres");
        require(mass > 0 && area > 0, "Aircraft mass and wing area must be positive");
        require(number(r, "baseMassKg") > 0 && number(r, "mainFuelMassKg") >= 0,
                "Aircraft source mass must be positive and fuel nonnegative");
        close(mass, number(r, "baseMassKg") + number(r, "mainFuelMassKg"), "Aircraft full-fuel mass");
        close(area, mass / number(r, "fullFuelWingLoadingKgPerSquareMetre"), "Aircraft wing area");
        int engines = integer(r, "engineCount"), recoveryTicks = integer(e, "stallRecoveryTicks");
        require(engines > 0 && recoveryTicks > 0, "Aircraft engine count and recovery ticks must be positive");
        double thrust = number(e, "staticOrEquivalentThrustNewtons");
        double propellerSpeed = number(e, "propellerPowerReferenceSpeedMps");
        boolean afterburner = bool(e, "afterburnerEnabled");
        double afterburnerMultiplier = number(e, "afterburnerMultiplier");
        if (propellerSpeed > 0) {
            require(r.get("dryThrustKgfPerEngine").isJsonNull() && !afterburner,
                    "Piston aircraft cannot also declare jet thrust or afterburner");
            double efficiency = number(e, "effectivePropulsiveEfficiency");
            require(efficiency > 0 && efficiency <= 1, "Invalid effective propeller efficiency");
            double maximumPower = number(r, "maximumPowerHpPerEngine");
            require(maximumPower >= number(r, "normalPowerHpPerEngine"), "Invalid piston power ordering");
            close(thrust, engines * maximumPower * number(e, "hpToWatts") * efficiency / propellerSpeed,
                    "Installed propeller thrust");
            close(number(e, "normalPowerThrottleFraction"), number(r, "normalPowerHpPerEngine") / maximumPower,
                    "Normal-power throttle fraction");
        } else {
            require(propellerSpeed == 0 && r.get("maximumPowerHpPerEngine").isJsonNull()
                    && e.get("effectivePropulsiveEfficiency").isJsonNull(), "Mixed jet/propeller binding");
            close(thrust, engines * number(r, "dryThrustKgfPerEngine") * GRAVITY, "Installed jet thrust");
        }
        if (afterburner) {
            close(afterburnerMultiplier, number(r, "afterburnerThrustKgfPerEngine")
                    / number(r, "dryThrustKgfPerEngine"), "Afterburner thrust ratio");
        } else {
            require(afterburnerMultiplier == 1 && r.get("afterburnerThrustKgfPerEngine").isJsonNull(),
                    "Disabled afterburner must not add thrust");
        }
        double forcePerSpeedSquared = 0.5 * 1.225 * area / mass;
        double maximumLift = number(e, "maximumLiftCoefficient");
        double liftSpeed = Math.sqrt(GRAVITY / (forcePerSpeedSquared * maximumLift));
        double maximumSpeed = number(r, "maximumTrueAirspeedKmh") / 3.6;
        double controlSpeed = number(e, "controlReferenceSpeedMps");
        require(controlSpeed > liftSpeed && controlSpeed < maximumSpeed,
                "Control reference speed must lie between lift and maximum speed");
        double indicatedSpeed = number(r, "maximumIndicatedAirspeedKmh") / 3.6;
        double casCalibration = e.has("pitotCasCalibrationFactor")
                ? number(e, "pitotCasCalibrationFactor") : 1;
        require(casCalibration >= 1 && casCalibration <= 1.1,
                "Pitot CAS calibration must remain within the bounded reference reconciliation range");
        double stallAngle = number(e, "stallAngleDegrees"), recoveryAngle = number(e, "recoveryAngleDegrees");
        double response = number(e, "controlResponsePerSecond");
        double pitchRate = number(e, "pitchRateDegreesPerSecond");
        double rollRate = number(e, "rollRateDegreesPerSecond");
        double rudderRate = number(e, "rudderRateDegreesPerSecond");
        double spoolUp = number(e, "throttleSpoolUpPerSecond"), spoolDown = number(e, "throttleSpoolDownPerSecond");
        require(e.has("takeoffPitchReferenceSpeedMps") == e.has("lowSpeedThrustMultiplier"),
                "Takeoff calibration requires both pitch reference and thrust multiplier");
        FixedWingTakeoffHandling takeoff = e.has("lowSpeedThrustMultiplier")
                ? new FixedWingTakeoffHandling(number(e, "takeoffPitchReferenceSpeedMps"),
                        number(e, "lowSpeedThrustMultiplier")) : null;

        // Reference remains full-scale SI. Legacy authority fields are finite compatibility values;
        // the fixed-wing owner uses the separately constructed handling profile for all movement.
        DoubleFunction<FixedWingFlightProfile> referenceAtStructuralSpeed = structuralSpeed -> new FixedWingFlightProfile(
                referenceId, mass, area, number(e, "liftSlopePerRadian"), maximumLift,
                number(e, "zeroLiftDragCoefficient"), number(e, "inducedDragFactor"),
                number(e, "stallDragCoefficient"), stallAngle, recoveryAngle, liftSpeed,
                1.2 * liftSpeed, 2 * stallAngle, thrust, afterburner, afterburnerMultiplier,
                null, 0, structuralSpeed, maximumSpeed, 1.5 * liftSpeed,
                pitchRate * response, response, rollRate * response, response, rudderRate * response, response,
                0.5 * 1.225 * controlSpeed * controlSpeed, 0, 0.2, 0, 1.5,
                6, number(e, "groundBrakingMps2"), spoolUp, spoolDown, 20, recoveryTicks,
                1.5 * Math.max(indicatedSpeed, maximumSpeed), 2.5, 0.01, 0.04, 16, 24);
        FixedWingFlightProfile reference = referenceAtStructuralSpeed.apply(indicatedSpeed);

        FixedWingHandlingProfile controls = new FixedWingHandlingProfile(
                GRAVITY, liftSpeed, controlSpeed, 0.4 * liftSpeed, maximumSpeed,
                stallAngle, recoveryAngle, 1.2 * liftSpeed, recoveryTicks,
                thrust / mass, afterburnerMultiplier, spoolUp, spoolDown,
                forcePerSpeedSquared * number(e, "zeroLiftDragCoefficient"),
                GRAVITY * number(e, "inducedDragFactor") * maximumLift,
                number(e, "airbrakeDragPerMetre"), number(e, "rollingResistanceMps2"),
                number(e, "groundBrakingMps2"), 6, pitchRate, rollRate, rudderRate, response,
                1.2, 1.5, 20, 3, 0.01, 0.04, 16, 24, 1, 2, 1.25,
                number(e, "overspeedDragPerMetre"), number(e, "sideDragPerMetre"),
                number(r, "positiveGLimit"), 0.4, number(e, "protectionAngleDegrees"), 3,
                1, indicatedSpeed, -number(r, "negativeGLimit"),
                number(e, "liftSlopePerRadian") * Math.PI / 180 / maximumLift,
                forcePerSpeedSquared * number(e, "stallDragCoefficient"),
                number(e, "taxiFullSteeringSpeedMps"), number(e, "minimumStallStateSpeedMps"),
                propellerSpeed, number(e, "thrustDensityExponent"),
                e.get("thrustDensityKneeReferenceAltitudeMetres").isJsonNull() ? 0
                        : FixedWingAtmosphere.densityRatio(number(e, "thrustDensityKneeReferenceAltitudeMetres")),
                number(e, "dryMachThrustFactor"), number(e, "afterburnerMachThrustFactor"),
                e.has("waveDragOnsetMach") ? number(e, "waveDragOnsetMach") : 0.82, 0.25,
                forcePerSpeedSquared * number(e, "waveDragCoefficient"), takeoff,
                e.has("firstSoftCapKmh") ? number(e, "firstSoftCapKmh") / 3.6 : 0.0,
                // jets pick up speed briskly when slow; propellers already have their game gain
                propellerSpeed > 0 ? 1.0 : FixedWingHandlingProfile.JET_LOW_SPEED_BOOST,
                // wheel-brake gain: FixedWingReferenceHandling.fromReference sets it from the reference mass
                1.0);
        require(!e.has("firstSoftCapKmh") || (number(e, "firstSoftCapKmh") > 0 && number(e, "firstSoftCapKmh") <= 800),
                "First soft cap must be a positive HUD speed");
        require(!e.has("waveDragOnsetMach") || (number(e, "waveDragOnsetMach") > 0.3
                && number(e, "waveDragOnsetMach") < 1.2), "Wave drag onset must be a subsonic-to-transonic Mach");
        // Keep source IAS intact; only the handling input reconciles its published envelope with pitot CAS.
        FixedWingFlightProfile handlingReference = casCalibration == 1 ? reference
                : referenceAtStructuralSpeed.apply(indicatedSpeed * casCalibration);
        return new Binding(reference, FixedWingReferenceHandling.fromReference(handlingReference, controls, scale));
    }

    private static void validateIdentity(String reference, String handling) {
        require(reference.matches("berts_vehicle_pack:flight_reference/[a-z0-9_]+")
                && handling.equals(reference.replace(":flight_reference/", ":flight_handling/")),
                "Invalid aircraft flight identity pair");
    }

    private static Set<String> fields(String names) { return Set.of(names.split(" ")); }

    private static void exactFields(JsonObject object, Set<String> expected) {
        require(object.keySet().equals(expected), "Aircraft flight fields do not match schema");
    }

    private static void validateScalarRecord(JsonObject object, Set<String> nullable, Set<String> booleans) {
        for (String key : object.keySet()) {
            if (nullable.contains(key) && object.get(key).isJsonNull()) continue;
            if (booleans.contains(key)) bool(object, key); else number(object, key);
        }
    }

    private static JsonObject object(JsonObject object, String key) {
        require(object.has(key) && object.get(key).isJsonObject(), "Missing aircraft object " + key);
        return object.getAsJsonObject(key);
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        require(value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString(),
                "Missing aircraft string " + key);
        return value.getAsString();
    }

    private static double number(JsonObject object, String key) {
        JsonElement value = object.get(key);
        require(value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber(),
                "Missing aircraft number " + key);
        double result = value.getAsDouble();
        require(Double.isFinite(result), "Nonfinite aircraft number " + key);
        return result;
    }

    private static int integer(JsonObject object, String key) {
        double value = number(object, key);
        require(value == Math.rint(value) && value >= 0 && value <= Integer.MAX_VALUE,
                "Invalid aircraft integer " + key);
        return (int) value;
    }

    private static boolean bool(JsonObject object, String key) {
        JsonElement value = object.get(key);
        require(value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean(),
                "Missing aircraft boolean " + key);
        return value.getAsBoolean();
    }

    private static void close(double value, double expected, String label) {
        require(Double.isFinite(expected) && Math.abs(value - expected) <= 1e-6 * Math.max(1, Math.abs(expected)),
                label + " is inconsistent with its source parameters");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
