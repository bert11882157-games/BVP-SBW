package com.yourname.berts_vehicle_pack.client.renderer;

import com.atsuishio.superbwarfare.api.vehicle.render.FarVehicleCopies;
import com.atsuishio.superbwarfare.client.renderer.vehicle.VehicleRenderPartSnapshot;
import com.atsuishio.superbwarfare.entity.vehicle.base.GeoVehicleEntity;
import com.atsuishio.superbwarfare.resource.vehicle.DefaultVehicleResource;
import com.atsuishio.superbwarfare.resource.vehicle.DefaultVehicleResource.AircraftRigResource;
import com.atsuishio.superbwarfare.resource.vehicle.VehicleResource;
import com.example.sbwmeshloader.core.PolyMeshModel;
import com.github.mcmodderanchor.simplebedrockmodel.v1.common.model.BedrockBone;
import com.mojang.logging.LogUtils;
import com.yourname.berts_vehicle_pack.entity.BvpFarVehicleVisuals;
import com.yourname.berts_vehicle_pack.entity.helicopter.AuthoredHelicopter;
import net.minecraft.world.entity.EntityType;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/** Resource-owned visual articulation. It never writes accepted flight or engine state. */
final class AircraftRigAnimator {
    private static final int CACHE_CAPACITY = 256;
    private static final int MAX_SURFACES = 32;
    private static final int MAX_ROTORS = 16;
    private static final int MAX_SWEEPS = 16;
    private static final int MAX_GEAR = 32;
    private static final int MAX_FLAPS = 16;
    private static final int MAX_STEERING = 4;
    /** Nose-wheel steering authority: full up to taxi speed, easing to a fifth by take-off speed (blocks/tick). */
    private static final double STEER_FULL_SPEED = 0.5, STEER_LOW_SPEED = 2.0, STEER_HIGH_SPEED_GAIN = 0.2;
    private static final Map<PolyMeshModel, Entry> BINDINGS = new LinkedHashMap<>();
    private static final Map<UUID, Phase> PHASES = new LinkedHashMap<>(16, 0.75F, true);
    private static Object world;
    private static int warningsRemaining = 8;

    private AircraftRigAnimator() { }

