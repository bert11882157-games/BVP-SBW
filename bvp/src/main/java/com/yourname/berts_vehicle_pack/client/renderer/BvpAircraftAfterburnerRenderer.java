package com.yourname.berts_vehicle_pack.client.renderer;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.resource.vehicle.DefaultVehicleResource;
import com.atsuishio.superbwarfare.resource.vehicle.DefaultVehicleResource.AfterburnerResource;
import com.atsuishio.superbwarfare.resource.vehicle.VehicleResource;
import com.mojang.logging.LogUtils;
import com.yourname.berts_vehicle_pack.BertsVehiclePack;
import com.yourname.berts_vehicle_pack.init.ModParticles;
import net.minecraft.core.particles.ParticleOptions;
import com.yourname.berts_vehicle_pack.particle.ProjectileEffectParticleOptions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Vector4d;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Client-only afterburner presentation from accepted fixed-wing controls. Each rendered frame the nozzles' world pose
 * at that frame's partial tick is handed to SBW's {@code AfterburnerPlumes}, which draws the flame body, core, shock
 * diamonds and nozzle glow as geometry and owns ignition and shutdown transitions (no particles).
 */
@Mod.EventBusSubscriber(modid = BertsVehiclePack.MODID, value = Dist.CLIENT)
public final class BvpAircraftAfterburnerRenderer {
    private static final int MAX_AIRCRAFT = 64;
    private static final double DEFAULT_NOZZLE_RADIUS = 0.42;
    private static final long VISIBLE_TTL_TICKS = 20;
    private static final LinkedHashMap<VehicleEntity, State> STATES = new LinkedHashMap<>();
    private static final Budget BUDGET = new Budget();
    private static ClientLevel activeLevel;
    private static long clientTick;
    private static int warningsRemaining = 8;

    private BvpAircraftAfterburnerRenderer() {}

    /** Called by the full-model renderer; item previews and non-fixed-wing vehicles are excluded. */
    public static void observe(VehicleEntity vehicle, float partialTicks) {
        observe(vehicle);
        State state = STATES.get(vehicle);
        if (state == null || activeLevel == null) return;
        var controls = vehicle.getVehicleFlightControlSurfaceSnapshot(partialTicks);
        boolean lit = controls != null && controls.getAfterburnerActive();
        double throttle = controls == null ? 0.0D : Math.max(0.0F, Math.min(1.0F, controls.getThrottle()));
        var preview = com.atsuishio.superbwarfare.diagnostics.FxPreview.getEngine();
        if (preview != null) {
            lit = preview.afterburner;
            throttle = preview.throttle;
        }
        try {
            DefaultVehicleResource resource = VehicleResource.getDefault(vehicle);
            if (!state.resolved || state.resource != resource) {
                state.resource = resource;
                state.resolved = true;
                state.warned = false;
                state.config = null;
                state.stop();
                if (resource != null && resource.getAfterburnerPresentation() != null) {
                    state.config = compile(resource.getAfterburnerPresentation());
                }
            }
            Config config = state.config;
            if (config == null) return;
            boolean active = lit && config.afterburning();
            // no shimmer from a stopped engine or a wreck
            double haze = vehicle.engineRunning() || preview != null ? config.haze() : 0.0D;
            var transform = vehicle.getVehicleTransform(partialTicks);
            double time = activeLevel.m_46467_() + partialTicks;
            int index = 0;
            for (Outlet outlet : config.outlets()) {
                Vec3 p = outlet.position();
                Vec3 d = outlet.direction();
                transform.transform(p.f_82479_, p.f_82480_, p.f_82481_, 1.0D, state.point);
                transform.transform(d.f_82479_, d.f_82480_, d.f_82481_, 0.0D, state.direction);
                com.atsuishio.superbwarfare.client.particle.AfterburnerPlumes.submit(vehicle.m_19879_(), index++,
                        state.point.x, state.point.y, state.point.z,
                        state.direction.x, state.direction.y, state.direction.z,
                        outlet.radius(), active, time, config.palette(), throttle, haze);
            }
        } catch (RuntimeException failure) {
            state.config = null;
            if (!state.warned && warningsRemaining > 0) {
                state.warned = true;
                warningsRemaining--;
                LogUtils.getLogger().warn("Afterburner presentation skipped for {}", vehicle.m_20148_(), failure);
            }
        }
    }

