package com.yourname.berts_vehicle_pack.client;

import com.atsuishio.superbwarfare.api.projectile.impact.ProjectileImpactDisposition;
import com.atsuishio.superbwarfare.api.projectile.impact.ProjectileImpactPresentationOutcome;
import com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.yourname.berts_vehicle_pack.BertsVehiclePack;
import com.yourname.berts_vehicle_pack.armor.BvpImpactFragmentPlanner;
import com.yourname.berts_vehicle_pack.client.renderer.BvpTracerRenderer;
import com.yourname.berts_vehicle_pack.effects.TracerInterpolation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.Random;

/** Client-thread visual simulation. Recipes cannot create or interact with gameplay entities. */
@Mod.EventBusSubscriber(modid = BertsVehiclePack.MODID, value = Dist.CLIENT)
public final class BvpClientImpactFragments {
    private static final double MAX_DISTANCE_SQR = 160.0D * 160.0D;
    private static final Random RANDOM = new Random();
    private static final Pool POOL = new Pool();
    private static ClientLevel activeLevel;

    private BvpClientImpactFragments() {}

    /**
     * Consumes an accepted S2C recipe on the client thread. All vectors are world-space.
     * Randomness is client-local; no fragment state or result is returned to the server.
     */
    public static void accept(ResourceLocation dimension, Vec3 position, Vec3 incoming,
                              Vec3 normal, float caliberMm, int policy) {
        if (!Float.isFinite(caliberMm) || caliberMm <= 0 || policy < 0 || policy > 6) {
            return;
        }
        if (!beginRecipe(dimension, position, incoming, normal)) return;
        var fan = plan(caliberMm, policy, incoming, normal, RANDOM.nextLong());
        boolean admitted = POOL.admit(position, fan);
        if (EliteDiagnostics.isClientEnabled()) {
            EliteDiagnostics.recordClient(activeLevel.m_46467_(), "impact_fragments", "RECIPE",
                    "position", position, "policy", policy, "caliber_mm", caliberMm,
                    "spawned", admitted ? fan.fragments().size() : 0, "live", POOL.bodies.size());
        }
    }

    /** Client-only compatibility adapter. A legacy shared seed is intentionally not consumed. */
    public static void acceptLegacy(Level level, Vec3 position, Vec3 incoming, Vec3 normal,
                                    int count, float scale) {
        if (!RenderSystem.isOnRenderThread() || level == null
                || level != Minecraft.m_91087_().f_91073_
                || count <= 0 || count > Pool.MAX_PER_TICK
                || !Float.isFinite(scale) || scale <= 0 || scale > 64) return;
        if (!beginRecipe(level.m_46472_().m_135782_(), position, incoming, normal)) return;
        POOL.admit(position,
                BvpImpactFragmentPlanner.fan(incoming, normal, count, scale, RANDOM.nextLong()));
    }

    static BvpImpactFragmentPlanner.Plan plan(float caliber, int policy, Vec3 incoming,
                                              Vec3 normal, long clientSeed) {
        if (policy == 6) return BvpImpactFragmentPlanner.heavyWarhead(clientSeed);
        if (policy == 5) return BvpImpactFragmentPlanner.fan(incoming, normal, 3, 0.16F, clientSeed);
        int rocketCount = switch (policy) {
            case 2 -> 12;
            case 3 -> 18;
            case 4 -> 24;
            default -> 0;
        };
        return BvpImpactFragmentPlanner.plan(new BvpImpactFragmentPlanner.Input(
                true, ProjectileImpactDisposition.CONSUME,
                ProjectileImpactPresentationOutcome.NON_PENETRATION, false,
                incoming, normal, caliber, policy == 1, policy == 1,
                rocketCount, clientSeed));
    }

    private static boolean beginRecipe(ResourceLocation dimension, Vec3 position,
                                       Vec3 incoming, Vec3 normal) {
        if (!RenderSystem.isOnRenderThread() || !finite(position)
                || !usable(incoming) || !usable(normal)) return false;
        Minecraft minecraft = Minecraft.m_91087_();
        syncLevel(minecraft.f_91073_);
        if (activeLevel == null || minecraft.f_91074_ == null || minecraft.m_91104_()
                || !activeLevel.m_46472_().m_135782_().equals(dimension)
                || position.m_82557_(minecraft.f_91074_.m_20182_()) > MAX_DISTANCE_SQR) {
            return false;
        }
        return POOL.reserveRecipe();
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft minecraft = Minecraft.m_91087_();
        syncLevel(minecraft.f_91073_);
        if (activeLevel != null && !minecraft.m_91104_()) POOL.tick();
    }