    /** True also for a rejected explicit rig: legacy animators must not reinterpret its bones. */
    static boolean apply(GeoVehicleEntity entity, PolyMeshModel model, float partialTick,
                         VehicleRenderPartSnapshot renderParts) {
        if (world != entity.m_9236_()) {
            clear();
            world = entity.m_9236_();
        }
        Entry entry = BINDINGS.get(model);
        if (entry == null || entry.entityType != entity.m_6095_()) {
            if (entry != null) entry.restore();
            entry = new Entry(entity.m_6095_());
            BINDINGS.put(model, entry);
            trim(BINDINGS);
        }
        DefaultVehicleResource resource = VehicleResource.getDefault(entry.resourceId);
        if (!entry.resolved || entry.resource != resource) {
            entry.restore();
            entry.resolved = true;
            entry.resource = resource;
            entry.binding = null;
            entry.station = null;
            AircraftRigResource data = resource == null ? null : resource.getAircraftRig();
            entry.explicit = data != null;
            if (data != null) {
                try {
                    entry.binding = bind(data, model::getBone);
                    var station = resource.getDefensiveStationPresentation();
                    if (station != null) entry.station = bindStation(station, entry.binding, model::getBone);
                } catch (IllegalArgumentException invalid) {
                    if (warningsRemaining > 0) {
                        warningsRemaining--;
                        LogUtils.getLogger().warn("Aircraft rig skipped for {}: {}",
                                entry.resourceId, invalid.getMessage());
                    }
                }
            }
        }
        if (!entry.explicit) return false;
        Binding binding = entry.binding;
        if (binding == null) return true;

        double elevator = 0, aileron = 0, rudder = 0, spool = 0, speed = 0, airbrake = 0, rolling = 0;
        boolean grounded = false;
        boolean needsMotion = binding.sweeps.length != 0 || binding.rotors.length != 0 || binding.steering.length != 0;
        boolean usable = Float.isFinite(partialTick) && !entity.isWreck();
        if (usable && entity instanceof AuthoredHelicopter) {
            spool = BvpFarVehicleVisuals.helicopterRotorSpool(entity, partialTick);
            usable = Double.isFinite(spool);
        } else if (usable && FarVehicleCopies.isCopy(entity)) {
            var far = FarVehicleCopies.frame(entity);
            var controls = far == null ? null : far.getFixedWingControls();
            usable = far != null && !far.getSnapshot().getWreck() && controls != null;
            if (usable) {
                elevator = controls.getElevator();
                aileron = controls.getAileron();
                rudder = controls.getRudder();
                airbrake = controls.getAirbrake();
                spool = controls.getThrottle();
                if (needsMotion) {
                    var snapshot = far.getSnapshot();
                    speed = speed(snapshot.getMotionX(), snapshot.getMotionY(), snapshot.getMotionZ());
                    grounded = grounded(entity);
                    rolling = rolling(entity, grounded, snapshot.getMotionX(), snapshot.getMotionZ());
                }
            }
        } else if (usable) {
            var flight = entity.getVehicleFlightPresentationSnapshot(partialTick);
            usable = flight.getServerTick() > 0 && Double.isFinite(flight.getThrottle());
            if (usable) {
                spool = clamp(flight.getThrottle(), 0, 1);
                if (needsMotion) {
                    var motion = flight.getMotion();
                    speed = speed(motion.f_82479_, motion.f_82480_, motion.f_82481_);
                    grounded = grounded(entity);
                    rolling = rolling(entity, grounded, motion.f_82479_, motion.f_82481_);
                }
                var controls = flight.getControlSurfaces();
                if (controls != null) {
                    elevator = controls.getElevator();
                    aileron = controls.getAileron();
                    rudder = controls.getRudder();
                airbrake = controls.getAirbrake();
                }
            }
        }
        double[] forced = com.atsuishio.superbwarfare.diagnostics.DiagnosticControlOverride.current();
        if (forced != null && !entity.isWreck()) {
            elevator = forced[0];
            aileron = forced[1];
            rudder = forced[2];
        }
        Phase phase = PHASES.get(entity.getUUID());
        if (phase == null || phase.binding != binding) {
            phase = new Phase(binding);
            PHASES.put(entity.getUUID(), phase);
            trim(PHASES);
        }
        if (usable) {
            phase.advance(entity.m_9236_().m_46467_() + (double) partialTick, spool, speed, rolling);
        } else {
            phase.reset();
        }
        binding.apply(elevator, aileron, rudder, speed, phase);
        // Far copies receive the same normalized snapshot value in SynchedGearRot.
        float gearFraction = entity.getSynchedGearRot();
        binding.applyGear(gearFraction);
        if (binding.steering.length != 0) binding.applySteering(usable && grounded, gearFraction, rudder, speed);
        if (binding.flaps.length != 0) {
            binding.applyFlaps(Float.isFinite(partialTick) && !entity.isWreck()
                    && entity.hasFixedWingLandingGear(), gearFraction);
            binding.applyAirbrakes(airbrake);
        }
        if (entry.station != null) {
            int seat = entity.getPassengerWeaponStationControllerIndex();
            int weapon = seat >= 0 ? entity.getSelectedWeapon(seat) : -1;
            if (Float.isFinite(partialTick) && !entity.isWreck()
                    && entity.isPassengerStationLocalAim(seat, weapon)) {
                entry.station.apply(renderParts.getStationPresentationValid(),
                        renderParts.getStationYawFromRenderedHullDegrees(), -renderParts.getStationPitchDegrees());
            } else entry.station.restore();
        }
        return true;
    }

    static Station bindStation(DefaultVehicleResource.DefensiveStationResource data, Binding binding,
                               Function<String, BedrockBone> bones) {
        require(data != null && Integer.valueOf(1).equals(data.schema)
                && "GENERATED_MODEL_PIXELS".equals(data.frame)
                && data.yaw != null && data.pitch != null
                && "hull".equals(data.yaw.parent) && data.yaw.bone != null
                && data.yaw.bone.equals(data.pitch.parent), "station graph/schema");
        Set<String> names = new HashSet<>();
        Part yaw = part(data.yaw, -4, 1, 0, 0, 0, true, names, bones);
        Part pitch = part(data.pitch, -4, 1, 0, 0, 0, true, names, bones);
        for (Part control : binding.surfaces) require(control.bone != yaw.bone && control.bone != pitch.bone, "station/control overlap");
        for (Part rotor : binding.rotors) require(rotor.bone != yaw.bone && rotor.bone != pitch.bone, "station/rotor overlap");
        for (Sweep sweep : binding.sweeps) require(sweep.part.bone != yaw.bone && sweep.part.bone != pitch.bone, "station/sweep overlap");
        for (Gear gear : binding.gear) require(gear.bone != yaw.bone && gear.bone != pitch.bone, "station/gear overlap");
        for (Part flap : binding.flaps) require(flap.bone != yaw.bone && flap.bone != pitch.bone, "station/flap overlap");
        for (Part steer : binding.steering) require(steer.bone != yaw.bone && steer.bone != pitch.bone, "station/steering overlap");
        return new Station(yaw, pitch);
    }

