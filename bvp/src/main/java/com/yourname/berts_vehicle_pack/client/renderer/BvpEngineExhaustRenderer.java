package com.yourname.berts_vehicle_pack.client.renderer;

import com.yourname.berts_vehicle_pack.init.ModParticles;
import net.minecraft.core.particles.ParticleTypes;
import com.atsuishio.superbwarfare.resource.vehicle.DefaultVehicleResource;
import com.atsuishio.superbwarfare.resource.vehicle.VehicleResource;
import com.yourname.berts_vehicle_pack.entity.ArmoredVehicleEntity;
import com.yourname.berts_vehicle_pack.entity.BvpFarVehicleVisuals;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleOptions;
import org.joml.Matrix4d;
import org.joml.Vector4d;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ThreadLocalRandom;

public final class BvpEngineExhaustRenderer {
    private static final String ENGINE_EXHAUST_SCHEMA =
            "berts_vehicle_pack:engine_exhaust_origins/v1";
    private static final int MAX_ORIGINS = 32;
    private static final int IDLE_SPAWN_INTERVAL_TICKS = 8;
    private static final int THROTTLE_SPAWN_INTERVAL_TICKS = IDLE_SPAWN_INTERVAL_TICKS / 4;
    private static ClientLevel budgetLevel;
    private static long budgetTick = Long.MIN_VALUE;
    private static int emitted;

    private static final Map<ArmoredVehicleEntity, EmissionState> EMISSION_STATES = new WeakHashMap<>();

    private BvpEngineExhaustRenderer() {
    }

    /** Shared model-space effect seam used by every generic BVP armored renderer. */
    public static void emit(ArmoredVehicleEntity vehicle, float partialTicks) {
        if (vehicle == null || vehicle.isWreck() || (!BvpFarVehicleVisuals.engineRunning(vehicle) && !BvpFarVehicleVisuals.engineDisabled(vehicle))
                || !(vehicle.m_9236_() instanceof ClientLevel level)) {
            return;
        }

        long tick = level.m_46467_();
        if (budgetLevel != level || budgetTick != tick) { budgetLevel = level; budgetTick = tick; emitted = 0; }
        boolean disabled = BvpFarVehicleVisuals.engineDisabled(vehicle);
        int interval = disabled ? 2 : hasLongitudinalThrottle(vehicle)
                ? THROTTLE_SPAWN_INTERVAL_TICKS
                : IDLE_SPAWN_INTERVAL_TICKS;
        EmissionState state = EMISSION_STATES.computeIfAbsent(vehicle, ignored -> new EmissionState());
        if (state.initialized && tick >= state.lastTick && tick - state.lastTick < interval) {
            return;
        }
        DefaultVehicleResource.EngineExhaustResource exhaust = resolveExhaust(vehicle);
        if (exhaust == null || emitted >= 32) {
            return;
        }
        state.lastTick = tick;
        state.initialized = true;
        DefaultVehicleResource.EngineExhaustResource.Origin[] origins = exhaust.getOrigins();

        // The client camera transform retains SBW's raised rotation pivot and places model-space points too high.
        Matrix4d transform = vehicle.getVehicleTransform(partialTicks);
        Vector4d world = new Vector4d();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < origins.length && emitted < 32; i++) {
            double[] vent = origins[i].getPosition();
            // SBW vehicle matrices use +Z forward, so cross the model-space boundary by flipping Z.
            transform.transform(vent[0], vent[1], -vent[2], 1.0D, world);
            ParticleOptions smoke = disabled ? ModParticles.WRECK_SMOKE.get() : ModParticles.IMPACT_SMOKE.get();

            emitted++;
            level.addAlwaysVisibleParticle(smoke, true, world.x, world.y, world.z,
                    random.nextDouble(-0.006D, 0.006D),
                    disabled ? .055D : .022D,
                    random.nextDouble(-0.006D, 0.006D));
            if (disabled && (tick + vehicle.m_19879_() + i) % 10 == 0) {
                level.addAlwaysVisibleParticle(ParticleTypes.FLAME, true, world.x, world.y, world.z, 0, .028D, 0);
            }
        }
    }

    private static DefaultVehicleResource.EngineExhaustResource resolveExhaust(ArmoredVehicleEntity vehicle) {
        try {
            DefaultVehicleResource.EngineExhaustResource exhaust =
                    VehicleResource.getDefault(vehicle).getEngineExhaust();
            if (exhaust == null
                    || !ENGINE_EXHAUST_SCHEMA.equals(exhaust.getSchema())
                    || !"HULL".equals(exhaust.getParent())) {
                return null;
            }
            DefaultVehicleResource.EngineExhaustResource.Origin[] origins = exhaust.getOrigins();
            if (origins.length == 0 || origins.length > MAX_ORIGINS) {
                return null;
            }
            for (int index = 0; index < origins.length; index++) {
                DefaultVehicleResource.EngineExhaustResource.Origin origin = origins[index];
                if (origin == null || origin.getId().isBlank()) {
                    return null;
                }
                for (int prior = 0; prior < index; prior++) {
                    if (origin.getId().equals(origins[prior].getId())) {
                        return null;
                    }
                }
                double[] position = origin.getPosition();
                if (position.length != 3
                        || !Double.isFinite(position[0])
                        || !Double.isFinite(position[1])
                        || !Double.isFinite(position[2])) {
                    return null;
                }
            }
            return exhaust;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static boolean hasLongitudinalThrottle(ArmoredVehicleEntity vehicle) {
        return vehicle.forwardInputDown() || vehicle.backInputDown();
    }

    private static final class EmissionState {
        long lastTick;
        boolean initialized;
    }
}
