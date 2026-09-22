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
    private static final double MAX_RENDER_DISTANCE_SQR = 160.0D * 160.0D;
    private static final int MAX_TRACKED_TRACERS = 1024;
    private static final int MAX_TRACER_CANDIDATES = 1024;
    private static final int MAX_STAGED_PROJECTILES = 2048;
    private static final int MAX_ACTIVE_SAMPLES = 1024;
    private static final int MAX_SAMPLES_PER_TICK = 256;
    private static final int MAX_PENDING_LAUNCHES = 2048;
    private static final int MAX_ACCEPTED_IDENTITIES = 2048;
    private static final int MAX_RETIRED_SESSIONS = 8;
    private static final int LAUNCH_TTL_TICKS = 20;
    private static final float VISUAL_DELAY_TICKS = 1.0F;
    private static final Vec3 WORLD_X = new Vec3(1.0D, 0.0D, 0.0D);
    private static final Vec3 WORLD_Y = new Vec3(0.0D, 1.0D, 0.0D);
    private static final Vec3 WORLD_Z = new Vec3(0.0D, 0.0D, 1.0D);
    private static final RenderType TRACER_RENDER_TYPE = TracerRenderType.create();
    private static final Map<UUID, StagedProjectile> STAGED_PROJECTILES = new LinkedHashMap<>();
    private static final Map<UUID, TracerCandidate> TRACER_CANDIDATES = new LinkedHashMap<>();
    private static final Map<UUID, LiveBeam> LIVE_BEAMS = new LinkedHashMap<>();
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
        if (record == null || !finite(position) || !finite(record.getDirection())) {
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
                anchorBeam(projectileId, liveBeam, shotIdentity, position, record.getDirection(),
                        tick + partialTick);
                continue;
            }
            putPendingLaunch(projectileId,
                    new LaunchAnchor(shotIdentity, position, record.getDirection(), tick,
                            tick + partialTick, true));
        }
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
        int samplesAdded = 0;
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
            if (movement.m_82556_() <= MIN_DIRECTION_SQR) {
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
            // Impact fragments are server-created presentation projectiles rather than accepted
            // fire records, so they never receive a launch anchor. Admit them through the same
            // one-tick visual delay while retaining their live endpoint/chord rendering path.
            if (!beam.launchAccepted && isImpactShrapnel(entity)) {
                beam.acceptImpact(tick);
            }
            if (beam.launchAccepted && beam.launchPosition == null
                    && samplesAdded < MAX_SAMPLES_PER_TICK) {
                addSample(new BeamSample(entityUuid, end, direction, profile, tick));
                samplesAdded++;
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
        float renderTick = level.m_46467_() + partialTick;
        Camera camera = event.getCamera();
        Vec3 cameraPosition = camera.m_90583_();
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
                if (sample.spawnTick == currentTick && LIVE_BEAMS.containsKey(sample.entityUuid)) {
                    continue;
                }
                float remainingLife =
                        1.0F - (renderTick - sample.spawnTick) / sample.profile.lifetimeTicks();
                if (remainingLife <= 0.0F) {
                    continue;
                }
                if (!finite(sample.end) || sample.end.m_82557_(cameraPosition) > MAX_RENDER_DISTANCE_SQR) {
                    continue;
                }
                drawBeam(consumer, matrix, cameraPosition, sample.end, sample.direction,
                        sample.profile, remainingLife);
                beamsDrawn++;
            }
            for (LiveBeam beam : LIVE_BEAMS.values()) {
                liveVisited++;
                Vec3 end = beam.entity.m_20318_(partialTick);
                if (!finite(end) || end.m_82557_(cameraPosition) > MAX_RENDER_DISTANCE_SQR) {
                    continue;
                }
                Vec3 direction = sampledTrajectoryDirection(beam.entity, beam.launchDirection);
                if (direction == null) {
                    continue;
                }
                if (!beam.launchAccepted) {
                    continue;
                }
                if (!beam.visualReady(renderTick)) {
                    continue;
                }
                beam.releaseDelayedLaunch();
                drawBeam(consumer, matrix, cameraPosition, end, direction, beam.profile, 1.0F);
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
        if (!finite(direction) || direction.m_82556_() <= MIN_DIRECTION_SQR) {
            return;
        }
        Vec3 forward = direction.m_82541_();
        Vec3 start = end.m_82549_(forward.m_82490_(-profile.lengthBlocks()));
        drawSegment(consumer, matrix, cameraPosition, start, end, forward, profile, remainingLife);
    }

    private static void drawSegment(VertexConsumer consumer, Matrix4f matrix, Vec3 cameraPosition,
                                    Vec3 start, Vec3 end, Vec3 forward,
                                    BvpTracerProfile profile, float remainingLife) {
        Vec3 midpoint = start.m_82549_(end).m_82490_(0.5D);
        Vec3 side = forward.m_82537_(cameraPosition.m_82546_(midpoint));
        if (side.m_82556_() <= MIN_DIRECTION_SQR) {
            side = forward.m_82537_(leastParallelAxis(forward));
        }
        if (side.m_82556_() <= MIN_DIRECTION_SQR) {
            return;
        }
        side = side.m_82541_();
        Vec3 up = forward.m_82537_(side).m_82541_();
        Vec3 diagonalA = side.m_82549_(up).m_82541_();
        Vec3 diagonalB = side.m_82546_(up).m_82541_();
        float baseAlpha = profile.opacity() * remainingLife;
        float glowAlpha = baseAlpha * profile.glowOpacityScale();
        float coreAlpha = baseAlpha * profile.coreOpacityScale();
        double glowHalfWidth = profile.widthBlocks() * profile.glowWidthScale() * 0.5D;
        double coreHalfWidth = profile.widthBlocks() * profile.coreWidthScale() * 0.5D;

        quad(consumer, matrix, start, end, side, glowHalfWidth, profile, glowAlpha);
        quad(consumer, matrix, start, end, up, glowHalfWidth, profile, glowAlpha);
        quad(consumer, matrix, start, end, diagonalA, glowHalfWidth, profile, glowAlpha);
        quad(consumer, matrix, start, end, diagonalB, glowHalfWidth, profile, glowAlpha);
        quad(consumer, matrix, start, end, side, coreHalfWidth, profile, coreAlpha);
        quad(consumer, matrix, start, end, up, coreHalfWidth, profile, coreAlpha);
    }

    private static void quad(VertexConsumer consumer, Matrix4f matrix, Vec3 start, Vec3 end,
                             Vec3 normal, double halfWidth, BvpTracerProfile profile, float alpha) {
        if (!(halfWidth > 0.0D) || !(alpha > 0.0F)) {
            return;
        }
        Vec3 offset = normal.m_82490_(halfWidth);
        vertex(consumer, matrix, start.m_82546_(offset), profile, alpha);
        vertex(consumer, matrix, end.m_82546_(offset), profile, alpha);
        vertex(consumer, matrix, end.m_82549_(offset), profile, alpha);
        vertex(consumer, matrix, start.m_82549_(offset), profile, alpha);
    }

    private static void vertex(VertexConsumer consumer, Matrix4f matrix, Vec3 position,
                               BvpTracerProfile profile, float alpha) {
        consumer.m_252986_(matrix,
                        (float) position.f_82479_,
                        (float) position.f_82480_,
                        (float) position.f_82481_)
                .m_85950_(profile.red(), profile.green(), profile.blue(), alpha)
                .m_5752_();
    }

    private static Vec3 leastParallelAxis(Vec3 direction) {
        double x = Math.abs(direction.f_82479_);
        double y = Math.abs(direction.f_82480_);
        double z = Math.abs(direction.f_82481_);
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
                                   float presentationStartTick) {
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
        PENDING_LAUNCHES.clear();
        ACCEPTED_IDENTITIES.clear();
        SAMPLES.clear();
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
                ACCEPTED_IDENTITIES.remove(projectileId);
                ACCEPTED_IDENTITIES.put(projectileId, new IdentityStamp(shotIdentity, receivedTick));
                return true;
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
        return finite(acceptedFallback) && acceptedFallback.m_82556_() > MIN_DIRECTION_SQR
                ? acceptedFallback.m_82541_() : null;
    }

    private static void trimExpiredSamples(long tick) {
        SAMPLES.removeIf(sample -> {
            long age = tick - sample.spawnTick;
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
        PENDING_LAUNCHES.clear();
        ACCEPTED_IDENTITIES.clear();
        RETIRED_SESSIONS.clear();
        SAMPLES.clear();
        activeServerSession = null;
        activeSessionLatestSequence = 0L;
        activeSessionHasSequence = false;
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
        Vec3 launchPosition;
        Vec3 launchDirection;
        ShotIdentity shotIdentity;
        boolean launchAccepted;
        float presentationStartTick;

        LiveBeam(Entity entity, BvpTracerProfile profile, long lastSeenTick) {
            update(entity, profile, lastSeenTick);
        }

        void update(Entity entity, BvpTracerProfile profile, long lastSeenTick) {
            this.entity = entity;
            this.profile = profile;
            this.lastSeenTick = lastSeenTick;
        }

        void anchor(ShotIdentity shotIdentity, Vec3 position, Vec3 direction,
                    float presentationStartTick) {
            this.launchAccepted = true;
            this.shotIdentity = shotIdentity;
            this.launchPosition = position;
            this.launchDirection = direction.m_82541_();
            this.presentationStartTick = presentationStartTick;
        }

        void acceptImpact(long tick) {
            this.launchAccepted = true;
            this.shotIdentity = null;
            this.launchPosition = null;
            this.launchDirection = null;
            this.presentationStartTick = tick;
        }

        boolean visualReady(float renderTick) {
            return renderTick - presentationStartTick >= VISUAL_DELAY_TICKS;
        }

        void releaseDelayedLaunch() {
            launchPosition = null;
        }
    }

    private record ShotIdentity(UUID serverSessionId, long sequence) {
    }

    private record IdentityStamp(ShotIdentity shotIdentity, long receivedTick) {
    }

    private record LaunchAnchor(ShotIdentity shotIdentity, Vec3 position, Vec3 direction, long receivedTick,
                                float presentationStartTick, boolean resolved) {
    }

    private record BeamSample(
            UUID entityUuid,
            Vec3 end,
            Vec3 direction,
            BvpTracerProfile profile,
            long spawnTick) {
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
