package com.yourname.berts_vehicle_pack.client;

import com.atsuishio.superbwarfare.client.sound.VehicleLoopSoundProviderRegistry;
import com.atsuishio.superbwarfare.client.sound.VehicleSoundInstance;
import com.atsuishio.superbwarfare.data.vehicle.subdata.VehicleLoopSoundChannel;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.yourname.berts_vehicle_pack.BertsVehiclePack;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Aircraft-only owner of TaP idle/start samples and continuously spooled running loops. */
final class BvpAircraftEngineSounds {
    private static final String PREFIX = "aircraft_engine/";
    private static final ResourceLocation OWNER = new ResourceLocation(BertsVehiclePack.MODID, "aircraft_engine");
    private static final int MAX_ENGINES = 32;
    private static final double RANGE_SQUARED = 64 * 64;
    private static final Map<UUID, Engine> ACTIVE = new LinkedHashMap<>();

    private BvpAircraftEngineSounds() { }

    static void registerProvider() {
        VehicleLoopSoundProviderRegistry.registerPathPrefix(BertsVehiclePack.MODID, PREFIX,
                BvpAircraftEngineSounds::update);
        VehicleLoopSoundProviderRegistry.registerMaintenance(OWNER, BvpAircraftEngineSounds::maintain);
    }

    private static boolean audible(Minecraft client, VehicleEntity vehicle) {
        return client.level != null && client.player != null && vehicle.level() == client.level
                && !vehicle.isRemoved() && !vehicle.isWreck() && vehicle.getHealth() > 0F
                && vehicle.engineRunning() && vehicle.isFixedWingFlightVehicle()
                && client.options.getSoundSourceVolume(SoundSource.MASTER) > 0F
                && client.options.getSoundSourceVolume(SoundSource.AMBIENT) > 0F
                && client.gameRenderer.getMainCamera().getPosition().distanceToSqr(vehicle.position()) <= RANGE_SQUARED;
    }

    private static void update(VehicleEntity vehicle, VehicleLoopSoundChannel channel, ResourceLocation profile) {
        if (channel != VehicleLoopSoundChannel.ENGINE || !vehicle.isFixedWingFlightVehicle()) return;
        Minecraft client = Minecraft.getInstance();
        if (!audible(client, vehicle)) return;
        Engine engine = ACTIVE.get(vehicle.getUUID());
        if (engine != null && (engine.vehicle != vehicle || !engine.profile.equals(profile))) {
            stop(client, engine);
            ACTIVE.remove(vehicle.getUUID());
            engine = null;
        }
        long time = client.level.getGameTime();
        if (engine == null) {
            String[] parts = profile.getPath().split("/", -1);
            if (parts.length != 3 || !parts[0].equals("aircraft_engine")
                    || !parts[1].matches("[a-z0-9_]+")
                    || !(parts[2].equals("idle") || parts[2].equals("startup") || parts[2].equals("single"))) return;
            // Prioritize the local pilot rather than evicting/restarting loops for a busy flyover.
            if (ACTIVE.size() >= MAX_ENGINES) {
                if (client.player.getVehicle() != vehicle) return;
                Iterator<Engine> iterator = ACTIVE.values().iterator();
                Engine oldest = iterator.next();
                stop(client, oldest);
                iterator.remove();
            }
            engine = new Engine(vehicle, profile, parts[2], time);
            ACTIVE.put(vehicle.getUUID(), engine);
            engine.run = new Layer(engine, vehicle.getEngineSound(), false, false);
            client.getSoundManager().play(engine.run);
            SoundEvent lowPower = SoundEvent.createVariableRangeEvent(
                    new ResourceLocation(BertsVehiclePack.MODID, parts[1] + "_engine_start"));
            if (parts[2].equals("idle")) {
                engine.low = new Layer(engine, lowPower, true, false);
                client.getSoundManager().play(engine.low);
            } else if (parts[2].equals("startup") && engine.throttle() < 0.25F
                    && client.player.getVehicle() == vehicle) {
                // Entering range of an already flying aircraft must not replay its starter.
                engine.low = new Layer(engine, lowPower, true, true);
                client.getSoundManager().play(engine.low);
            }
        }
        if (engine.lastSample != time) {
            engine.envelope.tick(engine.throttle());
            engine.lastSample = time;
        }
        engine.lastSeen = time;
        if (time >= engine.nextRetry) {
            engine.nextRetry = time + 20;
            // Sound resource reload/channel starvation may terminate a loop independently.
            if (!client.getSoundManager().isActive(engine.run)) {
                client.getSoundManager().stop(engine.run);
                engine.run = new Layer(engine, vehicle.getEngineSound(), false, false);
                client.getSoundManager().play(engine.run);
            }
            if (engine.mode.equals("idle") && !client.getSoundManager().isActive(engine.low)) {
                SoundEvent event = engine.low.event;
                client.getSoundManager().stop(engine.low);
                engine.low = new Layer(engine, event, true, false);
                client.getSoundManager().play(engine.low);
            }
        }
    }

