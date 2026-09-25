package com.yourname.berts_vehicle_pack.client.renderer;

import com.atsuishio.superbwarfare.api.performance.ClientRenderPerformanceDiagnostics;
import com.atsuishio.superbwarfare.api.projectile.ProfiledProjectile;
import com.atsuishio.superbwarfare.api.projectile.ProjectileProfiles;
import com.atsuishio.superbwarfare.api.weapon.FiredVisualRecord;
import com.atsuishio.superbwarfare.entity.projectile.ProjectileEntity;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.yourname.berts_vehicle_pack.effects.BvpTracerProfile;
import com.yourname.berts_vehicle_pack.effects.TracerInterpolation;
import com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Profile-driven additive beam renderer that replaces BVP's rectangular projectile bodies. */
public final class BvpTracerRenderer {
    private static final double MIN_DIRECTION_SQR = 1.0E-8D;
    private static final double MAX_RENDER_DISTANCE_SQR = 16384.0D * 16384.0D;
    private static final int MAX_TRACKED_TRACERS = 1024;
    private static final int MAX_TRACER_CANDIDATES = 1024;
    private static final int MAX_STAGED_PROJECTILES = 2048;
    private static final int MAX_ACTIVE_SAMPLES = 1024;
    private static final int MAX_SAMPLES_PER_TICK = 256;
    private static final int MAX_PENDING_LAUNCHES = 2048;
    private static final int MAX_ACCEPTED_IDENTITIES = 2048;
    private static final int MAX_RETIRED_SESSIONS = 8;
    private static final int LAUNCH_TTL_TICKS = 20;
    private static final TracerInterpolation.Cadence RENDER_CADENCE =
            new TracerInterpolation.Cadence();
    private static long retainedSampleTick = Long.MIN_VALUE;
    private static int retainedSamplesThisTick;
    private static long renderFrames;
    private static double worldUnitsPerPixelPerDistance;
    private static final Vec3 WORLD_X = new Vec3(1.0D, 0.0D, 0.0D);
    private static final Vec3 WORLD_Y = new Vec3(0.0D, 1.0D, 0.0D);
    private static final Vec3 WORLD_Z = new Vec3(0.0D, 0.0D, 1.0D);
    private static final RenderType TRACER_RENDER_TYPE = TracerRenderType.create();
    private static final Map<UUID, StagedProjectile> STAGED_PROJECTILES = new LinkedHashMap<>();
    private static final Map<UUID, TracerCandidate> TRACER_CANDIDATES = new LinkedHashMap<>();
    private static final Map<UUID, LiveBeam> LIVE_BEAMS = new LinkedHashMap<>();
    private static final Map<UUID, Double> VISUAL_BIRTHS = new LinkedHashMap<>();
    private static final Map<UUID, LaunchAnchor> PENDING_LAUNCHES = new LinkedHashMap<>();
    private static final Map<UUID, IdentityStamp> ACCEPTED_IDENTITIES = new LinkedHashMap<>();
    private static final Map<UUID, Boolean> RETIRED_SESSIONS = new LinkedHashMap<>();
    private static final Deque<BeamSample> SAMPLES = new ArrayDeque<>();
    private static ClientLevel activeLevel;
    private static UUID activeServerSession;
    private static long activeSessionLatestSequence;
    private static boolean activeSessionHasSequence;

    private BvpTracerRenderer() {
    }

    private static final BvpTracerProfile CLIENT_FRAGMENT_PROFILE = new BvpTracerProfile(
            1.0F, 1.0F, 1.0F, 1, 4.0D, 0.025D, 1.0F, 14,
            1.0D, 1.0F, 4.0D, 0.25F);

    /** Shared client beam state; independent visuals own their simulation and buffer flush. */
    public static RenderType clientFragmentRenderType() {
        return TRACER_RENDER_TYPE;
    }

    public static void drawClientFragment(VertexConsumer vertices, Matrix4f matrix,
                                          Vec3 camera, Vec3 position, Vec3 direction,
                                          float scale) {
        drawBeam(vertices, matrix, camera, position, direction,
                CLIENT_FRAGMENT_PROFILE, 1.0F, scale);
    }

    /**
     * Entity join precedes Forge additional-spawn-data decoding. Stage only the typed projectile
     * contract here and resolve its immutable tracer profile once on the next client tick.
     */
    public static void onEntityAdded(Entity entity) {
        if (!(entity instanceof ProfiledProjectile)
                || !(entity.m_9236_() instanceof ClientLevel level)) {
            return;
        }
        syncLevel(level);
        UUID uuid = entity.m_20148_();
        StagedProjectile previousStaged =
                STAGED_PROJECTILES.put(uuid, new StagedProjectile(entity, entity.m_19879_()));
        TracerCandidate previousCandidate = TRACER_CANDIDATES.remove(uuid);
        if ((previousStaged != null && previousStaged.entity != entity)
                || (previousCandidate != null && previousCandidate.entity != entity)) {
            LIVE_BEAMS.remove(uuid);
            SAMPLES.removeIf(sample -> sample.entityUuid.equals(uuid));
        }
        trimOldest(STAGED_PROJECTILES, MAX_STAGED_PROJECTILES);
    }

    public static void onEntityRemoved(Entity entity) {
        if (entity == null) {
            return;
        }
        UUID uuid = entity.m_20148_();
        StagedProjectile staged = STAGED_PROJECTILES.get(uuid);
        if (staged != null && staged.entity == entity) {
            STAGED_PROJECTILES.remove(uuid);
        }
        TracerCandidate candidate = TRACER_CANDIDATES.get(uuid);
        if (candidate != null && candidate.entity == entity) {
            TRACER_CANDIDATES.remove(uuid);
        }
        LiveBeam beam = LIVE_BEAMS.get(uuid);
        if (beam != null && beam.entity == entity) {
            LIVE_BEAMS.remove(uuid);
        }
    }

