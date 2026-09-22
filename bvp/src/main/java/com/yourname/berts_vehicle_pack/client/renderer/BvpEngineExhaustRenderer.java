package com.yourname.berts_vehicle_pack.client.renderer;

import com.atsuishio.superbwarfare.client.particle.CustomCloudOption;
import com.atsuishio.superbwarfare.resource.vehicle.DefaultVehicleResource;
import com.atsuishio.superbwarfare.resource.vehicle.VehicleResource;
import com.yourname.berts_vehicle_pack.entity.ArmoredVehicleEntity;
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
    private static final ParticleOptions WHITE_SMOKE =
            new CustomCloudOption(0.88F, 0.89F, 0.86F, 58, 0.70F, 0.0F, false, false);
    private static final ParticleOptions GRAY_SMOKE =
            new CustomCloudOption(0.52F, 0.54F, 0.52F, 62, 0.76F, 0.0F, false, false);

    private static final Map<ArmoredVehicleEntity, EmissionState> EMISSION_STATES = new WeakHashMap<>();

    private BvpEngineExhaustRenderer() {
    }

    /** Shared model-space effect seam used by every generic BVP armored renderer. */
    public static void emit(ArmoredVehicleEntity vehicle, float partialTicks) {
        if (vehicle == null || vehicle.isWreck() || !vehicle.engineRunning()
                || !(vehicle.m_9236_() instanceof ClientLevel level)) {
            return;
        }

        long tick = level.m_46467_();
        int interval = hasLongitudinalThrottle(vehicle)
                ? THROTTLE_SPAWN_INTERVAL_TICKS
                : IDLE_SPAWN_INTERVAL_TICKS;
        EmissionState state = EMISSION_STATES.computeIfAbsent(vehicle, ignored -> new EmissionState());
        if (state.initialized && tick >= state.lastTick && tick - state.lastTick < interval) {
            return;
        }
        state.lastTick = tick;
        state.initialized = true;

        DefaultVehicleResource.EngineExhaustResource exhaust = resolveExhaust(vehicle);
        if (exhaust == null) {
            return;
        }
        DefaultVehicleResource.EngineExhaustResource.Origin[] origins = exhaust.getOrigins();

        // The client camera transform retains SBW's raised rotation pivot and places model-space points too high.
        Matrix4d transform = vehicle.getVehicleTransform(partialTicks);
        Vector4d world = new Vector4d();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        boolean whiteFirst = ((tick / interval + vehicle.m_19879_()) & 1L) == 0L;
        for (int i = 0; i < origins.length; i++) {
            double[] vent = origins[i].getPosition();
            // SBW vehicle matrices use +Z forward, so cross the model-space boundary by flipping Z.
            transform.transform(vent[0], vent[1], -vent[2], 1.0D, world);
            ParticleOptions smoke = ((i & 1) == 0) == whiteFirst ? WHITE_SMOKE : GRAY_SMOKE;

            // SBW CustomCloud scales supplied velocity by 0.01 internally.
            level.m_7106_(smoke, world.x, world.y, world.z,
                    random.nextDouble(-0.35D, 0.35D),
                    random.nextDouble(3.5D, 4.5D),
                    random.nextDouble(-0.35D, 0.35D));
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