    private static void maintain(Minecraft client) {
        Iterator<Engine> iterator = ACTIVE.values().iterator();
        while (iterator.hasNext()) {
            Engine engine = iterator.next();
            if (!audible(client, engine.vehicle) || client.level.getGameTime() - engine.lastSeen > 2) {
                stop(client, engine);
                iterator.remove();
            }
        }
    }

    private static void stop(Minecraft client, Engine engine) {
        client.getSoundManager().stop(engine.run);
        if (engine.low != null) client.getSoundManager().stop(engine.low);
    }

    private static final class Engine {
        final VehicleEntity vehicle;
        final ResourceLocation profile;
        final String mode;
        final AircraftEngineEnvelope envelope;
        long lastSeen, lastSample, nextRetry;
        Layer run, low;

        Engine(VehicleEntity vehicle, ResourceLocation profile, String mode, long time) {
            this.vehicle = vehicle;
            this.profile = profile;
            this.mode = mode;
            envelope = new AircraftEngineEnvelope(throttle());
            lastSeen = lastSample = time;
            nextRetry = time + 20;
        }

        float throttle() {
            return (float) vehicle.getVehicleFlightInstrumentSnapshot(1F).getThrottle();
        }

        boolean afterburner() {
            var surfaces = vehicle.getVehicleFlightInstrumentSnapshot(1F).getControlSurfaces();
            return surfaces != null && surfaces.getAfterburnerActive();
        }

        float gain() {
            var value = vehicle.computed().getEngineInfo().get("EngineSoundVolume");
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return 0.7F;
            float gain = value.getAsFloat();
            return Float.isFinite(gain) ? Math.max(0F, Math.min(2F, gain)) : 0F;
        }
    }

    private static final class Layer extends VehicleSoundInstance {
        final Engine engine;
        final SoundEvent event;
        final boolean lowPower, startup;

        Layer(Engine engine, SoundEvent event, boolean lowPower, boolean startup) {
            super(event, Minecraft.getInstance(), engine.vehicle);
            this.engine = engine;
            this.event = event;
            this.lowPower = lowPower;
            this.startup = startup;
            looping = !startup;
            // Keep silent layers alive for crossfades instead of restarting their samples.
            volume = Math.max(0.001F, getVolume(engine.vehicle));
        }

        @Override public boolean canStartSilent() { return true; }

        @Override protected boolean canPlay(VehicleEntity vehicle) {
            return vehicle.engineRunning() && !vehicle.isWreck();
        }

        @Override protected float getPitch(VehicleEntity vehicle) {
            return startup ? 1F : lowPower ? engine.envelope.idlePitch()
                    : engine.envelope.runningPitch(engine.afterburner());
        }

        @Override protected float getVolume(VehicleEntity vehicle) {
            return engine.gain() * (startup ? 0.55F : lowPower ? engine.envelope.idleGain()
                    : engine.envelope.runningGain(engine.mode.equals("idle"), engine.afterburner()));
        }
    }
}