    public static void onLevelUnload(ClientLevel level) {
        if (activeLevel == level) {
            syncLevel(null);
        }
    }

    public static void onResourceReload() {
        clearAllState();
        activeLevel = null;
    }

    /** Retains the frozen ballistic muzzle path for tank-cannon and compatibility callers. */
    public static void acceptLaunch(FiredVisualRecord record) {
        if (record == null || !finite(record.getMuzzlePosition()) || !finite(record.getDirection())) {
            return;
        }
        acceptLaunch(record, record.getMuzzlePosition(), record.getDirection(), true);
    }

    /** Marks explicit projectile UUIDs as accepted while their render-time launch frame is unresolved. */
    public static void acceptUnresolvedLaunch(FiredVisualRecord record) {
        if (record == null || !finite(record.getDirection())) {
            return;
        }
        acceptLaunch(record, null, record.getDirection(), false);
    }

    /** Resolves accepted projectile UUIDs to the same immutable observer-local frame as muzzle FX. */
    public static void resolveLaunch(FiredVisualRecord record, Vec3 position, float partialTick) {
        if (record != null) resolveLaunch(record, position, record.getDirection(), partialTick);
    }

    public static void resolveLaunch(FiredVisualRecord record, Vec3 position,
                                     Vec3 direction, float partialTick) {
        if (record == null || !finite(position) || !finite(direction)
                || direction.m_82556_() <= MIN_DIRECTION_SQR || !Float.isFinite(partialTick)) {
            return;
        }
        Minecraft minecraft = Minecraft.m_91087_();
        ClientLevel level = minecraft == null ? null : minecraft.f_91073_;
        syncLevel(level);
        if (level == null) {
            return;
        }
        long tick = level.m_46467_();
        ShotIdentity shotIdentity = new ShotIdentity(record.getServerSessionId(), record.getSequence());
        for (UUID projectileId : record.getSpawnedProjectileIds()) {
            if (projectileId != null) {
                resolveAcceptedLaunch(projectileId, shotIdentity, position, direction,
                        tick, tick + (double) partialTick);
            }
        }
    }

    /** Completes an existing admission; never re-admits or advances the global shot sequence. */
    private static boolean resolveAcceptedLaunch(UUID projectileId, ShotIdentity shotIdentity,
                                                  Vec3 position, Vec3 direction,
                                                  long tick, double presentationStartTick) {
        if (shotIdentity.serverSessionId == null
                || !shotIdentity.serverSessionId.equals(activeServerSession)
                || RETIRED_SESSIONS.containsKey(shotIdentity.serverSessionId)) {
            return false;
        }
        IdentityStamp accepted = ACCEPTED_IDENTITIES.get(projectileId);
        if (accepted == null || !accepted.shotIdentity.equals(shotIdentity)) {
            return false;
        }
        long age = tick - accepted.receivedTick;
        if (age < 0L || age > LAUNCH_TTL_TICKS) {
            return false;
        }
        LiveBeam liveBeam = LIVE_BEAMS.get(projectileId);
        if (liveBeam != null) {
            if (liveBeam.launchAccepted) return false;
            anchorBeam(projectileId, liveBeam, shotIdentity, position, direction, presentationStartTick);
            return true;
        }
        LaunchAnchor pending = PENDING_LAUNCHES.get(projectileId);
        if (pending == null || pending.resolved || !pending.shotIdentity.equals(shotIdentity)
                || tick < pending.receivedTick || tick - pending.receivedTick > LAUNCH_TTL_TICKS) {
            return false;
        }
        putPendingLaunch(projectileId, new LaunchAnchor(shotIdentity, position, direction,
                pending.receivedTick, presentationStartTick, true));
        return true;
    }

    private static void acceptLaunch(FiredVisualRecord record, Vec3 position,
                                     Vec3 direction, boolean resolved) {
        Minecraft minecraft = Minecraft.m_91087_();
        ClientLevel level = minecraft == null ? null : minecraft.f_91073_;
        syncLevel(level);
        if (level == null) {
            return;
        }
        long tick = level.m_46467_();
        ShotIdentity shotIdentity = new ShotIdentity(record.getServerSessionId(), record.getSequence());
        if (!acceptShotIdentity(shotIdentity)) {
            return;
        }
        for (UUID projectileId : record.getSpawnedProjectileIds()) {
            if (projectileId == null) {
                continue;
            }
            if (!acceptProjectileIdentity(projectileId, shotIdentity, tick)) {
                continue;
            }
            LiveBeam liveBeam = LIVE_BEAMS.get(projectileId);
            if (liveBeam != null) {
                if (resolved) {
                    anchorBeam(projectileId, liveBeam, shotIdentity,
                            position, direction, tick);
                } else {
                    waitForAcceptedLaunch(projectileId, liveBeam);
                }
                continue;
            }
            LaunchAnchor existing = PENDING_LAUNCHES.get(projectileId);
            if (existing != null && existing.resolved
                    && existing.shotIdentity.equals(shotIdentity)) {
                continue;
            }
            putPendingLaunch(projectileId,
                    new LaunchAnchor(shotIdentity, position, direction,
                            tick, tick, resolved));
        }
    }

