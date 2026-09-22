package com.yourname.berts_vehicle_pack.client.renderer;

import com.atsuishio.superbwarfare.api.projectile.ProjectileTrailKind;
import com.atsuishio.superbwarfare.api.projectile.ProjectileTrailProviders;
import com.atsuishio.superbwarfare.entity.projectile.FastThrowableProjectile;
import com.atsuishio.superbwarfare.entity.projectile.WireGuideMissileEntity;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.yourname.berts_vehicle_pack.client.BvpClientParticles;
import com.yourname.berts_vehicle_pack.effects.BvpProjectileEffectDefinition;
import com.yourname.berts_vehicle_pack.effects.BvpTrailDiagnostics;
import com.yourname.berts_vehicle_pack.effects.BvpTracerProfile;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Random;
import java.util.UUID;

public final class BvpProjectileTrailRenderer {
    private static final int MAX_CENTER_SAMPLES_PER_EMIT = 8;
    private static final int MAX_PARTICLES_PER_EMIT = 64;
    private static final int MAX_TRAIL_PARTICLES_PER_TICK = 256;
    private static final double GAUSSIAN_SPREAD_BLOCKS = 0.10D;
    private static final double MIN_FLIGHT_DIRECTION_SQR = 1.0E-8D;
    private static final long STALE_TRAIL_STATE_TICKS = 80L;
    private static final int WIRE_SEGMENTS = 40;
    private static final int MAX_WIRES_PER_FRAME = 32;
    private static final double MAX_WIRE_RENDER_DISTANCE_SQR = 180.0D * 180.0D;
    private static final double MAX_WIRE_LENGTH_BLOCKS = 256.0D;
    private static final double WIRE_PROJECTILE_REAR_OFFSET_BLOCKS = 0.24D;
    private static final double WIRE_LAUNCH_SIDE_OFFSET_BLOCKS = 0.10D;
    private static final double WIRE_FULLY_UNWOUND_DISTANCE_BLOCKS = 180.0D;
    private static final double WIRE_MAX_SAG_BLOCKS = 10.0D;
    private static final double WIRE_MAX_LAUNCH_CURL_BLOCKS = 1.8D;
    private static final RenderType WIRE_OUTER_RENDER_TYPE = WireRenderType.create(
            "bvp_atgm_wire_outer", 2.2D);
    private static final RenderType WIRE_INNER_RENDER_TYPE = WireRenderType.create(
            "bvp_atgm_wire_inner", 0.9D);
    private static final Vec3 WORLD_UP = new Vec3(0.0D, 1.0D, 0.0D);
    private static final Vec3 WORLD_X = new Vec3(1.0D, 0.0D, 0.0D);
    private static final Map<UUID, TrailState> TRAILS = new HashMap<>();
    private static ClientLevel activeLevel;
    private static long particleBudgetTick = Long.MIN_VALUE;
    private static int particleBudgetUsed;

    private BvpProjectileTrailRenderer() {
    }

    public static void registerProviders() {
        // v1 is extension-driven; the fallback is the single generic admission seam.
        ProjectileTrailProviders.registerFallback(
                new ResourceLocation("berts_vehicle_pack", "projectile_effect_v1"),
                BvpProjectileTrailRenderer::emitFromNative);
    }

    private static boolean emitFromNative(FastThrowableProjectile projectile, ProjectileTrailKind kind) {
        // Typed per-shot profiles (belt GREEN or the ordinary tank-shell policy) own the
        // complete tracer presentation.  Do not also emit the generated v1 particle fallback.
        if (BvpTracerProfile.enabled(projectile)) {
            return true;
        }
        BvpProjectileEffectDefinition definition = BvpProjectileEffectDefinition.forEntity(projectile);
        if (definition == null) {
            long tick = projectile.m_9236_() == null ? projectile.f_19797_ : projectile.m_9236_().m_46467_();
            BvpTrailDiagnostics.recordProviderFallback(projectile, kind, "missing_or_malformed_v1", tick);
            return false;
        }
        if (definition.trail().isTracer()) {
            return true;
        }
        emit(projectile, definition, kind);
        return true;
    }