    private static void observe(VehicleEntity vehicle) {
        Minecraft minecraft = Minecraft.m_91087_();
        syncLevel(minecraft.f_91073_);
        if (!live(vehicle) || vehicle.getVehicleFlightControlSurfaceSnapshot(1.0F) == null) {
            return;
        }
        State state = STATES.get(vehicle);
        if (state == null) {
            if (STATES.size() >= MAX_AIRCRAFT) {
                var iterator = STATES.keySet().iterator();
                iterator.next();
                iterator.remove();
            }
            state = new State();
            STATES.put(vehicle, state);
        }
        state.lastSeen = clientTick;
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft minecraft = Minecraft.m_91087_();
        syncLevel(minecraft.f_91073_);
        if (activeLevel == null || minecraft.m_91104_()) return;
        clientTick++;
        BUDGET.reset();
        var iterator = STATES.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            VehicleEntity vehicle = entry.getKey();
            State state = entry.getValue();
            if (!live(vehicle) || clientTick - state.lastSeen > VISIBLE_TTL_TICKS) {
                iterator.remove();
            }
        }
    }

    private static void emit(VehicleEntity vehicle, State state) {
        var controls = vehicle.getVehicleFlightControlSurfaceSnapshot(1.0F);
        if (controls == null || !controls.getAfterburnerActive()) {
            state.stop();
            return;
        }
        try {
            DefaultVehicleResource resource = VehicleResource.getDefault(vehicle);
            if (!state.resolved || state.resource != resource) {
                state.resource = resource;
                state.resolved = true;
                state.warned = false;
                state.config = null;
                state.stop();
                if (resource != null && resource.getAfterburnerPresentation() != null) {
                    state.config = compile(resource.getAfterburnerPresentation());
                }
            }
            Config config = state.config;
            if (config == null || !config.afterburning()) return;
            var transform = vehicle.getVehicleTransform(1.0F);
            Vec3 inherited = vehicle.getVehicleFlightPresentationSnapshot(1.0F).getMotion();
            if (!finite(inherited)) return;
            if (!config.tapEmitters().isEmpty()) {
                for (TapEmitter emitter : config.tapEmitters()) {
                    for (int subTick = 0; subTick < 3; subTick++) {
                    if ((state.tapTick * 3 + subTick) % emitter.interval() != 0 || !BUDGET.take()) continue;
                    Vec3 local = tapSample(emitter, state.random.nextFloat(), state.random.nextFloat(), state.random.nextFloat());
                    transform.transform(local.f_82479_, local.f_82480_, local.f_82481_, 1.0D, state.point);
                    Vec3 rawVelocity = emitter.velocity();
                    if (usable(rawVelocity)) {
                        Vec3 aft = rawVelocity.m_82541_().m_82490_(0.08D);
                        transform.transform(local.f_82479_ + aft.f_82479_, local.f_82480_ + aft.f_82480_,
                                local.f_82481_ + aft.f_82481_, 1.0D, state.point);
                    }
                    transform.transform(rawVelocity.f_82479_, rawVelocity.f_82480_, rawVelocity.f_82481_, 0.0D, state.direction);
                    Vec3 velocity = new Vec3(state.direction.x, state.direction.y, state.direction.z);
                    if (!finite(velocity) || !Double.isFinite(state.point.x)
                            || !Double.isFinite(state.point.y) || !Double.isFinite(state.point.z)) continue;
                    ParticleOptions effect = emitter.flame() ? ModParticles.TAP_EXHAUST_FLAME.get() : ModParticles.AFTERBURNER.get();
                    Particle particle = Minecraft.m_91087_().f_91061_.m_107370_(effect,
                            state.point.x, state.point.y, state.point.z,
                            velocity.f_82479_, velocity.f_82480_, velocity.f_82481_);
                    // Align the first queued render interval with the aircraft; subsequent motion
                    // remains the source's constant world velocity, without inherited aircraft speed.
                    seedQueuedParticle(particle, inherited);
                    if (emitter.flame() && BUDGET.take()) {
                        Particle smoke = Minecraft.m_91087_().f_91061_.m_107370_(ModParticles.TAP_EXHAUST_SMOKE.get(),
                                state.point.x, state.point.y, state.point.z, 0, 0, 0);
                        seedQueuedParticle(smoke, inherited);
                    }
                    }
                }
                state.tapTick++;
                return;
            }
            state.advance(config);
            for (Outlet outlet : config.outlets()) {
                Vec3 localPosition = outlet.position();
                Vec3 localDirection = outlet.direction();
                transform.transform(localPosition.f_82479_, localPosition.f_82480_,
                        localPosition.f_82481_, 1.0D, state.point);
                transform.transform(localDirection.f_82479_, localDirection.f_82480_,
                        localDirection.f_82481_, 0.0D, state.direction);
                Vec3 position = new Vec3(state.point.x, state.point.y, state.point.z);
                Vec3 direction = new Vec3(
                        state.direction.x, state.direction.y, state.direction.z);
                if (!finite(position) || !usable(direction)) continue;
                direction = direction.m_82541_();
                position = position.m_82549_(direction.m_82490_(0.08D));
                emitStream(position, direction, inherited,
                        config.flame(), true, state.flameCount);
            }
        } catch (RuntimeException failure) {
            state.config = null;
            if (!state.warned && warningsRemaining > 0) {
                state.warned = true;
                warningsRemaining--;
                LogUtils.getLogger().warn("Afterburner presentation skipped for {}",
                        vehicle.m_20148_(), failure);
            }
        }
    }

    private static void emitStream(Vec3 position, Vec3 direction, Vec3 inherited,
                                   Channel channel, boolean flame, int count) {
        Vec3 velocity = streamVelocity(direction, inherited, channel);
        if (velocity == null) return;
        for (int index = 0; index < count && BUDGET.take(); index++) {
            double offset = streamOffset(channel, index, count);
            ParticleOptions options = flame ? ModParticles.AFTERBURNER.get() : new ProjectileEffectParticleOptions(
                    flame, channel.scale(), 1.0F, channel.lifetime(), channel.width(),
                    (float) velocity.f_82479_, (float) velocity.f_82480_,
                    (float) velocity.f_82481_, channel.red(), channel.green(), channel.blue());
            Particle particle = Minecraft.m_91087_().f_91061_.m_107370_(options,
                    position.f_82479_ + direction.f_82479_ * offset,
                    position.f_82480_ + direction.f_82480_ * offset,
                    position.f_82481_ + direction.f_82481_ * offset,
                    velocity.f_82479_, velocity.f_82480_, velocity.f_82481_);
            seedQueuedParticle(particle, velocity);
        }
    }

    static Vec3 streamVelocity(Vec3 direction, Vec3 inherited, Channel channel) {
        if (!finite(direction) || !finite(inherited)) return null;
        Vec3 velocity = inherited.m_82549_(
                direction.m_82490_(channel.length() / channel.lifetime()));
        return finite(velocity) && Float.isFinite((float) velocity.f_82479_)
                && Float.isFinite((float) velocity.f_82480_)
                && Float.isFinite((float) velocity.f_82481_) ? velocity : null;
    }

    static double streamOffset(Channel channel, int index, int count) {
        // Spread the existing density over one exhaust-relative tick instead of overdrawn pairs.
        return channel.length() / channel.lifetime() * index / count;
    }

    static void seedQueuedParticle(Particle particle, Vec3 velocity) {
        // END-tick emissions enter ParticleEngine after its next update, without a first tick.
        // Seed current position only; the constructor's previous position remains the nozzle.
        // This aligns their first render interval without advancing age, fade, or lifetime.
        if (particle != null && finite(velocity)) {
            particle.m_6257_(velocity.f_82479_, velocity.f_82480_, velocity.f_82481_);
        }
    }

    static Config compile(AfterburnerResource data) {
        if (data == null || (data.schema != 1 && data.schema != 2) || !"VEHICLE_LOCAL_BLOCKS".equals(data.frame)
                || data.outlets == null || data.outlets.length == 0 || data.outlets.length > 8) {
            throw new IllegalArgumentException("Invalid afterburner schema, frame, or outlets");
        }
        var ids = new HashSet<String>();
        var outlets = new ArrayList<Outlet>(data.outlets.length);
        for (var outlet : data.outlets) {
            if (outlet == null || outlet.id == null || outlet.id.isBlank() || !ids.add(outlet.id)) {
                throw new IllegalArgumentException("Missing or duplicate afterburner outlet ID");
            }
            Vec3 position = vector(outlet.position);
            Vec3 direction = vector(outlet.direction);
            if (!usable(direction) || Math.abs(position.f_82479_) > 128
                    || Math.abs(position.f_82480_) > 128 || Math.abs(position.f_82481_) > 128) {
                throw new IllegalArgumentException("Invalid afterburner outlet transform");
            }
            double radius = outlet.nozzleRadiusBlocks == null ? DEFAULT_NOZZLE_RADIUS : outlet.nozzleRadiusBlocks;
            if (!Double.isFinite(radius) || radius < 0.05 || radius > 2.0) {
                throw new IllegalArgumentException("Afterburner nozzle radius outside 0.05..2 blocks");
            }
            outlets.add(new Outlet(outlet.id, position, direction.m_82541_(), radius));
        }
        var emitters = new ArrayList<TapEmitter>();
        if (data.schema == 2) {
            if (data.tapEmitters == null || data.tapEmitters.length == 0 || data.tapEmitters.length > 16)
                throw new IllegalArgumentException("TaP afterburner requires 1..16 source emitters");
            for (var emitter : data.tapEmitters) {
                if (emitter == null || emitter.interval < 1 || emitter.interval > 20
                        || !("FM_FLAME".equals(emitter.style) || "AFTERBURN".equals(emitter.style)))
                    throw new IllegalArgumentException("Invalid TaP afterburner style or interval");
                Outlet outlet = outlets.stream().filter(candidate -> candidate.id().equals(emitter.outlet))
                        .findFirst().orElseThrow(() -> new IllegalArgumentException("Unbound TaP nozzle"));
                Vec3 offset = vector(emitter.offset), extents = vector(emitter.extents), velocity = vector(emitter.velocity);
                if (offset.m_82556_() > 256 || extents.f_82479_ < 0 || extents.f_82480_ < 0 || extents.f_82481_ < 0
                        || extents.m_82556_() > 16 || velocity.m_82556_() > 16)
                    throw new IllegalArgumentException("Unbounded TaP emitter transform");
                emitters.add(new TapEmitter(outlet.position().m_82549_(offset), extents, velocity,
                        emitter.interval, "FM_FLAME".equals(emitter.style)));
            }
        }
        boolean afterburning = data.afterburning == null || data.afterburning;
        double haze = data.haze == null ? 1.0D : data.haze;
        if (!Double.isFinite(haze) || haze < 0.0D || haze > 2.0D) {
            throw new IllegalArgumentException("Afterburner haze outside 0..2");
        }
        return new Config(List.copyOf(outlets),
                channel(data.flame, true), channel(data.smoke, false), List.copyOf(emitters),
                palette(data.palette), afterburning, haze);
    }

    /** Core, flame, tail and diamond RGB as twelve floats; null when absent (the neutral orange). */
    static float[] palette(AfterburnerResource.Palette palette) {
        if (palette == null) return null;
        double[][] parts = {palette.core, palette.flame, palette.tail, palette.diamonds};
        float[] result = new float[12];
        for (int part = 0; part < 4; part++) {
            double[] rgb = parts[part];
            if (rgb == null || rgb.length != 3) throw new IllegalArgumentException("Afterburner palette needs four RGB triples");
            for (int c = 0; c < 3; c++) {
                if (!Double.isFinite(rgb[c]) || rgb[c] < 0.0D || rgb[c] > 1.0D) {
                    throw new IllegalArgumentException("Afterburner palette value outside 0..1");
                }
                result[part * 3 + c] = (float) rgb[c];
            }
        }
        return result;
    }

    static Vec3 tapSample(TapEmitter emitter, float x, float y, float z) {
        return emitter.position().m_82520_((x - 0.5) * emitter.extents().f_82479_,
                (y - 0.5) * emitter.extents().f_82480_, (z - 0.5) * emitter.extents().f_82481_);
    }

    private static Channel channel(AfterburnerResource.Stream stream, boolean flame) {
        if (stream == null) throw new IllegalArgumentException("Null afterburner stream");
        float scale = (float) number(stream.scale, flame ? 0.5 : 0.35, 0.005, 4);
        float width = (float) number(stream.widthBlocks, 0.1, 0.001, 1);
        double length = number(stream.lengthBlocks, 4, 0, 16);
        double density = number(stream.particlesPerTick, flame ? 2 : 0.5, 0, 4);
        int lifetime = stream.lifetimeTicks == null ? (flame ? 6 : 12) : stream.lifetimeTicks;
        if (lifetime < 1 || lifetime > 16) {
            throw new IllegalArgumentException("Afterburner lifetime outside 1..16 ticks");
        }
        double[] color = stream.colorRgb == null ? new double[] {1, 1, 1} : stream.colorRgb;
        if (color.length != 3) throw new IllegalArgumentException("Invalid afterburner RGB");
        // The dedicated TaP particle owns its exact size/fade. Outlet anchors and emission budgets
        // remain pack-owned; a six-tick moving plume preserves those nozzles at flight speed.
        if (flame) {
            lifetime = 6;
        }
        return new Channel(scale, width, length, density, lifetime,
                (float) number(color[0], 1, 0, 1),
                (float) number(color[1], 1, 0, 1),
                (float) number(color[2], 1, 0, 1));
    }

    private static double number(Double value, double fallback, double min, double max) {
        double result = value == null ? fallback : value;
        if (!Double.isFinite(result) || result < min || result > max) {
            throw new IllegalArgumentException("Afterburner value outside its finite bounds");
        }
        return result;
    }

    private static Vec3 vector(double[] value) {
        if (value == null || value.length != 3) {
            throw new IllegalArgumentException("Afterburner vectors require three components");
        }
        Vec3 result = new Vec3(value[0], value[1], value[2]);
        if (!finite(result)) throw new IllegalArgumentException("Nonfinite afterburner vector");
        return result;
    }

    private static boolean finite(Vec3 vector) {
        return vector != null && Double.isFinite(vector.f_82479_)
                && Double.isFinite(vector.f_82480_) && Double.isFinite(vector.f_82481_);
    }

    private static boolean usable(Vec3 vector) {
        return finite(vector) && Double.isFinite(vector.m_82556_())
                && vector.m_82556_() > 1.0E-8D;
    }

    private static boolean live(VehicleEntity vehicle) {
        return activeLevel != null && vehicle != null && vehicle.m_9236_() == activeLevel
                && !vehicle.m_213877_() && vehicle.m_6084_() && !vehicle.isWreck()
                && vehicle.isFixedWingFlightVehicle()
                && (activeLevel.m_6815_(vehicle.m_19879_()) == vehicle
                    || com.atsuishio.superbwarfare.api.vehicle.render.FarVehicleCopies.isCopy(vehicle));
    }

    private static void syncLevel(ClientLevel level) {
        if (activeLevel != level) {
            clear();
            activeLevel = level;
            clientTick = 0;
        }
    }

    private static void clear() {
        STATES.clear();
        BUDGET.reset();
        warningsRemaining = 8;
    }

    @SubscribeEvent
    public static void unload(LevelEvent.Unload event) {
        if (event.getLevel() == activeLevel) syncLevel(null);
    }

    @SubscribeEvent
    public static void disconnect(ClientPlayerNetworkEvent.LoggingOut event) {
        syncLevel(null);
    }

    record Outlet(String id, Vec3 position, Vec3 direction, double radius) {}
    record TapEmitter(Vec3 position, Vec3 extents, Vec3 velocity, int interval, boolean flame) {}
    record Channel(float scale, float width, double length, double density, int lifetime,
                   float red, float green, float blue) {}
    record Config(List<Outlet> outlets, Channel flame, Channel smoke, List<TapEmitter> tapEmitters,
                  float[] palette, boolean afterburning, double haze) {}

    static final class State {
        DefaultVehicleResource resource;
        Config config;
        boolean resolved;
        boolean warned;
        long lastSeen;
        double flameCarry;
        double smokeCarry;
        int flameCount;
        int smokeCount;
        long tapTick;
        final java.util.Random random = new java.util.Random();
        final Vector4d point = new Vector4d();
        final Vector4d direction = new Vector4d();

        void advance(Config config) {
            flameCarry += config.flame().density() * 3;
            smokeCarry += config.smoke().density() * 3;
            flameCount = (int) flameCarry;
            smokeCount = (int) smokeCarry;
            flameCarry -= flameCount;
            smokeCarry -= smokeCount;
        }

        void stop() {
            tapTick = 0;
            flameCarry = smokeCarry = 0;
            flameCount = smokeCount = 0;
        }
    }

    static final class Budget {
        private int remaining = 64;
        boolean take() {
            if (remaining == 0) return false;
            remaining--;
            return true;
        }
        void reset() { remaining = 64; }
    }

    @Mod.EventBusSubscriber(modid = BertsVehiclePack.MODID,
            value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class ReloadEvents {
        private ReloadEvents() {}

        @SubscribeEvent
        public static void register(RegisterClientReloadListenersEvent event) {
            event.registerReloadListener((ResourceManagerReloadListener) resources ->
                    Minecraft.m_91087_().execute(BvpAircraftAfterburnerRenderer::clear));
        }
    }
}