    public static void tick(Minecraft minecraft) {
        ClientLevel level = minecraft == null ? null : minecraft.f_91073_;
        syncLevel(level);
        if (level == null || minecraft.f_91074_ == null) {
            return;
        }

        long performanceStarted = ClientRenderPerformanceDiagnostics.startTimer();
        int candidatesVisited = 0;
        int candidatesAccepted = 0;
        long tick = level.m_46467_();
        trimExpiredSamples(tick);
        PENDING_LAUNCHES.values().removeIf(anchor -> tick - anchor.receivedTick > LAUNCH_TTL_TICKS);
        promoteStagedProjectiles(level);
        Vec3 viewerPosition = minecraft.f_91074_.m_20182_();
        Iterator<Map.Entry<UUID, TracerCandidate>> candidates =
                TRACER_CANDIDATES.entrySet().iterator();
        while (candidates.hasNext()) {
            Map.Entry<UUID, TracerCandidate> entry = candidates.next();
            TracerCandidate candidate = entry.getValue();
            if (!candidate.matches(level, entry.getKey())) {
                candidates.remove();
                LiveBeam stale = LIVE_BEAMS.get(entry.getKey());
                if (stale != null && stale.entity == candidate.entity) {
                    LIVE_BEAMS.remove(entry.getKey());
                }
                continue;
            }
            candidatesVisited++;
            Entity entity = candidate.entity;
            if (entity.m_20182_().m_82557_(viewerPosition) > MAX_RENDER_DISTANCE_SQR) {
                continue;
            }
            BvpTracerProfile profile = candidate.profile;
            Vec3 movement = entity.m_20184_();
            if (movement.m_82556_() <= MIN_DIRECTION_SQR
                    && com.atsuishio.superbwarfare.client.FarProjectilePlayback.pausedVelocity(entity) == null) {
                continue;
            }
            candidatesAccepted++;
            Vec3 end = entity.m_20182_();
            UUID entityUuid = entity.m_20148_();
            LiveBeam beam = LIVE_BEAMS.get(entityUuid);
            Vec3 direction = sampledTrajectoryDirection(entity,
                    beam == null ? null : beam.launchDirection);
            if (direction == null) {
                continue;
            }
            if (beam == null) {
                if (LIVE_BEAMS.size() >= MAX_TRACKED_TRACERS) {
                    Iterator<UUID> iterator = LIVE_BEAMS.keySet().iterator();
                    if (iterator.hasNext()) {
                        iterator.next();
                        iterator.remove();
                    }
                }
                beam = new LiveBeam(entity, profile, tick);
                LIVE_BEAMS.put(entityUuid, beam);
            } else {
                beam.update(entity, profile, tick);
            }
            LaunchAnchor launch = PENDING_LAUNCHES.remove(entityUuid);
            if (launch != null) {
                if (launch.resolved) {
                    anchorBeam(entityUuid, beam, launch.shotIdentity,
                            launch.position, launch.direction,
                            launch.presentationStartTick);
                } else {
                    waitForAcceptedLaunch(entityUuid, beam);
                }
            }
            // Compatibility for fragments already received from an older server. New impact
            // visuals use the separate client pool and never enter the entity tracer path.
            if (!beam.launchAccepted && isImpactShrapnel(entity)) {
                beam.acceptImpact(tick);
            }
            // A distant observer may receive the real projectile after its muzzle event has
            // left the native tracking range. Start from its current authoritative segment.
            if (!beam.launchAccepted && launch == null &&
                    entity.m_20182_().m_82557_(viewerPosition) > 160.0D * 160.0D) {
                beam.acceptImpact(tick - 1);
            }
        }
        LIVE_BEAMS.values().removeIf(beam -> beam.lastSeenTick != tick);
        ClientRenderPerformanceDiagnostics.recordTracerDiscovery(
                performanceStarted, candidatesVisited, candidatesAccepted);
    }