    public static void tick(Minecraft minecraft) {
        ClientLevel clientLevel = minecraft == null ? null : minecraft.f_91073_;
        updateLevel(clientLevel);
        if (clientLevel != null) {
            trimStaleStates(clientLevel.m_46467_());
            // Server residency holds cancel the whole projectile tick, including native FX.
            // Refresh an already registered exhaust at its held position without advancing flight.
            for (TrailState state : TRAILS.values()) {
                if (state.projectile instanceof WireGuideMissileEntity missile && state.guidedAtgm
                        && !missile.m_213877_()
                        && com.atsuishio.superbwarfare.client.FarProjectilePlayback.pausedVelocity(missile) != null) {
                    var definition = BvpProjectileEffectDefinition.forEntity(missile);
                    if (definition != null) emit(missile, definition, state.kind);
                }
            }
        }
    }

    private static void emit(FastThrowableProjectile entity, BvpProjectileEffectDefinition definition,
                             ProjectileTrailKind kind) {
        if (entity != null && com.atsuishio.superbwarfare.api.effect.MissilePresentation.hasSharedVisual(entity)) return;
        if (entity == null || entity.m_9236_() == null || !entity.m_9236_().f_46443_) {
            return;
        }
        if (!(entity.m_9236_() instanceof ClientLevel clientLevel)) {
            return;
        }
        updateLevel(clientLevel);
        long tick = clientLevel.m_46467_();
        int entityId = entity.m_19879_();
        UUID entityUuid = entity.m_20148_();
        TrailState state = TRAILS.computeIfAbsent(entityUuid,
                ignored -> new TrailState((((long) entityId) << 32) ^ entityUuid.getLeastSignificantBits()));
        state.projectile = entity;
        state.kind = kind;
        state.guidedAtgm = definition.isGuidedAtgm();
        if (state.lastSpawnTick == tick) {
            return;
        }
        state.lastSpawnTick = tick;

        if (definition.isGuidedAtgm() && entity instanceof WireGuideMissileEntity missile) {
            emitGuidedExhaust(missile, definition, kind, tick);
            return;
        }

        double previousX = entity.f_19854_;
        double previousY = entity.f_19855_ + entity.m_20206_() * 0.5D;
        double previousZ = entity.f_19856_;
        double travelX = entity.m_20185_() - previousX;
        double travelY = entity.m_20186_() + entity.m_20206_() * 0.5D - previousY;
        double travelZ = entity.m_20189_() - previousZ;
        double travelDistance = Math.sqrt(travelX * travelX + travelY * travelY + travelZ * travelZ);
        BvpProjectileEffectDefinition.Trail trail = definition.trail();
        int centerSampleTarget = Math.max(1, Math.min(MAX_CENTER_SAMPLES_PER_EMIT,
                trail.sampleCount(travelDistance)));
        BvpProjectileEffectDefinition.Phase phase = trail.phaseAt(Math.max(0, entity.f_19797_));
        double trailParticleScaleMultiplier = definition.trailParticleScaleMultiplier();
        double sizeScale = Math.max(0.005D, phase.scale() * trailParticleScaleMultiplier);
        double spreadBlocks = GAUSSIAN_SPREAD_BLOCKS * sizeScale;
        boolean emitFlame = trail.flame() && phase.flame();
        boolean emitSmoke = trail.smoke() && phase.smoke();
        int particlesPerPosition = (emitFlame ? 1 : 0) + (emitSmoke ? 1 : 0);
        BvpProjectileEffectDefinition.Orbit orbit = phase.orbit();
        OrbitFrame orbitFrame = orbit == null ? null : orbitFrame(entity, travelX, travelY, travelZ);
        double centralScaleMultiplier = definition.centralTrailScaleMultiplier();
        double centralSpreadBlocks = definition.centralTrailOnAxis() ? 0.0D : spreadBlocks;
        int centerSamples = 0;
        int satelliteSamples = 0;
        int particlesEmitted = 0;
        int positionsPerSample = orbitFrame == null ? 1 : 1 + orbit.count();
        int particlesPerSample = particlesPerPosition * positionsPerSample;
        for (int i = 0; i < centerSampleTarget; i++) {
            if (particlesEmitted + particlesPerSample > MAX_PARTICLES_PER_EMIT
                    || !reserveGlobalParticleBudget(tick, particlesPerSample)) {
                break;
            }
            particlesEmitted += particlesPerSample;
            double randomScale = trail.randomScaleMin()
                    + state.random.nextDouble() * (trail.randomScaleMax() - trail.randomScaleMin());
            double sampleProgress = (i + 1.0D) / centerSampleTarget;
            double centerX = previousX + travelX * sampleProgress;
            double centerY = previousY + travelY * sampleProgress;
            double centerZ = previousZ + travelZ * sampleProgress;
            Vec3 center = new Vec3(centerX, centerY, centerZ);
            OrbitFrame offsetFrame = orbitFrame == null ? orbitFrame(entity, travelX, travelY, travelZ) : orbitFrame;
            if (offsetFrame != null) center = center.m_82549_(offsetWorld(trail.attachmentOffset(), offsetFrame, travelX, travelY, travelZ));
            // Exactly one central stream is emitted per sample.  Guided ATGMs keep that stream
            // on the sampled missile axis; only satellites receive orbital displacement.
            BvpClientParticles.spawnProjectileTrail(clientLevel, trail, phase,
                    center.f_82479_ + state.random.nextGaussian() * centralSpreadBlocks,
                    center.f_82480_ + state.random.nextGaussian() * centralSpreadBlocks,
                    center.f_82481_ + state.random.nextGaussian() * centralSpreadBlocks,
                    randomScale, entity.m_20184_(), centralScaleMultiplier);
            centerSamples++;
            if (orbitFrame != null) {
                double sampleAge = Math.max(0.0D,
                        entity.f_19797_ - 1.0D + sampleProgress);
                emitOrbitingTrails(clientLevel, trail, phase, state,
                        center.f_82479_, center.f_82480_, center.f_82481_,
                        orbitFrame.right(), orbitFrame.up(),
                        orbit,
                        sampleAge * orbit.angularSpeedRadiansPerTick()
                                * definition.orbitAngularSpeedMultiplier(),
                        trailParticleScaleMultiplier,
                        definition.orbitRadiusMultiplier());
                satelliteSamples += orbit.count();
            }
        }
        int emittedPositions = centerSamples + satelliteSamples;
        int flameParticles = emitFlame ? emittedPositions : 0;
        int smokeParticles = emitSmoke ? emittedPositions : 0;
        BvpTrailDiagnostics.recordBvpTrailSpawn(entity, definition, kind, sizeScale,
                centerSamples, satelliteSamples, flameParticles, smokeParticles, tick);
    }