    static final class Station {
        final Part yaw, pitch;

        Station(Part yaw, Part pitch) { this.yaw = yaw; this.pitch = pitch; }

        void apply(boolean accepted, double yawDegrees, double pitchDegrees) {
            if (!accepted || !Double.isFinite(yawDegrees) || !Double.isFinite(pitchDegrees)) {
                restore();
                return;
            }
            yaw.rotate(yawDegrees);
            pitch.rotate(pitchDegrees);
        }

        void restore() { yaw.restore(); pitch.restore(); }
    }

    static boolean owns(PolyMeshModel model) {
        Entry entry = BINDINGS.get(model);
        return entry != null && entry.explicit;
    }

    /** Paired with the renderer's finally block; cached geometry never retains a vehicle pose. */
    static void restore(PolyMeshModel model) {
        Entry entry = BINDINGS.get(model);
        if (entry != null) entry.restore();
    }

    static void clear() {
        for (Entry entry : BINDINGS.values()) entry.restore();
        BINDINGS.clear();
        PHASES.clear();
        world = null;
        warningsRemaining = 8;
    }

    private static <K, V> void trim(Map<K, V> cache) {
        if (cache.size() > CACHE_CAPACITY) cache.remove(cache.keySet().iterator().next());
    }

    /** Copies and validates every channel and bone before any rotation is mutated. */
    static Binding bind(AircraftRigResource data, Function<String, BedrockBone> bones) {
        require(data != null && (Integer.valueOf(1).equals(data.schema)
                || Integer.valueOf(2).equals(data.schema))
                && "GENERATED_MODEL_PIXELS".equals(data.frame), "schema/frame");
        boolean extended = Integer.valueOf(2).equals(data.schema);
        require(data.surfaces != null && data.rotors != null
                && data.surfaces.length <= MAX_SURFACES && data.rotors.length <= MAX_ROTORS,
                "surface/rotor arrays");
        require(extended ? data.sweeps != null && data.sweeps.length <= MAX_SWEEPS
                : data.sweeps == null, "sweep array/version");
        require(data.gear == null || extended && data.gear.length <= MAX_GEAR, "gear array/version");
        require(data.gearDoors == null || extended && data.gearDoors.length <= 32, "gear door array/version");
        require(data.flaps == null || extended && data.flaps.length <= MAX_FLAPS, "flap array/version");
        require(data.noseSteering == null || extended && data.gear != null
                && data.noseSteering.length <= MAX_STEERING, "nose steering array/version");
        // Each flap validates the availability of its own input below.
        Set<String> names = new HashSet<>();
        Part[] surfaces = new Part[data.surfaces.length];
        Part[] rotors = new Part[data.rotors.length];
        Sweep[] sweeps = new Sweep[extended ? data.sweeps.length : 0];
        Gear[] gear = new Gear[data.gear == null ? 0 : data.gear.length];
        Door[] doors = new Door[data.gearDoors == null ? 0 : data.gearDoors.length];
        Part[] flaps = new Part[data.flaps == null ? 0 : data.flaps.length];
        for (int index = 0; index < surfaces.length; index++) {
            var source = data.surfaces[index];
            require(source != null, "null surface");
            int channel = extended ? -2 : switch (source.channel == null ? "" : source.channel) {
                case "elevatorUp" -> 0;
                case "rightRoll" -> 1;
                case "rudderRight" -> 2;
                default -> throw new IllegalArgumentException("surface channel");
            };
            double rate = positive(source.maxDeflectionDegrees, extended ? 90 : 180, "surface limit");
            double elevatorWeight = 0, rollWeight = 0, rudderWeight = 0;
            if (extended) {
                require(source.channel == null && source.angleSign == null
                        && source.controlWeights != null, "mixed surface version");
                elevatorWeight = weight(source.controlWeights.elevatorUp);
                rollWeight = weight(source.controlWeights.rightRoll);
                rudderWeight = weight(source.controlWeights.rudderRight);
                require(Math.abs(elevatorWeight) + Math.abs(rollWeight) + Math.abs(rudderWeight) > 0,
                        "zero control weights");
            } else {
                require(source.controlWeights == null, "mixed controls require schema 2");
                rate *= sign(source.angleSign);
            }
            surfaces[index] = part(source, channel, rate, elevatorWeight, rollWeight, rudderWeight,
                    extended, names, bones);
        }
        for (int index = 0; index < rotors.length; index++) {
            var source = data.rotors[index];
            require(source != null && ("engineSpool".equals(source.speedChannel)
                    || "presentedSpeed".equals(source.speedChannel)
                    || "groundRoll".equals(source.speedChannel)), "rotor channel");
            double rate = positive(source.degreesPerTickAtFullSpeed, 3600, "rotor rate")
                    * sign(source.direction);
            rotors[index] = part(source, "presentedSpeed".equals(source.speedChannel) ? -4
                    : "groundRoll".equals(source.speedChannel) ? -5 : -1,
                    rate, 0, 0, 0, extended, names, bones);
        }
        for (int index = 0; index < sweeps.length; index++) {
            var source = data.sweeps[index];
            require(source != null, "null sweep");
            double rate = positive(source.maxDeflectionDegrees, 90, "sweep limit")
                    * sign(source.angleSign);
            var part = part(source, -3, rate, 0, 0, 0, true, names, bones);
            sweeps[index] = new Sweep(part, source.schedule);
        }
        for (int index = 0; index < gear.length; index++) {
            var source = data.gear[index];
            require(source != null && source.bone != null
                    && source.bone.matches("[a-z][a-z0-9_]{0,63}")
                    && !"hull".equals(source.bone) && names.add(source.bone)
                    && "hull".equals(source.parent), "gear identity/parent");
            BedrockBone bone = bones.apply(source.bone);
            BedrockBone parent = bones.apply(source.parent);
            require(bone != null && parent != null && bone.parent == parent, "missing gear bone/parent");
            require(source.visibleWhen == null || "DEPLOYED".equals(source.visibleWhen)
                    || "RETRACTED".equals(source.visibleWhen), "gear visibility state");
            gear[index] = new Gear(bone, "RETRACTED".equals(source.visibleWhen));
        }
        for (int index = 0; index < flaps.length; index++) {
            var source = data.flaps[index];
            boolean airbrakeInput = source != null && "PRESENTED_AIRBRAKE".equals(source.input);
            require(source != null && (airbrakeInput || "PRESENTED_LANDING_GEAR_EXTENSION".equals(source.input))
                    && "hull".equals(source.parent), "flap input/parent");
            require(airbrakeInput || gear.length > 0, "landing flaps require explicit gear partitions");
            double rate = positive(source.maxDeflectionDegrees, 90, "landing flap limit")
                    * sign(source.angleSign);
            flaps[index] = part(source, airbrakeInput ? -6 : -5, rate, 0, 0, 0, true, names, bones);
        }
        for (int index = 0; index < doors.length; index++) {
            var source = data.gearDoors[index];
            require(source != null && "hull".equals(source.parent) && source.closedAngleDegrees != null
                    && Double.isFinite(source.closedAngleDegrees) && Math.abs(source.closedAngleDegrees) <= 180,
                    "gear door parent/angle");
            doors[index] = new Door(part(source, -7, source.closedAngleDegrees, 0, 0, 0, true, names, bones));
        }
        Part[] steering = new Part[data.noseSteering == null ? 0 : data.noseSteering.length];
        for (int index = 0; index < steering.length; index++) {
            var source = data.noseSteering[index];
            require(source != null && source.parent != null, "null nose steering");
            // only a deployed gear leg can carry a steering assembly: it disappears with the leg on retraction
            boolean deployedLeg = false;
            for (var leg : data.gear) {
                if (leg != null && source.parent.equals(leg.bone) && !"RETRACTED".equals(leg.visibleWhen)) {
                    deployedLeg = true;
                }
            }
            require(deployedLeg, "nose steering parent must be a deployed gear leg");
            double rate = positive(source.maxDeflectionDegrees, 90, "nose steering limit");
            steering[index] = part(source, -8, rate, 0, 0, 0, true, names, bones);
        }
        if (extended) validateParents(data);
        return new Binding(surfaces, rotors, sweeps, gear, flaps, doors, steering);
    }