    private static void promoteStagedProjectiles(ClientLevel level) {
        Iterator<Map.Entry<UUID, StagedProjectile>> iterator =
                STAGED_PROJECTILES.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, StagedProjectile> entry = iterator.next();
            StagedProjectile staged = entry.getValue();
            iterator.remove();
            if (!staged.matches(level, entry.getKey())) {
                continue;
            }
            BvpTracerProfile profile = BvpTracerProfile.forEntity(staged.entity);
            if (profile == null || !profile.shouldRender(staged.entity)) {
                continue;
            }
            TracerCandidate previous = TRACER_CANDIDATES.put(
                    entry.getKey(),
                    new TracerCandidate(staged.entity, staged.entityId, profile,
                            ProjectileProfiles.shotSequence(staged.entity)));
            if (previous != null && previous.entity != staged.entity) {
                LIVE_BEAMS.remove(entry.getKey());
                SAMPLES.removeIf(sample -> sample.entityUuid.equals(entry.getKey()));
            }
            trimCandidates();
        }
    }

    private static void trimCandidates() {
        while (TRACER_CANDIDATES.size() > MAX_TRACER_CANDIDATES) {
            Iterator<Map.Entry<UUID, TracerCandidate>> iterator =
                    TRACER_CANDIDATES.entrySet().iterator();
            if (!iterator.hasNext()) {
                return;
            }
            Map.Entry<UUID, TracerCandidate> eldest = iterator.next();
            iterator.remove();
            LiveBeam beam = LIVE_BEAMS.get(eldest.getKey());
            if (beam != null && beam.entity == eldest.getValue().entity) {
                LIVE_BEAMS.remove(eldest.getKey());
            }
        }
    }

    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }
        Minecraft minecraft = Minecraft.m_91087_();
        ClientLevel level = minecraft == null ? null : minecraft.f_91073_;
        syncLevel(level);
        if (level == null || LIVE_BEAMS.isEmpty() && SAMPLES.isEmpty()) {
            return;
        }

        long performanceStarted = ClientRenderPerformanceDiagnostics.startTimer();
        int retainedVisited = 0;
        int liveVisited = 0;
        int beamsDrawn = 0;
        float partialTick = event.getPartialTick();
        if (!Float.isFinite(partialTick)) return;
        partialTick = Math.max(0.0F, Math.min(1.0F, partialTick));
        double renderTick = level.m_46467_() + (double) partialTick;
        long renderNanos = System.nanoTime();
        renderFrames++;
        boolean sampleFrame = RENDER_CADENCE.advance(renderNanos, renderTick);
        if (retainedSampleTick != level.m_46467_()) {
            retainedSampleTick = level.m_46467_();
            retainedSamplesThisTick = 0;
        }
        Camera camera = event.getCamera();
        Vec3 cameraPosition = camera.m_90583_();
        double projectionY = Math.abs(event.getProjectionMatrix().m11());
        int viewportHeight = minecraft.getWindow().getHeight();
        worldUnitsPerPixelPerDistance = Double.isFinite(projectionY) && projectionY > 0 && viewportHeight > 0
                ? 2.0D / (projectionY * viewportHeight) : 0.0D;
        MultiBufferSource.BufferSource bufferSource = minecraft.m_91269_().m_110104_();
        VertexConsumer consumer = bufferSource.m_6299_(TRACER_RENDER_TYPE);
        PoseStack poseStack = event.getPoseStack();

        poseStack.m_85836_();
        poseStack.m_252880_(
                (float) -cameraPosition.f_82479_,
                (float) -cameraPosition.f_82480_,
                (float) -cameraPosition.f_82481_);
        try {
            Matrix4f matrix = poseStack.m_85850_().m_252922_();
            long currentTick = level.m_46467_();
            for (BeamSample sample : SAMPLES) {
                retainedVisited++;
                if (sample.spawnTick > currentTick - 1.0D && LIVE_BEAMS.containsKey(sample.entityUuid)) {
                    continue;
                }
                float remainingLife =
                        (float) (1.0D - (renderTick - sample.spawnTick) / sample.profile.lifetimeTicks());
                if (remainingLife <= 0.0F) {
                    continue;
                }
                if (!finite(sample.end) || sample.end.m_82557_(cameraPosition) > MAX_RENDER_DISTANCE_SQR) {
                    continue;
                }
                drawBeam(consumer, matrix, cameraPosition, sample.end, sample.direction,
                        sample.profile, remainingLife, sample.scale);
                beamsDrawn++;
            }
            for (LiveBeam beam : LIVE_BEAMS.values()) {
                liveVisited++;
                boolean residencyPaused = com.atsuishio.superbwarfare.client.FarProjectilePlayback.pausedVelocity(beam.entity) != null;
                if (!beam.launchAccepted || beam.entity.m_213877_()
                        || beam.entity.m_9236_() != level || !beam.visualReady(renderTick)) {
                    continue;
                }
                if (sampleFrame) {
                    // Retain an already displayed point, never fabricate extra tick subdivisions.
                    if (!residencyPaused && beam.presentation.end() != null && !beam.presentation.launchInterval()
                            && beam.renderedNativeTick != beam.lastSeenTick
                            && !isImpactShrapnel(beam.entity)
                            && retainedSamplesThisTick < MAX_SAMPLES_PER_TICK) {
                        addSample(new BeamSample(beam.entity.m_20148_(), beam.presentation.end(),
                                beam.presentation.direction(), beam.profile, beam.renderedTime,
                                flightScale((float) (beam.renderedTime - beam.visualBirthTick))));
                        retainedSamplesThisTick++;
                    }
                    beam.presentation.sample(renderTick, beam.sampledDirection, beam.profile.lengthBlocks());
                    beam.renderedNativeTick = beam.lastSeenTick;
                    beam.renderedTime = renderTick;
                }
                Vec3 end = beam.presentation.end();
                Vec3 direction = beam.presentation.direction();
                if (!finite(end) || !finite(direction)
                        || end.m_82557_(cameraPosition) > MAX_RENDER_DISTANCE_SQR) {
                    continue;
                }
                float scale = beam.entity instanceof ProjectileEntity fragment && fragment.isImpactShrapnel()
                        ? TracerInterpolation.fragmentScale(Math.max(0, fragment.f_19797_ - 1 + partialTick),
                                fragment.impactShrapnelLifetime())
                        // Paused entity ticks must not restart or delay the half-second visual growth.
                        : flightScale((float) (renderTick - beam.visualBirthTick));
                if (isImpactShrapnel(beam.entity)) {
                    drawBeam(consumer, matrix, cameraPosition, end, direction, beam.profile, 1.0F, scale);
                } else {
                    drawSegment(consumer, matrix, cameraPosition, beam.presentation.start(), end,
                            direction, beam.profile, 1.0F, scale);
                    drawFarHead(consumer, matrix, cameraPosition, end, beam.profile, scale);
                }
                if (EliteDiagnostics.isClientEnabled() && sampleFrame
                        && (beam.presentation.initialSample() || renderTick - beam.lastDiagnostic >= 0.25D)) {
                    beam.lastDiagnostic = renderTick;
                    EliteDiagnostics.record(beam.entity, "tracer", "RENDER_SAMPLE",
                            "render_tick", renderTick, "partial_tick", partialTick,
                            "start", beam.interpolation.start(), "first", beam.interpolation.first(),
                            "second", beam.interpolation.second(), "end", beam.interpolation.end(),
                            "position", end, "scale", scale, "fragment", isImpactShrapnel(beam.entity),
                            "residency_paused", residencyPaused,
                            "sample_hz_cap", TracerInterpolation.MAX_SAMPLE_HZ,
                            "render_frame", renderFrames, "sample_nanos", renderNanos,
                            "sample_slot", RENDER_CADENCE.slot(), "sample_count", RENDER_CADENCE.samples(),
                            "native_tick", beam.lastSeenTick,
                            "source_tick", beam.presentation.sourceTick(),
                            "source_alpha", beam.presentation.sourceAlpha(),
                            "visual_delay_ticks", beam.presentation.delayTicks(),
                            "initial_sample", beam.presentation.initialSample(),
                            "launch_interval", beam.presentation.launchInterval(),
                            "muzzle", beam.presentation.muzzle(),
                            "beam_start", beam.presentation.start(),
                            "muzzle_clearance", TracerInterpolation.MUZZLE_CLEARANCE_BLOCKS);
                }
                beamsDrawn++;
            }
        } finally {
            poseStack.m_85849_();
            bufferSource.m_109912_(TRACER_RENDER_TYPE);
            ClientRenderPerformanceDiagnostics.recordTracerRender(
                    performanceStarted, retainedVisited, liveVisited, beamsDrawn);
        }
    }

    private static void drawBeam(VertexConsumer consumer, Matrix4f matrix, Vec3 cameraPosition,
                                 Vec3 end, Vec3 direction, BvpTracerProfile profile,
                                 float remainingLife) {
        drawBeam(consumer, matrix, cameraPosition, end, direction, profile, remainingLife, 1.0F);
    }

    private static void drawBeam(VertexConsumer consumer, Matrix4f matrix, Vec3 cameraPosition,
                                 Vec3 end, Vec3 direction, BvpTracerProfile profile,
                                 float remainingLife, float scale) {
        if (!(scale > 0)) return;
        if (!finite(direction) || direction.m_82556_() <= MIN_DIRECTION_SQR) {
            return;
        }
        double length = Math.sqrt(direction.m_82556_());
        if (length < 1.0E-4D) return;
        double fx = direction.f_82479_ / length;
        double fy = direction.f_82480_ / length;
        double fz = direction.f_82481_ / length;
        double back = -profile.lengthBlocks() * scale;
        drawSegment(consumer, matrix, cameraPosition,
                end.f_82479_ + fx * back, end.f_82480_ + fy * back, end.f_82481_ + fz * back,
                end.f_82479_, end.f_82480_, end.f_82481_, fx, fy, fz, profile, remainingLife, scale);
    }

    private static void drawSegment(VertexConsumer consumer, Matrix4f matrix, Vec3 cameraPosition,
                                    Vec3 start, Vec3 end, Vec3 forward,
                                    BvpTracerProfile profile, float remainingLife, float scale) {
        drawSegment(consumer, matrix, cameraPosition,
                start.f_82479_, start.f_82480_, start.f_82481_,
                end.f_82479_, end.f_82480_, end.f_82481_,
                forward.f_82479_, forward.f_82480_, forward.f_82481_, profile, remainingLife, scale);
    }

    /** Allocation-free: this runs for every live and retained beam on every rendered frame. */
    private static void drawSegment(VertexConsumer consumer, Matrix4f matrix, Vec3 cameraPosition,
                                    double sx, double sy, double sz, double ex, double ey, double ez,
                                    double fx, double fy, double fz,
                                    BvpTracerProfile profile, float remainingLife, float scale) {
        double tx = cameraPosition.f_82479_ - (sx + ex) * 0.5D;
        double ty = cameraPosition.f_82480_ - (sy + ey) * 0.5D;
        double tz = cameraPosition.f_82481_ - (sz + ez) * 0.5D;
        double sideX = fy * tz - fz * ty;
        double sideY = fz * tx - fx * tz;
        double sideZ = fx * ty - fy * tx;
        if (sideX * sideX + sideY * sideY + sideZ * sideZ <= MIN_DIRECTION_SQR) {
            Vec3 axis = leastParallelAxis(fx, fy, fz);
            sideX = fy * axis.f_82481_ - fz * axis.f_82480_;
            sideY = fz * axis.f_82479_ - fx * axis.f_82481_;
            sideZ = fx * axis.f_82480_ - fy * axis.f_82479_;
        }
        double sideLength = Math.sqrt(sideX * sideX + sideY * sideY + sideZ * sideZ);
        if (sideLength * sideLength <= MIN_DIRECTION_SQR || sideLength < 1.0E-4D) {
            return;
        }
        sideX /= sideLength; sideY /= sideLength; sideZ /= sideLength;
        double upX = fy * sideZ - fz * sideY;
        double upY = fz * sideX - fx * sideZ;
        double upZ = fx * sideY - fy * sideX;
        double upLength = Math.sqrt(upX * upX + upY * upY + upZ * upZ);
        if (upLength < 1.0E-4D) return;
        upX /= upLength; upY /= upLength; upZ /= upLength;
        double aX = sideX + upX, aY = sideY + upY, aZ = sideZ + upZ;
        double aLength = Math.sqrt(aX * aX + aY * aY + aZ * aZ);
        double bX = sideX - upX, bY = sideY - upY, bZ = sideZ - upZ;
        double bLength = Math.sqrt(bX * bX + bY * bY + bZ * bZ);
        if (aLength < 1.0E-4D || bLength < 1.0E-4D) return;
        aX /= aLength; aY /= aLength; aZ /= aLength;
        bX /= bLength; bY /= bLength; bZ /= bLength;
        float baseAlpha = profile.opacity() * remainingLife;
        float glowAlpha = baseAlpha * profile.glowOpacityScale();
        float coreAlpha = baseAlpha * profile.coreOpacityScale();
        double glowHalfWidth = profile.widthBlocks() * profile.glowWidthScale() * 0.5D * scale;
        double coreHalfWidth = profile.widthBlocks() * profile.coreWidthScale() * 0.5D * scale;

        quad(consumer, matrix, sx, sy, sz, ex, ey, ez, sideX, sideY, sideZ, glowHalfWidth, profile, glowAlpha);
        quad(consumer, matrix, sx, sy, sz, ex, ey, ez, upX, upY, upZ, glowHalfWidth, profile, glowAlpha);
        quad(consumer, matrix, sx, sy, sz, ex, ey, ez, aX, aY, aZ, glowHalfWidth, profile, glowAlpha);
        quad(consumer, matrix, sx, sy, sz, ex, ey, ez, bX, bY, bZ, glowHalfWidth, profile, glowAlpha);
        quad(consumer, matrix, sx, sy, sz, ex, ey, ez, sideX, sideY, sideZ, coreHalfWidth, profile, coreAlpha);
        quad(consumer, matrix, sx, sy, sz, ex, ey, ez, upX, upY, upZ, coreHalfWidth, profile, coreAlpha);
    }

    private static void quad(VertexConsumer consumer, Matrix4f matrix,
                             double sx, double sy, double sz, double ex, double ey, double ez,
                             double nx, double ny, double nz, double halfWidth,
                             BvpTracerProfile profile, float alpha) {
        if (!(halfWidth > 0.0D) || !(alpha > 0.0F)) {
            return;
        }
        double ox = nx * halfWidth, oy = ny * halfWidth, oz = nz * halfWidth;
        vertex(consumer, matrix, sx - ox, sy - oy, sz - oz, profile, alpha);
        vertex(consumer, matrix, ex - ox, ey - oy, ez - oz, profile, alpha);
        vertex(consumer, matrix, ex + ox, ey + oy, ez + oz, profile, alpha);
        vertex(consumer, matrix, sx + ox, sy + oy, sz + oz, profile, alpha);
    }

    /** A distant live tracer remains readable end-on; retained trails and collision size are unchanged. */
    private static void drawFarHead(VertexConsumer consumer, Matrix4f matrix, Vec3 camera,
                                    Vec3 point, BvpTracerProfile profile, float scale) {
        double vx = camera.f_82479_ - point.f_82479_;
        double vy = camera.f_82480_ - point.f_82480_;
        double vz = camera.f_82481_ - point.f_82481_;
        double distance = Math.sqrt(vx * vx + vy * vy + vz * vz);
        if (!(distance > 160.0D) || !(worldUnitsPerPixelPerDistance > 0.0D)) return;
        vx /= distance; vy /= distance; vz /= distance;
        Vec3 axis = leastParallelAxis(vx, vy, vz);
        double sideX = vy * axis.f_82481_ - vz * axis.f_82480_;
        double sideY = vz * axis.f_82479_ - vx * axis.f_82481_;
        double sideZ = vx * axis.f_82480_ - vy * axis.f_82479_;
        double sideLength = Math.sqrt(sideX * sideX + sideY * sideY + sideZ * sideZ);
        if (sideLength < 1.0E-4D) return;
        sideX /= sideLength; sideY /= sideLength; sideZ /= sideLength;
        double upX = vy * sideZ - vz * sideY;
        double upY = vz * sideX - vx * sideZ;
        double upZ = vx * sideY - vy * sideX;
        double upLength = Math.sqrt(upX * upX + upY * upY + upZ * upZ);
        if (upLength < 1.0E-4D) return;
        upX /= upLength; upY /= upLength; upZ /= upLength;
        float fade = (float) Math.min(1.0D, (distance - 160.0D) / 160.0D);
        double pixel = distance * worldUnitsPerPixelPerDistance * scale;
        double glow = Math.max(profile.widthBlocks() * profile.glowWidthScale() * scale * 0.5D, pixel);
        double core = Math.max(profile.widthBlocks() * profile.coreWidthScale() * scale * 0.5D, pixel * 0.4D);
        double px = point.f_82479_, py = point.f_82480_, pz = point.f_82481_;
        quad(consumer, matrix, px - sideX * glow, py - sideY * glow, pz - sideZ * glow,
                px + sideX * glow, py + sideY * glow, pz + sideZ * glow,
                upX, upY, upZ, glow, profile, profile.opacity() * profile.glowOpacityScale() * fade);
        quad(consumer, matrix, px - sideX * core, py - sideY * core, pz - sideZ * core,
                px + sideX * core, py + sideY * core, pz + sideZ * core,
                upX, upY, upZ, core, profile, profile.opacity() * profile.coreOpacityScale() * fade);
    }

    private static void vertex(VertexConsumer consumer, Matrix4f matrix, double x, double y, double z,
                               BvpTracerProfile profile, float alpha) {
        consumer.m_252986_(matrix, (float) x, (float) y, (float) z)
                .m_85950_(profile.red(), profile.green(), profile.blue(), alpha)
                .m_5752_();
    }

    private static Vec3 leastParallelAxis(double dx, double dy, double dz) {
        double x = Math.abs(dx);
        double y = Math.abs(dy);
        double z = Math.abs(dz);
        return x <= y && x <= z ? WORLD_X : y <= z ? WORLD_Y : WORLD_Z;
    }

    private static void addSample(BeamSample sample) {
        if (SAMPLES.size() >= MAX_ACTIVE_SAMPLES) {
            SAMPLES.removeFirst();
        }
        SAMPLES.addLast(sample);
    }

    private static void anchorBeam(UUID entityUuid, LiveBeam beam, ShotIdentity shotIdentity,
                                   Vec3 position, Vec3 direction,
                                   double presentationStartTick) {
        if (beam.launchAccepted && shotIdentity.equals(beam.shotIdentity)) {
            return;
        }
        SAMPLES.removeIf(sample -> sample.entityUuid.equals(entityUuid));
        beam.anchor(shotIdentity, position, direction, presentationStartTick);
    }

    private static void waitForAcceptedLaunch(UUID entityUuid, LiveBeam beam) {
        if (beam.launchAccepted) {
            return;
        }
        SAMPLES.removeIf(sample -> sample.entityUuid.equals(entityUuid));
    }

    private static void putPendingLaunch(UUID projectileId, LaunchAnchor anchor) {
        if (!PENDING_LAUNCHES.containsKey(projectileId)
                && PENDING_LAUNCHES.size() >= MAX_PENDING_LAUNCHES) {
            Iterator<UUID> iterator = PENDING_LAUNCHES.keySet().iterator();
            if (iterator.hasNext()) {
                iterator.next();
                iterator.remove();
            }
        }
        PENDING_LAUNCHES.put(projectileId, anchor);
    }

    private static boolean acceptShotIdentity(ShotIdentity shotIdentity) {
        UUID serverSessionId = shotIdentity.serverSessionId;
        if (serverSessionId == null) {
            return false;
        }
        if (serverSessionId.equals(activeServerSession)) {
            if (!activeSessionHasSequence) {
                activeSessionLatestSequence = shotIdentity.sequence;
                activeSessionHasSequence = true;
                return true;
            }
            if (shotIdentity.sequence == activeSessionLatestSequence) {
                return true;
            }
            if (!isNewerSequence(shotIdentity.sequence, activeSessionLatestSequence)) {
                return false;
            }
            activeSessionLatestSequence = shotIdentity.sequence;
            return true;
        }
        if (RETIRED_SESSIONS.containsKey(serverSessionId)) {
            return false;
        }
        if (activeServerSession != null) {
            RETIRED_SESSIONS.put(activeServerSession, Boolean.TRUE);
            trimOldest(RETIRED_SESSIONS, MAX_RETIRED_SESSIONS);
        }
        LIVE_BEAMS.clear();
        VISUAL_BIRTHS.clear();
        PENDING_LAUNCHES.clear();
        ACCEPTED_IDENTITIES.clear();
        SAMPLES.clear();
        RENDER_CADENCE.reset();
        activeServerSession = serverSessionId;
        activeSessionLatestSequence = shotIdentity.sequence;
        activeSessionHasSequence = true;
        return true;
    }

    private static boolean acceptProjectileIdentity(UUID projectileId, ShotIdentity shotIdentity,
                                                    long receivedTick) {
        IdentityStamp existing = ACCEPTED_IDENTITIES.get(projectileId);
        if (existing != null) {
            if (!existing.shotIdentity.serverSessionId.equals(shotIdentity.serverSessionId)) {
                return false;
            }
            if (existing.shotIdentity.sequence == shotIdentity.sequence) {
                // An exact duplicate must not restart presentation or renew the receipt age.
                return false;
            }
            if (!isNewerSequence(shotIdentity.sequence, existing.shotIdentity.sequence)) {
                return false;
            }
            resetProjectilePresentation(projectileId, shotIdentity);
        }
        ACCEPTED_IDENTITIES.remove(projectileId);
        ACCEPTED_IDENTITIES.put(projectileId, new IdentityStamp(shotIdentity, receivedTick));
        trimOldest(ACCEPTED_IDENTITIES, MAX_ACCEPTED_IDENTITIES);
        return true;
    }

    private static boolean isNewerSequence(long candidate, long current) {
        return candidate != current && candidate - current > 0L;
    }

    private static void resetProjectilePresentation(UUID projectileId, ShotIdentity replacement) {
        LIVE_BEAMS.remove(projectileId);
        PENDING_LAUNCHES.remove(projectileId);
        SAMPLES.removeIf(sample -> sample.entityUuid.equals(projectileId));
        TracerCandidate candidate = TRACER_CANDIDATES.get(projectileId);
        if (candidate != null && candidate.shotSequence != replacement.sequence) {
            TRACER_CANDIDATES.remove(projectileId);
        }
        StagedProjectile staged = STAGED_PROJECTILES.get(projectileId);
        if (staged != null
                && ProjectileProfiles.shotSequence(staged.entity) != replacement.sequence) {
            STAGED_PROJECTILES.remove(projectileId);
        }
    }

    private static <K, V> void trimOldest(Map<K, V> map, int maximumSize) {
        while (map.size() > maximumSize) {
            Iterator<K> iterator = map.keySet().iterator();
            if (!iterator.hasNext()) {
                return;
            }
            iterator.next();
            iterator.remove();
        }
    }

    private static boolean finite(Vec3 vector) {
        return vector != null
                && Double.isFinite(vector.f_82479_)
                && Double.isFinite(vector.f_82480_)
                && Double.isFinite(vector.f_82481_);
    }

    private static boolean isImpactShrapnel(Entity entity) {
        return entity instanceof ProjectileEntity fragment && fragment.isImpactShrapnel();
    }

    public static float flightScale(float ageTicks) {
        return Float.isFinite(ageTicks) ? 1F + 0.4F * Math.max(0F, Math.min(1F, ageTicks / 10F)) : 1F;
    }

    /**
     * Uses the same previous/current position pair as Entity#getPosition(partialTick). The entity's
     * current delta movement already describes the next integration step after gravity/collision,
     * so pairing it with an interpolated endpoint can visibly cant the beam off its sampled path.
     */
    private static Vec3 sampledTrajectoryDirection(Entity entity, Vec3 acceptedFallback) {
        Vec3 previous = entity.m_20318_(0.0F);
        Vec3 current = entity.m_20318_(1.0F);
        if (!finite(previous) || !finite(current)) {
            return null;
        }
        Vec3 chord = current.m_82546_(previous);
        if (chord.m_82556_() > MIN_DIRECTION_SQR) {
            return chord.m_82541_();
        }
        Vec3 movement = entity.m_20184_();
        if (finite(movement) && movement.m_82556_() > MIN_DIRECTION_SQR) {
            return movement.m_82541_();
        }
        Vec3 pausedMotion = com.atsuishio.superbwarfare.client.FarProjectilePlayback.pausedVelocity(entity);
        if (finite(pausedMotion) && pausedMotion.m_82556_() > MIN_DIRECTION_SQR) {
            return pausedMotion.m_82541_();
        }
        return finite(acceptedFallback) && acceptedFallback.m_82556_() > MIN_DIRECTION_SQR
                ? acceptedFallback.m_82541_() : null;
    }

    private static void trimExpiredSamples(long tick) {
        SAMPLES.removeIf(sample -> {
            double age = tick - sample.spawnTick;
            return age < 0L || age >= sample.profile.lifetimeTicks();
        });
    }

    private static void syncLevel(ClientLevel level) {
        if (activeLevel == level) {
            return;
        }
        clearAllState();
        activeLevel = level;
    }

    private static void clearAllState() {
        STAGED_PROJECTILES.clear();
        TRACER_CANDIDATES.clear();
        LIVE_BEAMS.clear();
        VISUAL_BIRTHS.clear();
        PENDING_LAUNCHES.clear();
        ACCEPTED_IDENTITIES.clear();
        RETIRED_SESSIONS.clear();
        SAMPLES.clear();
        activeServerSession = null;
        activeSessionLatestSequence = 0L;
        activeSessionHasSequence = false;
        RENDER_CADENCE.reset();
        retainedSampleTick = Long.MIN_VALUE;
        retainedSamplesThisTick = 0;
        renderFrames = 0L;
    }

    private static boolean matchesEntity(Entity entity, int entityId,
                                         ClientLevel level, UUID expectedUuid) {
        return entity != null
                && entity.m_9236_() == level
                && !entity.m_213877_()
                && entity.m_19879_() == entityId
                && expectedUuid.equals(entity.m_20148_())
                && level.m_6815_(entityId) == entity;
    }

    private static final class StagedProjectile {
        final Entity entity;
        final int entityId;

        StagedProjectile(Entity entity, int entityId) {
            this.entity = entity;
            this.entityId = entityId;
        }

        boolean matches(ClientLevel level, UUID expectedUuid) {
            return matchesEntity(this.entity, this.entityId, level, expectedUuid);
        }
    }

    private static final class TracerCandidate {
        final Entity entity;
        final int entityId;
        final BvpTracerProfile profile;
        final long shotSequence;

        TracerCandidate(Entity entity, int entityId, BvpTracerProfile profile, long shotSequence) {
            this.entity = entity;
            this.entityId = entityId;
            this.profile = profile;
            this.shotSequence = shotSequence;
        }

        boolean matches(ClientLevel level, UUID expectedUuid) {
            return matchesEntity(this.entity, this.entityId, level, expectedUuid);
        }
    }

    private static final class LiveBeam {
        Entity entity;
        BvpTracerProfile profile;
        long lastSeenTick;
        Vec3 launchDirection;
        Vec3 sampledDirection;
        final TracerInterpolation.Presentation presentation = new TracerInterpolation.Presentation();
        long renderedNativeTick = Long.MIN_VALUE;
        double renderedTime;
        ShotIdentity shotIdentity;
        boolean launchAccepted;
        final double visualBirthTick;
        double presentationStartTick;
        double lastDiagnostic = Double.NEGATIVE_INFINITY;
        TracerInterpolation interpolation;

        LiveBeam(Entity entity, BvpTracerProfile profile, long lastSeenTick) {
            UUID id = entity.m_20148_();
            if (!VISUAL_BIRTHS.containsKey(id) && VISUAL_BIRTHS.size() >= MAX_ACCEPTED_IDENTITIES) {
                VISUAL_BIRTHS.remove(VISUAL_BIRTHS.keySet().iterator().next());
            }
            // Admission/pause transitions may recreate the beam or re-anchor its interpolation.
            // Preserve one bounded visual clock for the projectile across those transitions.
            this.visualBirthTick = VISUAL_BIRTHS.computeIfAbsent(id, ignored -> (double) lastSeenTick);
            update(entity, profile, lastSeenTick);
        }

        void update(Entity entity, BvpTracerProfile profile, long lastSeenTick) {
            this.entity = entity;
            this.profile = profile;
            this.lastSeenTick = lastSeenTick;
            this.interpolation = TracerInterpolation.between(entity.m_20318_(0.0F), entity.m_20318_(1.0F));
            this.sampledDirection = sampledTrajectoryDirection(entity, launchDirection);
            this.presentation.update(interpolation, lastSeenTick);
        }

        void anchor(ShotIdentity shotIdentity, Vec3 position, Vec3 direction,
                    double presentationStartTick) {
            this.launchAccepted = true;
            this.shotIdentity = shotIdentity;
            this.launchDirection = direction.m_82541_();
            this.presentation.anchor(position, direction);
            this.presentationStartTick = presentationStartTick;
        }

        void acceptImpact(long tick) {
            this.launchAccepted = true;
            this.shotIdentity = null;
            this.launchDirection = null;
            // Retain the old-server fragment delay; the independent client pool is unchanged.
            this.presentationStartTick = tick + 1.0D;
        }

        boolean visualReady(double renderTick) {
            return Double.isFinite(renderTick) && renderTick >= presentationStartTick;
        }
    }

    private record ShotIdentity(UUID serverSessionId, long sequence) {
    }

    private record IdentityStamp(ShotIdentity shotIdentity, long receivedTick) {
    }

    private record LaunchAnchor(ShotIdentity shotIdentity, Vec3 position, Vec3 direction, long receivedTick,
                                double presentationStartTick, boolean resolved) {
    }

    private record BeamSample(
            UUID entityUuid,
            Vec3 end,
            Vec3 direction,
            BvpTracerProfile profile,
            double spawnTick, float scale) {
    }

    /** Accesses RenderType's protected state shards without leaking global render state. */
    private static final class TracerRenderType extends RenderType {
        private TracerRenderType(String name, VertexFormat format, VertexFormat.Mode mode, int bufferSize,
                                 boolean affectsCrumbling, boolean sortOnUpload,
                                 Runnable setupState, Runnable clearState) {
            super(name, format, mode, bufferSize, affectsCrumbling, sortOnUpload, setupState, clearState);
        }

        private static RenderType create() {
            return RenderType.m_173215_(
                    "bvp_tracer_v2",
                    DefaultVertexFormat.f_85815_,
                    VertexFormat.Mode.QUADS,
                    1536,
                    false,
                    false,
                    RenderType.CompositeState.m_110628_()
                            .m_173292_(f_173104_)
                            .m_110685_(f_110136_)
                            .m_110663_(f_110113_)
                            .m_110661_(f_110110_)
                            .m_110671_(f_110153_)
                            .m_110677_(f_110155_)
                            .m_110675_(f_110126_)
                            .m_110687_(f_110115_)
                            .m_110691_(false));
        }
    }
}
