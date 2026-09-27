package com.yourname.berts_vehicle_pack.client;

import com.atsuishio.superbwarfare.client.sound.VehicleLoopSoundProviderRegistry;
import com.atsuishio.superbwarfare.client.sound.VehicleSoundInstance;
import com.atsuishio.superbwarfare.data.vehicle.subdata.EngineType;
import com.atsuishio.superbwarfare.data.vehicle.subdata.VehicleLoopSoundChannel;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.yourname.berts_vehicle_pack.BertsVehiclePack;
import com.yourname.berts_vehicle_pack.entity.ArmoredVehicleEntity;
import com.yourname.berts_vehicle_pack.entity.BmptEntity;
import com.yourname.berts_vehicle_pack.entity.T72BEntity;
import com.yourname.berts_vehicle_pack.entity.T90AEntity;
import com.yourname.berts_vehicle_pack.entity.Zsu23_4Entity;
import com.yourname.berts_vehicle_pack.init.ModSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

import java.util.LinkedHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/** One BVP-owned engine-loop provider for typed ground/helicopter custom profiles. */
final class BvpTankEngineSounds {
    private static final ResourceLocation PROFILE_ID =
            new ResourceLocation(BertsVehiclePack.MODID, "tank_engine");
    private static final ResourceLocation BMP2_PROFILE_ID =
            new ResourceLocation(BertsVehiclePack.MODID, "bmp2_engine");
    private static final ResourceLocation M1_PROFILE_ID =
            new ResourceLocation(BertsVehiclePack.MODID, "m1_abrams_elite_engine");
    private static final ResourceLocation M48_PROFILE_ID =
            new ResourceLocation(BertsVehiclePack.MODID, "tap_engine_m48a3_elite");
    private static final String TAP_ENGINE_PREFIX = "tap_engine_";
    private static final Pattern TAP_COMPONENT =
            Pattern.compile("[a-z0-9]+(?:_[a-z0-9]+)*");
    private static final long RESTART_RETRY_TICKS = 20L;
    private static final long UNSEEN_TTL_TICKS = 2L;
    private static final int MAX_ACTIVE_LAYERS = 256;
    private static final int MAX_PROFILE_CACHE = 256;
    private static final Map<UUID, ActiveLayers> ACTIVE = new LinkedHashMap<>(32, 0.75F, true);
    private static final Map<ResourceLocation, ConfiguredEngine> TAP_PROFILES =
            new LinkedHashMap<>(32, 0.75F, true);
    private static final ConfiguredEngine INVALID_PROFILE = new ConfiguredEngine(null, null);
    private static final SoundEvent M48_ENGINE = soundEvent("m48a3_elite_engine");

    private BvpTankEngineSounds() {
    }

    static void registerProvider() {
        com.atsuishio.superbwarfare.client.sound.DistantVehicleAudio.registerEngineProvider(
                BertsVehiclePack.MODID, BvpTankEngineSounds::distantEngine);
        VehicleLoopSoundProviderRegistry.register(PROFILE_ID, BvpTankEngineSounds::tickLegacyTankLoop);
        VehicleLoopSoundProviderRegistry.register(BMP2_PROFILE_ID,
                (vehicle, channel, profileId) -> tickConfiguredEngineLoop(
                        vehicle, channel, profileId, BMP2_PROFILE_ID,
                        ModSounds.BMP2_ENGINE_IDLE.get(), ModSounds.BMP2_ENGINE_DRIVE.get()));
        VehicleLoopSoundProviderRegistry.register(M1_PROFILE_ID,
                (vehicle, channel, profileId) -> tickConfiguredEngineLoop(
                        vehicle, channel, profileId, M1_PROFILE_ID,
                        ModSounds.M1_ABRAMS_ELITE_ENGINE_IDLE.get(),
                        ModSounds.M1_ABRAMS_ELITE_ENGINE_DRIVE.get()));
        VehicleLoopSoundProviderRegistry.register(M48_PROFILE_ID,
                (vehicle, channel, profileId) -> tickConfiguredEngineLoop(
                        vehicle, channel, profileId, M48_PROFILE_ID, M48_ENGINE, M48_ENGINE));
        VehicleLoopSoundProviderRegistry.registerPathPrefix(
                BertsVehiclePack.MODID, TAP_ENGINE_PREFIX, BvpTankEngineSounds::tickTapEngineLoop);
        // One cleanup hook owns every exact and prefix-routed BVP profile.
        VehicleLoopSoundProviderRegistry.registerMaintenance(PROFILE_ID, BvpTankEngineSounds::tick);
    }