    private static OrbitFrame orbitFrame(Entity entity, double travelX, double travelY, double travelZ) {
        Vec3 forward = travelX * travelX + travelY * travelY + travelZ * travelZ > MIN_FLIGHT_DIRECTION_SQR
                ? new Vec3(travelX, travelY, travelZ) : entity.m_20184_();
        if (forward.m_82556_() <= MIN_FLIGHT_DIRECTION_SQR) {
            return null;
        }
        forward = forward.m_82541_();
        Vec3 reference = Math.abs(forward.f_82480_) < 0.95D ? WORLD_UP : WORLD_X;
        Vec3 right = reference.m_82537_(forward).m_82541_();
        return new OrbitFrame(right, forward.m_82537_(right).m_82541_());
    }

    private static void emitGuidedExhaust(WireGuideMissileEntity missile,
                                         BvpProjectileEffectDefinition definition, ProjectileTrailKind kind, long tick) {
        if (missile.suppressesGuidedPropulsionTrail()) return;
        var profile = com.atsuishio.superbwarfare.api.projectile.ProjectileProfiles.resolve(missile);
        var shape = BvpMissileExhaustGeometry.forProfile(profile);
        if (shape == null) return;
        double exhaustRadius = shape.radius() * 1.4;
        Vec3 motion = missile.m_20184_();
        if (motion.m_82556_() < MIN_FLIGHT_DIRECTION_SQR) {
            Vec3 pausedMotion = com.atsuishio.superbwarfare.client.FarProjectilePlayback.pausedVelocity(missile);
            if (pausedMotion != null) motion = pausedMotion;
        }
        if (motion.m_82556_() < MIN_FLIGHT_DIRECTION_SQR) return;
        Vec3 forward = motion.m_82541_();
        OrbitFrame frame = orbitFrame(missile, motion.f_82479_, motion.f_82480_, motion.f_82481_);
        if (frame == null) return;
        boolean thrust = missile.isGuidedPropulsionThrusting();
        double dx = missile.m_20185_() - missile.f_19854_;
        double dy = missile.m_20186_() - missile.f_19855_;
        double dz = missile.m_20189_() - missile.f_19856_;
        int particlesPerSample = thrust ? 5 : 1;
        int samples = Math.min(MAX_PARTICLES_PER_EMIT / particlesPerSample,
                Math.max(3, Math.min(MAX_CENTER_SAMPLES_PER_EMIT * 3,
                        definition.trail().sampleCount(Math.sqrt(dx * dx + dy * dy + dz * dz)) * 3)));
        int emitted = 0;
        // Triple the original sample rate while retaining per-missile and global FX ceilings.
        for (int sample = 1; sample <= samples; sample++) {
            if (!reserveGlobalParticleBudget(tick, particlesPerSample)) break;
            double t = sample / (double) samples;
            Vec3 center = new Vec3(missile.f_19854_ + (missile.m_20185_() - missile.f_19854_) * t,
                    missile.f_19855_ + (missile.m_20186_() - missile.f_19855_) * t + missile.m_20206_() * 0.5,
                    missile.f_19856_ + (missile.m_20189_() - missile.f_19856_) * t)
                    .m_82546_(forward.m_82490_(shape.rear()));
            BvpClientParticles.spawnMissileExhaust(thrust, center, (float) (exhaustRadius * (thrust ? 1.1 : 2.2)));
            emitted++;
            if (!thrust) continue;
            for (int satellite = 0; satellite < 4; satellite++) {
                double angle = (missile.f_19797_ - 1 + t) * 0.628318531 + satellite * Math.PI / 2;
                Vec3 offset = frame.right().m_82490_(Math.cos(angle) * exhaustRadius * 0.35)
                        .m_82549_(frame.up().m_82490_(Math.sin(angle) * exhaustRadius * 0.35));
                BvpClientParticles.spawnMissileExhaust(true, center.m_82549_(offset), (float) (exhaustRadius * 0.8));
            }
        }
        BvpTrailDiagnostics.recordBvpTrailSpawn(missile, definition, kind,
                exhaustRadius, emitted, thrust ? emitted * 4 : 0,
                thrust ? emitted * 5 : 0, thrust ? 0 : emitted, tick);
    }