    private static Part part(AircraftRigResource.Part source, int channel, double rate,
                             double elevatorWeight, double rollWeight, double rudderWeight,
                             boolean extended,
                             Set<String> names, Function<String, BedrockBone> bones) {
        require(source.bone != null && source.bone.matches("[A-Za-z0-9_.-]{1,96}")
                && !"hull".equals(source.bone) && names.add(source.bone)
                && source.parent != null && !source.bone.equals(source.parent)
                && (extended || "hull".equals(source.parent)), "bone identity/parent");
        vector(source.pivot, "pivot");
        vector(source.axis, "axis");
        BedrockBone bone = bones.apply(source.bone);
        BedrockBone parent = bones.apply(source.parent);
        require(bone != null && parent != null && bone.parent == parent, "missing bone/parent");
        double x = 0, y = 0, z = 0;
        int depth = 0;
        for (BedrockBone current = bone; current != null; current = current.parent) {
            require(++depth <= 64, "cyclic/deep hierarchy");
            x += current.x;
            y += current.y;
            z += current.z;
        }
        // BedrockModel and PolyMesh reverse point X. Pivots are already baked into the model;
        // moving them again would double-apply authoring and move neutral geometry.
        require(Math.abs(x + source.pivot[0]) <= 0.001
                && Math.abs(y - source.pivot[1]) <= 0.001
                && Math.abs(z - source.pivot[2]) <= 0.001, "model/resource pivot mismatch");
        double length = Math.sqrt(source.axis[0] * source.axis[0]
                + source.axis[1] * source.axis[1] + source.axis[2] * source.axis[2]);
        require(length > 1.0E-8 && Double.isFinite(length), "zero/nonfinite axis");
        // Rotations are axial vectors: X reflection maps [a,b,c] to [a,-b,-c].
        // The compiler has already applied its separate Z reflection exactly once.
        return new Part(bone, channel, rate, elevatorWeight, rollWeight, rudderWeight,
                (float) (source.axis[0] / length),
                (float) (-source.axis[1] / length), (float) (-source.axis[2] / length));
    }