    @SubscribeEvent
    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        Minecraft minecraft = Minecraft.m_91087_();
        syncLevel(minecraft.f_91073_);
        if (activeLevel == null || POOL.bodies.isEmpty()) return;
        float partial = event.getPartialTick();
        if (!Float.isFinite(partial)) return;
        partial = Math.max(0, Math.min(1, partial));
        Vec3 camera = event.getCamera().m_90583_();
        var buffers = minecraft.m_91269_().m_110104_();
        var renderType = BvpTracerRenderer.clientFragmentRenderType();
        var vertices = buffers.m_6299_(renderType);
        PoseStack poses = event.getPoseStack();
        poses.m_85836_();
        try {
            poses.m_252880_((float) -camera.f_82479_, (float) -camera.f_82480_,
                    (float) -camera.f_82481_);
            var matrix = poses.m_85850_().m_252922_();
            for (Body body : POOL.bodies) {
                float scale = body.scale
                        * TracerInterpolation.fragmentScale(body.age + partial, body.lifetime);
                if (!(scale > 0)) continue;
                Vec3 position = body.path.position(partial);
                if (position.m_82557_(camera) > MAX_DISTANCE_SQR) continue;
                BvpTracerRenderer.drawClientFragment(
                        vertices, matrix, camera, position, body.direction, scale);
                if (EliteDiagnostics.isClientEnabled()
                        && (!body.recordedVisible || !body.recordedShrink && body.age + partial > body.lifetime * 0.75F)) {
                    body.recordedVisible = true;
                    body.recordedShrink = body.age + partial > body.lifetime * 0.75F;
                    EliteDiagnostics.recordClient(activeLevel.m_46467_(), "impact_fragments", "RENDER_SAMPLE",
                            "position", position, "age", body.age + partial, "lifetime", body.lifetime,
                            "relative_scale", scale / body.scale, "shrinking", body.recordedShrink);
                }
            }
        } finally {
            poses.m_85849_();
            buffers.m_109912_(renderType);
        }
    }

    @SubscribeEvent
    public static void unload(LevelEvent.Unload event) {
        if (event.getLevel() == activeLevel) syncLevel(null);
    }

    @SubscribeEvent
    public static void disconnect(ClientPlayerNetworkEvent.LoggingOut event) {
        syncLevel(null);
    }

    private static void syncLevel(ClientLevel level) {
        if (level != activeLevel) {
            POOL.clear();
            activeLevel = level;
        }
    }

    private static boolean finite(Vec3 vector) {
        return vector != null && Double.isFinite(vector.f_82479_)
                && Double.isFinite(vector.f_82480_) && Double.isFinite(vector.f_82481_);
    }

    private static boolean usable(Vec3 vector) {
        return finite(vector) && Double.isFinite(vector.m_82556_())
                && vector.m_82556_() > 1.0E-6D;
    }

    /** Admission is atomic per fan; overflow drops a recipe rather than truncating it. */
    static final class Pool {
        static final int MAX_LIVE = 512;
        static final int MAX_PER_TICK = 128;
        static final int MAX_RECIPES_PER_TICK = 16;
        final ArrayList<Body> bodies = new ArrayList<>(MAX_LIVE);
        int recipes;
        int admitted;

        boolean reserveRecipe() {
            if (recipes >= MAX_RECIPES_PER_TICK) return false;
            recipes++;
            return true;
        }

        boolean admit(Vec3 position, BvpImpactFragmentPlanner.Plan plan) {
            if (!finite(position) || plan == null || !Float.isFinite(plan.renderScale())
                    || plan.renderScale() <= 0 || plan.renderScale() > 64) return false;
            int count = plan.fragments().size();
            if (count == 0 || count > MAX_PER_TICK - admitted
                    || count > MAX_LIVE - bodies.size()) return false;
            for (var fragment : plan.fragments()) {
                int life = fragment.lifetimeTicks();
                boolean heavy = life == 4 && plan.renderScale() <= 0.12F && fragment.speedBlocksPerTick() >= 4 && fragment.speedBlocksPerTick() <= 6;
                if (!usable(fragment.direction())
                        || !Float.isFinite(fragment.speedBlocksPerTick())
                        || !heavy && (fragment.speedBlocksPerTick() < 0.85F
                        || fragment.speedBlocksPerTick() > 1.65F
                        || life != 10 && life != 12 && life != 14)) return false;
            }
            for (var fragment : plan.fragments()) {
                bodies.add(new Body(position, fragment, plan.renderScale()));
            }
            admitted += count;
            return true;
        }

        void tick() {
            recipes = 0;
            admitted = 0;
            for (int index = bodies.size() - 1; index >= 0; index--) {
                Body body = bodies.get(index);
                if (++body.age >= body.lifetime) {
                    bodies.remove(index);
                } else {
                    body.advance();
                }
            }
        }

        void clear() {
            bodies.clear();
            recipes = 0;
            admitted = 0;
        }
    }

    static final class Body {
        private static final double GRAVITY_BLOCKS_PER_TICK_SQUARED = (double) 0.05F;
        final int lifetime;
        final float scale;
        Vec3 position;
        Vec3 velocity;
        Vec3 direction;
        TracerInterpolation path;
        int age;
        boolean recordedVisible;
        boolean recordedShrink;

        Body(Vec3 origin, BvpImpactFragmentPlanner.Fragment fragment, float scale) {
            this.position = origin;
            this.velocity = fragment.direction().m_82490_(fragment.speedBlocksPerTick());
            this.direction = velocity;
            this.lifetime = fragment.lifetimeTicks();
            this.scale = scale;
            this.path = TracerInterpolation.between(origin, origin);
        }

        void advance() {
            Vec3 previous = position;
            direction = velocity;
            position = position.m_82549_(velocity);
            velocity = velocity.m_82549_(
                    new Vec3(0, -GRAVITY_BLOCKS_PER_TICK_SQUARED, 0));
            path = TracerInterpolation.between(previous, position);
        }
    }

    @Mod.EventBusSubscriber(modid = BertsVehiclePack.MODID,
            value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class ReloadEvents {
        private ReloadEvents() {}

        @SubscribeEvent
        public static void register(RegisterClientReloadListenersEvent event) {
            event.registerReloadListener((ResourceManagerReloadListener) resources ->
                    Minecraft.m_91087_().execute(POOL::clear));
        }
    }
}