    private static void emitOrbitingTrails(ClientLevel level, BvpProjectileEffectDefinition.Trail trail,
                                            BvpProjectileEffectDefinition.Phase phase, TrailState state,
                                            double centerX, double centerY, double centerZ, Vec3 right, Vec3 up,
                                            BvpProjectileEffectDefinition.Orbit orbit, double orbitPhase,
                                            double particleScaleMultiplier, double radiusMultiplier) {
        double satelliteScale = phase.scale() * orbit.scale() * particleScaleMultiplier;
        double spreadBlocks = GAUSSIAN_SPREAD_BLOCKS * satelliteScale;
        double randomScale = trail.randomScaleMin()
                + state.random.nextDouble() * (trail.randomScaleMax() - trail.randomScaleMin());
        for (int index = 0; index < orbit.count(); index++) {
            double angle = orbitPhase + index * (Math.PI * 2.0D / orbit.count());
            double rightScale = Math.cos(angle) * orbit.radiusBlocks() * radiusMultiplier;
            double upScale = Math.sin(angle) * orbit.radiusBlocks() * radiusMultiplier;
            double orbitX = right.f_82479_ * rightScale + up.f_82479_ * upScale;
            double orbitY = right.f_82480_ * rightScale + up.f_82480_ * upScale;
            double orbitZ = right.f_82481_ * rightScale + up.f_82481_ * upScale;
            BvpClientParticles.spawnProjectileTrail(level, trail, phase,
                    centerX + orbitX + state.random.nextGaussian() * spreadBlocks,
                    centerY + orbitY + state.random.nextGaussian() * spreadBlocks,
                    centerZ + orbitZ + state.random.nextGaussian() * spreadBlocks,
                    randomScale, null, particleScaleMultiplier);
        }
    }