    private static SoundEvent distantEngine(VehicleEntity vehicle) {
        if (!isSupportedVehicle(vehicle)) return null;
        ResourceLocation profile = vehicle.computed().getCustomSoundProfileId();
        if (M1_PROFILE_ID.equals(profile)) return ModSounds.M1_ABRAMS_ELITE_ENGINE_DRIVE.get();
        if (BMP2_PROFILE_ID.equals(profile)) return ModSounds.BMP2_ENGINE_DRIVE.get();
        if (M48_PROFILE_ID.equals(profile)) return M48_ENGINE;
        if (PROFILE_ID.equals(profile)) {
            if (vehicle instanceof T90AEntity) return ModSounds.T90A_ENGINE_RUN.get();
            if (vehicle instanceof T72BEntity || vehicle instanceof BmptEntity || vehicle instanceof Zsu23_4Entity)
                return ModSounds.T72B_ENGINE.get();
        }
        ConfiguredEngine configured = configuredProfile(profile);
        return configured == null ? null : configured.driveEvent;
    }

    private static void tickLegacyTankLoop(VehicleEntity vehicle, VehicleLoopSoundChannel channel,
                                           ResourceLocation profileId) {
        if (!PROFILE_ID.equals(profileId)
                || channel != VehicleLoopSoundChannel.ENGINE
                || !(vehicle instanceof ArmoredVehicleEntity tank)) {
            return;
        }

        Minecraft minecraft = Minecraft.m_91087_();
        if (minecraft == null || minecraft.f_91073_ == null || minecraft.f_91074_ == null) {
            if (minecraft != null) stopAll(minecraft);
            return;
        }

        long gameTime = minecraft.f_91073_.m_46467_();
        if (tank instanceof T72BEntity) {
            update(minecraft, tank, profileId, ModSounds.T72B_IDLE.get(), ModSounds.T72B_ENGINE.get(), gameTime);
        } else if (tank instanceof T90AEntity) {
            update(minecraft, tank, profileId, ModSounds.T90A_IDLE.get(), ModSounds.T90A_ENGINE_RUN.get(), gameTime);
        } else if (tank instanceof BmptEntity) {
            update(minecraft, tank, profileId, ModSounds.T72B_IDLE.get(), ModSounds.T72B_ENGINE.get(), gameTime);
        } else if (tank instanceof Zsu23_4Entity) {
            update(minecraft, tank, profileId, ModSounds.T72B_IDLE.get(), ModSounds.T72B_ENGINE.get(), gameTime);
        }
    }

    private static void tickTapEngineLoop(VehicleEntity vehicle, VehicleLoopSoundChannel channel,
                                          ResourceLocation profileId) {
        if (channel != VehicleLoopSoundChannel.ENGINE || !isSupportedVehicle(vehicle)) return;
        ConfiguredEngine configured = configuredProfile(profileId);
        if (configured == null) return;
        Minecraft minecraft = Minecraft.m_91087_();
        if (minecraft == null || minecraft.f_91073_ == null || minecraft.f_91074_ == null) {
            if (minecraft != null) stopAll(minecraft);
            return;
        }
        update(minecraft, vehicle, profileId, configured.idleEvent, configured.driveEvent,
                minecraft.f_91073_.m_46467_());
    }