    private static void validateParents(AircraftRigResource data) {
        Map<String, String> parents = new HashMap<>();
        for (var source : data.surfaces) parents.put(source.bone, source.parent);
        for (var source : data.sweeps) parents.put(source.bone, source.parent);
        if (data.gear != null) for (var source : data.gear) if (source != null) parents.put(source.bone, "hull");
        // steering assemblies hang under gear legs; wheel rotors may in turn hang under them
        if (data.noseSteering != null) for (var source : data.noseSteering) parents.put(source.bone, source.parent);
        for (var source : data.rotors) validateParent(source.bone, source.parent, parents);
        for (var entry : parents.entrySet()) validateParent(entry.getKey(), entry.getValue(), parents);
    }

    private static void validateParent(String bone, String parent, Map<String, String> parents) {
        Set<String> ancestors = new HashSet<>();
        ancestors.add(bone);
        while (!"hull".equals(parent)) {
            require(parents.containsKey(parent) && ancestors.size() < 8 && ancestors.add(parent),
                    "unknown/rotor/cyclic/deep parent");
            parent = parents.get(parent);
        }
    }

    private static double weight(Double value) {
        require(value != null && Double.isFinite(value) && Math.abs(value) <= 1, "control weight");
        return value;
    }

    /**
     * Signed ground roll along the nose, blocks/tick, for landing gear wheels: zero off the ground. The client
     * onGround flag is not reliable for synchronized vehicles, so a solid block just below the airframe also counts.
     */
    private static double rolling(GeoVehicleEntity entity, boolean grounded, double x, double z) {
        if (!Double.isFinite(x) || !Double.isFinite(z) || !grounded) return 0;
        double yaw = Math.toRadians(entity.m_146908_());
        return -Math.sin(yaw) * x + Math.cos(yaw) * z;
    }