    /**
     * Draws the bounded two-pass wire used by SBW's wire-guided ATGM entity.  The shape follows
     * TaP's current presentation: a sagging cubic launch curve with a short-lived unwinding curl,
     * rendered as a dark outer strand and a lighter inner strand.
     */
    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }
        Minecraft minecraft = Minecraft.m_91087_();
        ClientLevel level = minecraft == null ? null : minecraft.f_91073_;
        updateLevel(level);
        if (level == null || TRAILS.isEmpty()) {
            return;
        }

        Camera camera = event.getCamera();
        Vec3 cameraPosition = camera.m_90583_();
        float partialTick = event.getPartialTick();
        MultiBufferSource.BufferSource bufferSource = minecraft.m_91269_().m_110104_();
        VertexConsumer outer = bufferSource.m_6299_(WIRE_OUTER_RENDER_TYPE);
        VertexConsumer inner = bufferSource.m_6299_(WIRE_INNER_RENDER_TYPE);
        PoseStack poseStack = event.getPoseStack();
        int rendered = 0;

        poseStack.m_85836_();
        poseStack.m_252880_((float) -cameraPosition.f_82479_,
                (float) -cameraPosition.f_82480_, (float) -cameraPosition.f_82481_);
        try {
            PoseStack.Pose pose = poseStack.m_85850_();
            for (TrailState state : TRAILS.values()) {
                FastThrowableProjectile projectile = state.projectile;
                if (rendered >= MAX_WIRES_PER_FRAME
                        || !state.guidedAtgm
                        || !(projectile instanceof WireGuideMissileEntity)
                        || projectile.m_213877_()
                        || !projectile.m_6084_()
                        || projectile.m_9236_() != level) {
                    continue;
                }
                Vec3 missilePosition = projectile.m_20318_(partialTick);
                if (!finite(missilePosition)
                        || missilePosition.m_82557_(cameraPosition) > MAX_WIRE_RENDER_DISTANCE_SQR) {
                    continue;
                }
                Vec3 launcherPosition = launcherPosition(projectile, partialTick);
                Vec3 flightDirection = flightDirection(projectile);
                if (!finite(launcherPosition) || flightDirection == null
                        || !drawWire(outer, inner, pose, projectile, missilePosition,
                        launcherPosition, flightDirection)) {
                    continue;
                }
                rendered++;
            }
        } finally {
            poseStack.m_85849_();
            bufferSource.m_109912_(WIRE_OUTER_RENDER_TYPE);
            bufferSource.m_109912_(WIRE_INNER_RENDER_TYPE);
        }
    }

    private static Vec3 launcherPosition(FastThrowableProjectile projectile, float partialTick) {
        Entity owner = projectile.m_19749_();
        if (owner == null || owner.m_213877_()) {
            return null;
        }
        Entity mounted = owner.m_20202_();
        if (mounted instanceof VehicleEntity vehicle) {
            try {
                Vec3 shootPosition = vehicle.getShootPosForHud(owner, partialTick);
                if (finite(shootPosition)) {
                    return shootPosition;
                }
            } catch (RuntimeException ignored) {
                // Fall through to the owner's eye when a vehicle has no current weapon snapshot.
            }
        }
        Vec3 ownerPosition = owner.m_20318_(partialTick);
        return finite(ownerPosition)
                ? ownerPosition.m_82520_(0.0D, owner.m_20192_(), 0.0D)
                : null;
    }

    private static Vec3 flightDirection(FastThrowableProjectile projectile) {
        Vec3 motion = projectile.m_20184_();
        if (finite(motion) && motion.m_82556_() > MIN_FLIGHT_DIRECTION_SQR) {
            return motion.m_82541_();
        }
        Vec3 sampled = new Vec3(projectile.m_20185_() - projectile.f_19854_,
                projectile.m_20186_() - projectile.f_19855_,
                projectile.m_20189_() - projectile.f_19856_);
        return finite(sampled) && sampled.m_82556_() > MIN_FLIGHT_DIRECTION_SQR
                ? sampled.m_82541_() : null;
    }

    private static boolean drawWire(VertexConsumer outer, VertexConsumer inner, PoseStack.Pose pose,
                                    FastThrowableProjectile projectile, Vec3 missilePosition,
                                    Vec3 launcherPosition, Vec3 flightDirection) {
        Vec3 start = missilePosition.m_82549_(
                flightDirection.m_82490_(-WIRE_PROJECTILE_REAR_OFFSET_BLOCKS));
        Vec3 side = WORLD_UP.m_82537_(flightDirection);
        if (side.m_82556_() <= MIN_FLIGHT_DIRECTION_SQR) {
            side = WORLD_X.m_82537_(flightDirection);
        }
        if (side.m_82556_() <= MIN_FLIGHT_DIRECTION_SQR) {
            return false;
        }
        side = side.m_82541_();
        Vec3 end = launcherPosition.m_82549_(side.m_82490_(WIRE_LAUNCH_SIDE_OFFSET_BLOCKS));
        Vec3 delta = end.m_82546_(start);
        double distance = delta.m_82553_();
        if (!Double.isFinite(distance) || distance < 0.05D || distance > MAX_WIRE_LENGTH_BLOCKS) {
            return false;
        }
        Vec3 chordDirection = delta.m_82490_(1.0D / distance);
        double firstControlDistance = clamp(distance * 0.14D, 0.45D, 8.0D);
        double secondControlDistance = clamp(distance * 0.08D, 0.25D, 3.0D);
        Vec3 controlOne = start.m_82549_(flightDirection.m_82490_(-firstControlDistance));
        Vec3 controlTwo = end.m_82549_(chordDirection.m_82490_(-secondControlDistance));
        double horizontalDistance = Math.sqrt(delta.f_82479_ * delta.f_82479_
                + delta.f_82481_ * delta.f_82481_);
        double sag = Math.min(WIRE_MAX_SAG_BLOCKS,
                Math.max(0.12D, horizontalDistance * 0.04D + distance * 0.015D));
        double unwind = clamp(distance / WIRE_FULLY_UNWOUND_DISTANCE_BLOCKS, 0.0D, 1.0D);
        double ageRamp = clamp(projectile.f_19797_ / 4.0D, 0.0D, 1.0D);
        double curlAmplitude = Math.min(WIRE_MAX_LAUNCH_CURL_BLOCKS, 0.45D + distance * 0.015D)
                * (1.0D - unwind * 0.35D) * ageRamp;
        double curlTurns = 4.75D - unwind * 3.65D;

        Vec3 previous = wirePoint(start, controlOne, controlTwo, end, side,
                0.0D, sag, curlAmplitude, curlTurns);
        for (int segment = 1; segment <= WIRE_SEGMENTS; segment++) {
            double progress = segment / (double) WIRE_SEGMENTS;
            Vec3 current = wirePoint(start, controlOne, controlTwo, end, side,
                    progress, sag, curlAmplitude, curlTurns);
            line(outer, pose, previous, current, 0.08F, 0.08F, 0.08F, 0.78F);
            line(inner, pose, previous, current, 0.18F, 0.18F, 0.18F, 0.84F);
            previous = current;
        }
        return true;
    }

    private static Vec3 wirePoint(Vec3 start, Vec3 controlOne, Vec3 controlTwo, Vec3 end,
                                  Vec3 side, double progress, double sag,
                                  double curlAmplitude, double curlTurns) {
        double inverse = 1.0D - progress;
        double a = inverse * inverse * inverse;
        double b = 3.0D * inverse * inverse * progress;
        double c = 3.0D * inverse * progress * progress;
        double d = progress * progress * progress;
        double envelope = Math.sin(Math.PI * progress);
        double curlPhase = progress * curlTurns * Math.PI * 2.0D;
        double sideCurl = Math.sin(curlPhase) * curlAmplitude * envelope;
        double verticalCurl = Math.cos(curlPhase) * curlAmplitude * 0.75D * envelope;
        return new Vec3(
                start.f_82479_ * a + controlOne.f_82479_ * b
                        + controlTwo.f_82479_ * c + end.f_82479_ * d + side.f_82479_ * sideCurl,
                start.f_82480_ * a + controlOne.f_82480_ * b
                        + controlTwo.f_82480_ * c + end.f_82480_ * d - sag * envelope + verticalCurl,
                start.f_82481_ * a + controlOne.f_82481_ * b
                        + controlTwo.f_82481_ * c + end.f_82481_ * d + side.f_82481_ * sideCurl);
    }

    private static void line(VertexConsumer consumer, PoseStack.Pose pose, Vec3 from, Vec3 to,
                             float red, float green, float blue, float alpha) {
        Vec3 normal = to.m_82546_(from);
        if (normal.m_82556_() <= MIN_FLIGHT_DIRECTION_SQR) {
            return;
        }
        normal = normal.m_82541_();
        lineVertex(consumer, pose, from, normal, red, green, blue, alpha);
        lineVertex(consumer, pose, to, normal, red, green, blue, alpha);
    }

    private static void lineVertex(VertexConsumer consumer, PoseStack.Pose pose, Vec3 point, Vec3 normal,
                                   float red, float green, float blue, float alpha) {
        consumer.m_252986_(pose.m_252922_(),
                        (float) point.f_82479_, (float) point.f_82480_, (float) point.f_82481_)
                .m_85950_(red, green, blue, alpha)
                .m_252939_(pose.m_252943_(),
                        (float) normal.f_82479_, (float) normal.f_82480_, (float) normal.f_82481_)
                .m_5752_();
    }

    private static boolean finite(Vec3 value) {
        return value != null && Double.isFinite(value.f_82479_)
                && Double.isFinite(value.f_82480_) && Double.isFinite(value.f_82481_);
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static Vec3 offsetWorld(BvpProjectileEffectDefinition.Vec3Value offset,
                                    OrbitFrame frame, double travelX, double travelY, double travelZ) {
        if (offset == null) return new Vec3(0.0D, 0.0D, 0.0D);
        Vec3 forward = new Vec3(travelX, travelY, travelZ);
        forward = forward.m_82556_() <= MIN_FLIGHT_DIRECTION_SQR
                ? frame.right().m_82537_(frame.up()).m_82541_() : forward.m_82541_();
        return frame.right().m_82490_(offset.x())
                .m_82549_(frame.up().m_82490_(offset.y()))
                .m_82549_(forward.m_82490_(offset.z()));
    }

    private static void trimStaleStates(long tick) {
        TRAILS.values().removeIf(state -> {
            long elapsedTicks = tick - state.lastSpawnTick;
            return state.projectile == null || state.projectile.m_213877_()
                    || state.lastSpawnTick < 0L || elapsedTicks < 0L
                    || elapsedTicks > STALE_TRAIL_STATE_TICKS;
        });
    }

    private static void updateLevel(ClientLevel level) {
        if (activeLevel != level) {
            TRAILS.clear();
            activeLevel = level;
            particleBudgetTick = Long.MIN_VALUE;
            particleBudgetUsed = 0;
        }
    }

    private static boolean reserveGlobalParticleBudget(long tick, int particleCount) {
        if (particleCount <= 0) {
            return false;
        }
        if (particleBudgetTick != tick) {
            particleBudgetTick = tick;
            particleBudgetUsed = 0;
        }
        if (particleBudgetUsed + particleCount > MAX_TRAIL_PARTICLES_PER_TICK) {
            return false;
        }
        particleBudgetUsed += particleCount;
        return true;
    }

    private static final class TrailState {
        final Random random;
        FastThrowableProjectile projectile;
        ProjectileTrailKind kind = ProjectileTrailKind.SMALL;
        boolean guidedAtgm;
        long lastSpawnTick = -1L;

        TrailState(long seed) {
            this.random = new Random(seed);
        }
    }

    private record OrbitFrame(Vec3 right, Vec3 up) {
    }

    /** Accesses RenderType's protected state shards for a depth-tested translucent line batch. */
    private static final class WireRenderType extends RenderType {
        private WireRenderType(String name, VertexFormat format, VertexFormat.Mode mode, int bufferSize,
                               boolean affectsCrumbling, boolean sortOnUpload,
                               Runnable setupState, Runnable clearState) {
            super(name, format, mode, bufferSize, affectsCrumbling, sortOnUpload, setupState, clearState);
        }

        private static RenderType create(String name, double width) {
            return RenderType.m_173215_(
                    name,
                    DefaultVertexFormat.f_166851_,
                    VertexFormat.Mode.LINES,
                    4096,
                    false,
                    false,
                    RenderType.CompositeState.m_110628_()
                            .m_173292_(f_173095_)
                            .m_110673_(new RenderStateShard.LineStateShard(OptionalDouble.of(width)))
                            .m_110669_(f_110117_)
                            .m_110685_(f_110139_)
                            .m_110663_(f_110113_)
                            .m_110675_(f_110123_)
                            .m_110687_(f_110115_)
                            .m_110661_(f_110110_)
                            .m_110691_(false));
        }
    }
}