    private static void tickConfiguredEngineLoop(VehicleEntity vehicle, VehicleLoopSoundChannel channel,
                                                 ResourceLocation profileId,
                                                 ResourceLocation expectedProfileId,
                                                 SoundEvent idleEvent, SoundEvent driveEvent) {
        if (!expectedProfileId.equals(profileId)
                || channel != VehicleLoopSoundChannel.ENGINE
                || !isSupportedVehicle(vehicle)
                || idleEvent == null || driveEvent == null) {
            return;
        }

        Minecraft minecraft = Minecraft.m_91087_();
        if (minecraft == null || minecraft.f_91073_ == null || minecraft.f_91074_ == null) {
            if (minecraft != null) stopAll(minecraft);
            return;
        }

        update(minecraft, vehicle, profileId, idleEvent, driveEvent, minecraft.f_91073_.m_46467_());
    }

    private static ConfiguredEngine configuredProfile(ResourceLocation profileId) {
        if (profileId == null || !BertsVehiclePack.MODID.equals(profileId.m_135827_())) return null;
        ConfiguredEngine cached = TAP_PROFILES.get(profileId);
        if (cached != null) return cached == INVALID_PROFILE ? null : cached;

        ConfiguredEngine parsed = parseProfile(profileId);
        while (TAP_PROFILES.size() >= MAX_PROFILE_CACHE) {
            Iterator<ResourceLocation> iterator = TAP_PROFILES.keySet().iterator();
            if (!iterator.hasNext()) break;
            iterator.next();
            iterator.remove();
        }
        TAP_PROFILES.put(profileId, parsed == null ? INVALID_PROFILE : parsed);
        return parsed;
    }

    /** Strictly decodes the producer's tap_engine_<drive>[__<idle>][__<start>] grammar. */
    private static ConfiguredEngine parseProfile(ResourceLocation profileId) {
        String path = profileId.m_135815_();
        if (!path.startsWith(TAP_ENGINE_PREFIX)) return null;
        String suffix = path.substring(TAP_ENGINE_PREFIX.length());
        String[] components = suffix.split("__", -1);
        if (components.length < 1 || components.length > 3) return null;
        for (String component : components) {
            if (component.isEmpty() || !TAP_COMPONENT.matcher(component).matches()) return null;
        }

        SoundEvent drive = soundEvent("tap_" + components[0]);
        SoundEvent idle = components.length > 1 ? soundEvent("tap_" + components[1]) : drive;
        // The optional start event is validated for identity/resource syntax but intentionally
        // has no replay semantics in the current looping sound API.
        if (components.length > 2 && soundEvent("tap_" + components[2]) == null) return null;
        if (drive == null || idle == null) return null;
        return new ConfiguredEngine(idle, drive);
    }