    /** The client onGround flag is not reliable for synchronized vehicles: a solid block just below also counts. */
    private static boolean grounded(GeoVehicleEntity entity) {
        if (entity.m_20096_()) return true;
        var level = entity.m_9236_();
        var below = net.minecraft.core.BlockPos.m_274561_(entity.m_20185_(), entity.m_20186_() - 0.35, entity.m_20189_());
        return !level.m_8055_(below).m_60795_();
    }

    /** Steering authority at a presented speed (blocks/tick): full while taxiing, reduced on the take-off run. */
    static double steeringGain(double speed) {
        if (!Double.isFinite(speed) || speed <= STEER_FULL_SPEED) return 1;
        if (speed >= STEER_LOW_SPEED) return STEER_HIGH_SPEED_GAIN;
        double alpha = (speed - STEER_FULL_SPEED) / (STEER_LOW_SPEED - STEER_FULL_SPEED);
        return 1 + alpha * (STEER_HIGH_SPEED_GAIN - 1);
    }

    private static double speed(double x, double y, double z) {
        return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z)
                ? Math.hypot(Math.hypot(x, y), z) : 0;
    }

    private static void vector(double[] vector, String name) {
        require(vector != null && vector.length == 3, name);
        for (double value : vector) require(Double.isFinite(value) && Math.abs(value) <= 16384, name);
    }

    private static double positive(Double value, double maximum, String name) {
        require(value != null && Double.isFinite(value) && value > 0 && value <= maximum, name);
        return value;
    }

    private static int sign(Integer value) {
        require(value != null && (value == -1 || value == 1), "rotation sign");
        return value;
    }

    private static void require(boolean condition, String reason) {
        if (!condition) throw new IllegalArgumentException(reason);
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Double.isFinite(value) ? Math.max(minimum, Math.min(maximum, value)) : 0;
    }

    private static final class Entry {
        final EntityType<?> entityType;
        final String resourceId;
        DefaultVehicleResource resource;
        Binding binding;
        Station station;
        boolean resolved;
        boolean explicit;

        Entry(EntityType<?> entityType) {
            this.entityType = entityType;
            this.resourceId = VehicleResource.getRegistryId(entityType);
        }

        void restore() {
            if (binding != null) binding.restore();
            if (station != null) station.restore();
        }
    }

    static final class Binding {
        final Part[] surfaces;
        final Part[] rotors;
        final Sweep[] sweeps;
        final Gear[] gear;
        final Part[] flaps;
        final Door[] doors;
        final Part[] steering;

        Binding(Part[] surfaces, Part[] rotors, Sweep[] sweeps, Gear[] gear, Part[] flaps, Door[] doors) {
            this(surfaces, rotors, sweeps, gear, flaps, doors, new Part[0]);
        }

        Binding(Part[] surfaces, Part[] rotors, Sweep[] sweeps, Gear[] gear, Part[] flaps, Door[] doors,
                Part[] steering) {
            this.surfaces = surfaces;
            this.rotors = rotors;
            this.sweeps = sweeps;
            this.gear = gear;
            this.flaps = flaps;
            this.doors = doors;
            this.steering = steering;
        }

        void apply(double elevator, double aileron, double rudder, Phase phase) {
            apply(elevator, aileron, rudder, 0, phase);
        }

        void apply(double elevator, double aileron, double rudder, double speed, Phase phase) {
            for (Part part : surfaces) {
                double control = switch (part.channel) {
                    case 0 -> elevator;
                    case 1 -> aileron;
                    case -2 -> part.elevatorWeight * clamp(elevator, -1, 1)
                            + part.rollWeight * clamp(aileron, -1, 1)
                            + part.rudderWeight * clamp(rudder, -1, 1);
                    default -> rudder;
                };
                part.rotate(clamp(control, -1, 1) * part.rate);
            }
            for (int index = 0; index < rotors.length; index++) {
                rotors[index].rotate(phase.degrees[index]);
            }
            for (Sweep sweep : sweeps) sweep.part.rotate(sweep.fraction(speed) * sweep.part.rate);
        }

        void applyGear(double fraction) {
            boolean retracted = Double.isFinite(fraction) && fraction == 1.0;
            for (Gear part : gear) part.apply(retracted);
            double closure = Double.isFinite(fraction) ? clamp(fraction, 0, 1) : 0;
            for (Door door : doors) door.apply(closure);
        }

        /**
         * Nose-wheel steering follows the rudder (positive = nose right) only while the aircraft rests on fully
         * extended gear; airborne, retracting or wrecked it centres.
         */
        void applySteering(boolean grounded, double gearFraction, double rudder, double speed) {
            boolean down = grounded && Double.isFinite(gearFraction) && gearFraction <= 0;
            double command = down ? clamp(rudder, -1, 1) * steeringGain(speed) : 0;
            for (Part steer : steering) steer.rotate(command * steer.rate);
        }

        void applyFlaps(boolean gearAvailable, double gearFraction) {
            double fraction = gearAvailable && Double.isFinite(gearFraction)
                    && gearFraction >= 0 && gearFraction <= 1 ? 1 - gearFraction : 0;
            for (Part flap : flaps) if (flap.channel == -5) flap.rotate(fraction * flap.rate);
        }

        void applyAirbrakes(double fraction) {
            double extension = Double.isFinite(fraction) ? clamp(fraction, 0, 1) : 0;
            for (Part flap : flaps) if (flap.channel == -6) flap.rotate(extension * flap.rate);
        }

        void restore() {
            for (Part part : surfaces) part.restore();
            for (Part part : rotors) part.restore();
            for (Sweep sweep : sweeps) sweep.part.restore();
            for (Gear part : gear) part.restore();
            for (Part flap : flaps) flap.restore();
            for (Door door : doors) door.restore();
            for (Part steer : steering) steer.restore();
        }
    }

    static final class Door {
        final Part part;
        private boolean captured, visibleBefore;
        Door(Part part) { this.part = part; }
        void apply(double closure) {
            if (!captured) { visibleBefore = part.bone.visible; captured = true; }
            part.bone.visible = true;
            part.rotate(closure * part.rate);
        }
        void restore() {
            part.restore();
            if (captured) part.bone.visible = visibleBefore;
            captured = false;
        }
    }

    /** Restores the exact incoming visibility; shared models cannot retain another entity's gear. */
    static final class Gear {
        final BedrockBone bone;
        final boolean showRetracted;
        private boolean captured;
        private boolean visibleBefore;

        Gear(BedrockBone bone) { this(bone, false); }
        Gear(BedrockBone bone, boolean showRetracted) { this.bone = bone; this.showRetracted = showRetracted; }

        void apply(boolean retracted) {
            if (!captured) {
                visibleBefore = bone.visible;
                captured = true;
            }
            bone.visible = visibleBefore && (retracted == showRetracted);
        }

        void restore() {
            if (captured) bone.visible = visibleBefore;
            captured = false;
        }
    }

    static final class Part {
        final BedrockBone bone;
        final int channel;
        final double rate;
        final double elevatorWeight, rollWeight, rudderWeight;
        final float axisX, axisY, axisZ;
        final Quaternionf neutral;
        final Vector3f neutralEuler;

        Part(BedrockBone bone, int channel, double rate,
             double elevatorWeight, double rollWeight, double rudderWeight, float x, float y, float z) {
            this.bone = bone;
            this.channel = channel;
            this.rate = rate;
            this.elevatorWeight = elevatorWeight;
            this.rollWeight = rollWeight;
            this.rudderWeight = rudderWeight;
            this.axisX = x;
            this.axisY = y;
            this.axisZ = z;
            this.neutral = new Quaternionf(bone.rotation);
            this.neutralEuler = new Vector3f(bone.rotationInEuler);
        }

        void rotate(double degrees) {
            bone.rotation.rotationAxis((float) Math.toRadians(degrees), axisX, axisY, axisZ)
                    .mul(neutral);
            var q = bone.rotation;
            double sinPitch = 2.0 * (q.w * (double) q.y - q.z * (double) q.x);
            bone.rotationInEuler.set(
                    (float) Math.atan2(2.0 * (q.w * (double) q.x + q.y * (double) q.z),
                            1.0 - 2.0 * (q.x * (double) q.x + q.y * (double) q.y)),
                    (float) Math.asin(Math.max(-1.0, Math.min(1.0, sinPitch))),
                    (float) Math.atan2(2.0 * (q.w * (double) q.z + q.x * (double) q.y),
                            1.0 - 2.0 * (q.y * (double) q.y + q.z * (double) q.z)));
        }

        void restore() {
            bone.rotation.set(neutral);
            bone.rotationInEuler.set(neutralEuler);
        }
    }

    /** A bounded lookup of accepted full-XYZ motion; no local physics or render-clock integration. */
    static final class Sweep {
        final Part part;
        final double[] speeds;
        final double[] fractions;

        Sweep(Part part, AircraftRigResource.SweepSchedule source) {
            require(source != null && "PRESENTED_SPEED_BLOCKS_PER_TICK".equals(source.input)
                    && "LINEAR_CLAMPED".equals(source.interpolation) && source.points != null
                    && source.points.length >= 2 && source.points.length <= 16, "sweep schedule");
            this.part = part;
            speeds = new double[source.points.length];
            fractions = new double[source.points.length];
            for (int index = 0; index < speeds.length; index++) {
                double[] point = source.points[index];
                require(point != null && point.length == 2 && Double.isFinite(point[0])
                        && point[0] >= 0 && point[0] <= 100000 && Double.isFinite(point[1])
                        && point[1] >= 0 && point[1] <= 1, "sweep point");
                speeds[index] = point[0];
                fractions[index] = point[1];
                require(index == 0 ? point[0] == 0 && point[1] == 0
                        : point[0] > speeds[index - 1] && point[1] >= fractions[index - 1],
                        "sweep ordering/neutral");
            }
            require(fractions[fractions.length - 1] == 1, "sweep endpoint");
        }

        double fraction(double speed) {
            if (!Double.isFinite(speed) || speed <= 0) return 0;
            for (int index = 1; index < speeds.length; index++) {
                if (speed < speeds[index]) {
                    double alpha = (speed - speeds[index - 1]) / (speeds[index] - speeds[index - 1]);
                    return fractions[index - 1] + alpha * (fractions[index] - fractions[index - 1]);
                }
            }
            return 1;
        }
    }

    /** Bounded UUID-owned phase survives live/far handoffs; no catch-up loop or render allocation. */
    static final class Phase {
        final Binding binding;
        final double[] degrees;
        double previousTick = Double.NaN;
        double previousSpool;
        double previousSpeed;
        double previousRolling;

        Phase(Binding binding) {
            this.binding = binding;
            this.degrees = new double[binding.rotors.length];
        }

        void advance(double tick, double spool) {
            advance(tick, spool, 0);
        }

        void advance(double tick, double spool, double speed) {
            advance(tick, spool, speed, 0);
        }

        void advance(double tick, double spool, double speed, double rolling) {
            if (!Double.isFinite(tick)) { reset(); return; }
            // Wheels show at most one block/tick of roll: faster spins only strobe at frame rate.
            rolling = Double.isFinite(rolling) ? clamp(rolling, -1, 1) : 0;
            spool = clamp(spool, 0, 1);
            // Wind-driven auxiliaries reach their presentation rate at one block/tick.
            // Use the same snapshot motion for live entities and distant copies.
            speed = clamp(speed, 0, 1);
            double elapsed = tick - previousTick;
            if (!Double.isFinite(elapsed) || elapsed < 0 || elapsed > 20) {
                reset();
                previousTick = tick;
                previousSpool = spool;
                previousSpeed = speed;
                previousRolling = rolling;
                return;
            }
            double integratedSpool = elapsed * (previousSpool + spool) * 0.5;
            double integratedSpeed = elapsed * (previousSpeed + speed) * 0.5;
            double integratedRolling = elapsed * (previousRolling + rolling) * 0.5;
            for (int index = 0; index < degrees.length; index++) {
                var rotor = binding.rotors[index];
                degrees[index] = (degrees[index] + rotor.rate * (rotor.channel == -4 ? integratedSpeed
                        : rotor.channel == -5 ? integratedRolling : integratedSpool)) % 360;
            }
            previousTick = tick;
            previousSpool = spool;
            previousSpeed = speed;
            previousRolling = rolling;
        }

        void reset() {
            java.util.Arrays.fill(degrees, 0);
            previousTick = Double.NaN;
            previousSpool = 0;
            previousSpeed = 0;
            previousRolling = 0;
        }
    }
}