    /** SoundEvent creation is the same resource-backed seam used by serialized weapon sounds. */
    private static SoundEvent soundEvent(String path) {
        if (path == null || !path.matches("[a-z0-9]+(?:_[a-z0-9]+)*")) return null;
        try {
            return SoundEvent.m_262824_(new ResourceLocation(BertsVehiclePack.MODID, path));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static boolean isSupportedVehicle(VehicleEntity vehicle) {
        if (vehicle == null || vehicle.computed() == null) return false;
        EngineType engineType = vehicle.computed().getEngineType();
        return engineType == EngineType.WHEEL
                || engineType == EngineType.TRACK
                || engineType == EngineType.HELICOPTER;
    }

    static void tick(Minecraft minecraft) {
        if (minecraft == null || minecraft.f_91073_ == null || minecraft.f_91074_ == null) {
            stopAll(minecraft);
            return;
        }

        long gameTime = minecraft.f_91073_.m_46467_();
        Iterator<ActiveLayers> iterator = ACTIVE.values().iterator();
        while (iterator.hasNext()) {
            ActiveLayers active = iterator.next();
            if (gameTime - active.lastSeenGameTime > UNSEEN_TTL_TICKS
                    || active.vehicle.m_9236_() != minecraft.f_91073_
                    || !isLive(active.vehicle)
                    || !active.vehicle.engineRunning()) {
                stop(minecraft, active);
                iterator.remove();
            }
        }
    }

    private static void update(Minecraft minecraft, VehicleEntity vehicle, ResourceLocation profileId,
                               SoundEvent idleEvent, SoundEvent runEvent, long gameTime) {
        UUID id = vehicle.m_20148_();
        ActiveLayers active = ACTIVE.get(id);
        if (!isLive(vehicle) || !isAudible(minecraft)) {
            if (active != null) {
                stop(minecraft, active);
                ACTIVE.remove(id);
            }
            return;
        }

        boolean phaseSensitive = idleEvent != runEvent && !idleEvent.equals(runEvent);
        if (active == null || active.vehicle != vehicle || !profileId.equals(active.profileId)) {
            if (active != null) stop(minecraft, active);
            active = new ActiveLayers(vehicle, profileId,
                    start(minecraft, vehicle, idleEvent, false, phaseSensitive, gameTime),
                    phaseSensitive ? start(minecraft, vehicle, runEvent, true, true, gameTime) : null,
                    gameTime);
            active.state = hasLongitudinalThrottle(vehicle) ? LoopState.THROTTLE : LoopState.IDLE;
            ACTIVE.put(id, active);
            trim(minecraft);
            return;
        }

        active.lastSeenGameTime = gameTime;
        active.state = hasLongitudinalThrottle(vehicle) ? LoopState.THROTTLE : LoopState.IDLE;
        active.idle = ensure(minecraft, vehicle, idleEvent, false, phaseSensitive, active.idle, gameTime);
        if (phaseSensitive) {
            active.run = ensure(minecraft, vehicle, runEvent, true, true, active.run, gameTime);
        }
    }

    private static ActiveLayer ensure(Minecraft minecraft, VehicleEntity vehicle, SoundEvent event,
                                      boolean running, boolean phaseSensitive, ActiveLayer layer,
                                      long gameTime) {
        if (layer == null) return start(minecraft, vehicle, event, running, phaseSensitive, gameTime);
        if (!layer.sound.m_7801_() && minecraft.m_91106_().m_120403_(layer.sound)) return layer;
        if (!layer.sound.m_7801_() && gameTime < layer.nextRestartGameTime) return layer;
        minecraft.m_91106_().m_120399_(layer.sound);
        return start(minecraft, vehicle, event, running, phaseSensitive, gameTime);
    }

    private static ActiveLayer start(Minecraft minecraft, VehicleEntity vehicle, SoundEvent event,
                                     boolean running, boolean phaseSensitive, long gameTime) {
        VehicleEngineLoop sound = new VehicleEngineLoop(event, minecraft, vehicle, running, phaseSensitive);
        minecraft.m_91106_().m_120367_(sound);
        return new ActiveLayer(sound, gameTime + RESTART_RETRY_TICKS);
    }

    private static void stop(Minecraft minecraft, ActiveLayers active) {
        if (minecraft == null || active == null) return;
        active.state = LoopState.OFF;
        if (active.idle != null) minecraft.m_91106_().m_120399_(active.idle.sound);
        if (active.run != null) minecraft.m_91106_().m_120399_(active.run.sound);
    }

    private static boolean isLive(VehicleEntity vehicle) {
        return vehicle != null && !vehicle.m_213877_() && !vehicle.isWreck() && vehicle.getHealth() > 0.0F;
    }

    private static boolean hasLongitudinalThrottle(VehicleEntity vehicle) {
        if (vehicle.computed().getEngineType() == EngineType.HELICOPTER) {
            return vehicle.helicopterEngineLoad() > 0.1F;
        }
        return vehicle.forwardInputDown() || vehicle.backInputDown();
    }

    private static boolean isAudible(Minecraft minecraft) {
        return minecraft.f_91066_.m_92147_(SoundSource.MASTER) > 0.0F
                && minecraft.f_91066_.m_92147_(SoundSource.AMBIENT) > 0.0F;
    }

    private static void stopAll(Minecraft minecraft) {
        if (minecraft == null) {
            ACTIVE.clear();
            return;
        }
        for (ActiveLayers active : ACTIVE.values()) stop(minecraft, active);
        ACTIVE.clear();
    }

    private static void trim(Minecraft minecraft) {
        while (ACTIVE.size() > MAX_ACTIVE_LAYERS) {
            Iterator<Map.Entry<UUID, ActiveLayers>> iterator = ACTIVE.entrySet().iterator();
            if (!iterator.hasNext()) return;
            ActiveLayers active = iterator.next().getValue();
            stop(minecraft, active);
            iterator.remove();
        }
    }

    /**
     * One engine layer (idle or drive). The drive layer fades in with the throttle and the idle layer sinks under it
     * (smoothed over about half a second instead of switching), and both rise in pitch with engine load: ground
     * vehicles with their power output, helicopters with collective (rotor load). Played positionally with Doppler
     * by VehicleSoundInstance.
     */
    private static final class VehicleEngineLoop extends VehicleSoundInstance {
        private static final float MIX_RATE = 0.12F;
        private final VehicleEntity vehicle;
        private final boolean running;
        private final boolean phaseSensitive;
        private float mix = -1.0F;

        private VehicleEngineLoop(SoundEvent event, Minecraft minecraft, VehicleEntity vehicle,
                                  boolean running, boolean phaseSensitive) {
            super(event, minecraft, vehicle);
            this.vehicle = vehicle;
            this.running = running;
            this.phaseSensitive = phaseSensitive;
        }

        @Override
        protected boolean canPlay(VehicleEntity ignored) {
            return isLive(vehicle) && vehicle.engineRunning();
        }

        /** 0 idle .. 1 full load, smoothed per tick (getVolume runs once per sound tick). */
        private float load() {
            float target;
            if (vehicle.computed().getEngineType() == EngineType.HELICOPTER) {
                target = vehicle.helicopterEngineLoad();
            } else {
                float power = Math.min(1.0F, Math.abs(vehicle.getPower()));
                target = Math.max(hasLongitudinalThrottle(vehicle) ? 0.6F : 0.0F, power);
            }
            if (mix < 0.0F) mix = target;
            else mix += (target - mix) * MIX_RATE;
            return mix;
        }

        @Override
        protected float getPitch(VehicleEntity ignored) {
            float m = Math.max(0.0F, mix);
            if (vehicle.computed().getEngineType() == EngineType.HELICOPTER) {
                return 0.94F + 0.14F * m;              // rotor and turbine load
            }
            return running ? 0.88F + 0.3F * m : 0.97F + 0.08F * m;
        }

        @Override
        protected float getVolume(VehicleEntity ignored) {
            float m = load();
            float base = vehicle.getEngineSoundVolume() * 2.0F;
            if (!phaseSensitive) return base * (0.75F + 0.25F * m);
            return running ? base * m : base * (1.0F - 0.65F * m);
        }
    }

    private static final class ActiveLayer {
        private final VehicleEngineLoop sound;
        private final long nextRestartGameTime;

        private ActiveLayer(VehicleEngineLoop sound, long nextRestartGameTime) {
            this.sound = sound;
            this.nextRestartGameTime = nextRestartGameTime;
        }
    }

    private static final class ActiveLayers {
        private final VehicleEntity vehicle;
        private final ResourceLocation profileId;
        private ActiveLayer idle;
        private ActiveLayer run;
        private long lastSeenGameTime;
        private LoopState state = LoopState.IDLE;

        private ActiveLayers(VehicleEntity vehicle, ResourceLocation profileId,
                             ActiveLayer idle, ActiveLayer run, long lastSeenGameTime) {
            this.vehicle = vehicle;
            this.profileId = profileId;
            this.idle = idle;
            this.run = run;
            this.lastSeenGameTime = lastSeenGameTime;
        }
    }

    private static final class ConfiguredEngine {
        private final SoundEvent idleEvent;
        private final SoundEvent driveEvent;

        private ConfiguredEngine(SoundEvent idleEvent, SoundEvent driveEvent) {
            this.idleEvent = idleEvent;
            this.driveEvent = driveEvent;
        }
    }

    /** OFF is represented by removal from ACTIVE; live layers transition IDLE/THROTTLE without restart. */
    private enum LoopState {
        OFF,
        IDLE,
        THROTTLE
    }
}
