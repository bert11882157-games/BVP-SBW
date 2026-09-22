package com.yourname.berts_vehicle_pack.client;

import com.atsuishio.superbwarfare.api.weapon.FiredVisualRecord;
import com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics;
import com.atsuishio.superbwarfare.api.weapon.FiredVisualFrameReference;
import com.atsuishio.superbwarfare.api.vehicle.pose.VehicleAttachmentSnapshot;
import com.atsuishio.superbwarfare.client.ClientRenderHandler;
import com.atsuishio.superbwarfare.client.particle.CustomCloudOption;
import com.yourname.berts_vehicle_pack.client.renderer.BvpMuzzleFlashRenderer;
import com.yourname.berts_vehicle_pack.client.renderer.BvpTracerRenderer;
import com.yourname.berts_vehicle_pack.effects.BvpFiredVisuals.VisualKind;
import com.yourname.berts_vehicle_pack.entity.ArmoredVehicleEntity;
import com.yourname.berts_vehicle_pack.entity.Mi24VEntity;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/** Client-only BVP presentation for one authoritative SBW fired-visual record. */
public final class BvpFiredVisualClient {
    private static final int MAX_TRACKED_SOURCES = 2048;
    private static final int MAX_PENDING_VISUALS = 2048;
    private static final int MAX_ATTACHMENT_NAME_CHARS = 128;
    private static final int CANNON_MUZZLE_SMOKE_COUNT = 24;
    private static final double CANNON_MUZZLE_RADIUS = 0.08D;
    private static final double CANNON_MUZZLE_CONE_SPREAD = 1.0D / 6.0D;
    private static final double CANNON_MUZZLE_SMOKE_MIN_PACKET_SPEED = 8.0D;
    private static final double CANNON_MUZZLE_SMOKE_MAX_PACKET_SPEED = 12.0D;
    private static final double CANNON_MUZZLE_SMOKE_UPWARD_BIAS = 0.25D;
    private static final double MIN_MUZZLE_DIRECTION_SQR = 1.0E-6D;
    private static final double BARREL_TIP_PROXIMITY_BLOCKS = 0.5D;
    private static final double BARREL_TIP_PROXIMITY_SQR =
            BARREL_TIP_PROXIMITY_BLOCKS * BARREL_TIP_PROXIMITY_BLOCKS;
    private static final Vec3 WORLD_UP = new Vec3(0.0D, 1.0D, 0.0D);
    private static final Vec3 WORLD_X = new Vec3(1.0D, 0.0D, 0.0D);
    private static final Vec3 CANNON_MUZZLE_SMOKE_UPWARD =
            new Vec3(0.0D, CANNON_MUZZLE_SMOKE_UPWARD_BIAS, 0.0D);
    private static final CustomCloudOption CANNON_MUZZLE_SMOKE =
            new CustomCloudOption(0.58F, 0.60F, 0.58F, 24, 0.75F, -0.15F, true, false);
    private static final Map<UUID, Long> AUTOCANNON_SEQUENCES = boundedSequenceMap();
    private static final Map<UUID, Long> PASSENGER_HMG_SEQUENCES = boundedSequenceMap();
    private static final Deque<PendingVisual> PENDING_VISUALS = new ArrayDeque<>();
    private static ClientLevel activeLevel;

    private BvpFiredVisualClient() {
    }

    public static void handle(FiredVisualRecord record, VisualKind kind) {
        Minecraft minecraft = Minecraft.m_91087_();
        ClientLevel level = minecraft == null ? null : minecraft.f_91073_;
        syncLevel(level);
        if (level == null) {
            return;
        }

        UUID sourceUuid = record.getSourceEntityUuid();
        LaunchPresentation presentation = new LaunchPresentation(record);
        if (kind == VisualKind.TANK_CANNON) {
            BvpTracerRenderer.acceptLaunch(record);
        } else {
            BvpTracerRenderer.acceptUnresolvedLaunch(record);
            for (UUID projectileId : record.getSpawnedProjectileIds()) {
                if (projectileId != null) {
                    ClientRenderHandler.registerBulletRenderOrigin(
                            projectileId, record.getMuzzlePosition(),
                            partialTick -> presentation.resolve(level, partialTick).position);
                }
            }
        }
        switch (kind) {
            case AUTOCANNON -> remember(AUTOCANNON_SEQUENCES, sourceUuid, record.getSequence());
            case PASSENGER_HMG -> remember(PASSENGER_HMG_SEQUENCES, sourceUuid, record.getSequence());
            default -> {
            }
        }

        if (PENDING_VISUALS.size() >= MAX_PENDING_VISUALS) {
            PendingVisual overflow = PENDING_VISUALS.removeFirst();
            EffectFrame overflowEffect = overflow.presentation.resolve(level, 1.0F);
            resolveTracerLaunch(overflow, overflowEffect, 1.0F);
            dispatch(minecraft, level, overflow, overflowEffect, 1.0F);
        }
        PENDING_VISUALS.addLast(new PendingVisual(record, kind, presentation));
    }

    /** Resolves each accepted effect against exactly one current render-time attachment snapshot. */
    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }
        Minecraft minecraft = Minecraft.m_91087_();
        ClientLevel level = minecraft == null ? null : minecraft.f_91073_;
        syncLevel(level);
        if (level == null) {
            return;
        }

        float partialTick = event.getPartialTick();
        while (!PENDING_VISUALS.isEmpty()) {
            PendingVisual pending = PENDING_VISUALS.removeFirst();
            EffectFrame effect = pending.presentation.resolve(level, partialTick);
            resolveTracerLaunch(pending, effect, partialTick);
            dispatch(minecraft, level, pending, effect, partialTick);
        }
    }

    public static long autocannonSequence(Mi24VEntity entity) {
        return entity == null ? 0L : AUTOCANNON_SEQUENCES.getOrDefault(entity.m_20148_(), 0L);
    }

    public static long passengerHmgSequence(ArmoredVehicleEntity entity) {
        return entity == null ? 0L : PASSENGER_HMG_SEQUENCES.getOrDefault(entity.m_20148_(), 0L);
    }

    /**
     * Suppresses only the local controller's forward-moving spray when the final first-person
     * camera is using an authored view point colocated with the selected weapon's effect muzzle.
     */
    private static boolean shouldSuppressLocalForwardSpray(
            Minecraft minecraft, FiredVisualRecord record, Vec3 effectPosition, float partialTick) {
        Player player = minecraft == null ? null : minecraft.f_91074_;
        if (player == null || minecraft.f_91066_.m_92176_() != CameraType.FIRST_PERSON
                || record.getShooterEntityId() != player.m_19879_()
                || !(player.m_20202_() instanceof ArmoredVehicleEntity vehicle)
                || record.getSourceEntityId() != vehicle.m_19879_()) {
            return false;
        }
        UUID shooterUuid = record.getShooterEntityUuid();
        UUID sourceUuid = record.getSourceEntityUuid();
        if ((shooterUuid != null && !shooterUuid.equals(player.m_20148_()))
                || (sourceUuid != null && !sourceUuid.equals(vehicle.m_20148_()))) {
            return false;
        }

        int seatIndex = vehicle.getSeatIndex(player);
        if (seatIndex < 0 || vehicle.getGunData(player) == null
                || !matchesSelectedWeapon(record, vehicle, seatIndex)) {
            return false;
        }

        Camera camera = minecraft.f_91063_.m_109153_();
        if (camera.m_90592_() != player) {
            return false;
        }
        Vec3 cameraPosition = camera.m_90583_();
        Vec3 viewPosition = vehicle.getViewPos(player, partialTick);
        return finite(cameraPosition) && finite(viewPosition) && finite(effectPosition)
                && cameraPosition.m_82557_(viewPosition) <= BARREL_TIP_PROXIMITY_SQR
                && viewPosition.m_82557_(effectPosition) <= BARREL_TIP_PROXIMITY_SQR;
    }

    private static boolean matchesSelectedWeapon(
            FiredVisualRecord record, ArmoredVehicleEntity vehicle, int seatIndex) {
        return matchesWeapon(record, vehicle, vehicle.getGunName(seatIndex));
    }

    private static boolean matchesWeapon(
            FiredVisualRecord record, ArmoredVehicleEntity vehicle, String weaponName) {
        ResourceLocation recordWeaponId = record.getWeaponId();
        ResourceLocation vehicleTypeId = ForgeRegistries.ENTITY_TYPES.getKey(vehicle.m_6095_());
        String weaponPath = generatedWeaponPath(weaponName);
        return recordWeaponId != null && vehicleTypeId != null && weaponPath != null
                && recordWeaponId.m_135827_().equals(vehicleTypeId.m_135827_())
                && recordWeaponId.m_135815_().equals(vehicleTypeId.m_135815_() + "/" + weaponPath);
    }

    private static EffectFrame resolveEffectFrame(
            ClientLevel level, FiredVisualRecord record, float partialTick) {
        EffectFrame fallback = EffectFrame.fallback(record);
        FiredVisualFrameReference reference = record.getFrameReference();
        UUID sourceUuid = record.getSourceEntityUuid();
        if (reference == null || sourceUuid == null
                || !(level.m_6815_(record.getSourceEntityId()) instanceof ArmoredVehicleEntity vehicle)
                || !sourceUuid.equals(vehicle.m_20148_())
                || !matchesWeapon(record, vehicle, reference.getWeaponName())) {
            return fallback;
        }

        String positionAttachment = reference.getEffectPositionAttachment();
        String directionAttachment = reference.getEffectDirectionAttachment();
        if (!validAttachmentName(positionAttachment) || !validAttachmentName(directionAttachment)) {
            return fallback;
        }

        VehicleAttachmentSnapshot snapshot = vehicle.getVehicleAttachmentSnapshot(partialTick);
        Vec3 position = snapshot.point(positionAttachment, Vec3.f_82478_);
        Vec3 direction = snapshot.direction(directionAttachment, new Vec3(0.0D, 0.0D, 1.0D));
        if (!finite(position) || !finite(direction)
                || direction.m_82556_() <= MIN_MUZZLE_DIRECTION_SQR) {
            return fallback;
        }
        return new EffectFrame(position, direction.m_82541_());
    }

    private static void dispatch(
            Minecraft minecraft, ClientLevel level, PendingVisual pending,
            EffectFrame effect, float partialTick) {
        FiredVisualRecord record = pending.record;
        Vec3 position = effect.position;
        Vec3 direction = effect.direction;
        long visualSeed = visualSeed(record);
        boolean suppressForwardSpray = shouldSuppressLocalForwardSpray(
                minecraft, record, position, partialTick);
        if (EliteDiagnostics.isClientEnabled()) {
            EliteDiagnostics.recordClient(level.m_46467_(), "effects", "firing_dispatch",
                    "source_entity", record.getSourceEntityUuid(), "weapon", record.getWeaponId(),
                    "profile", record.getProjectileProfileId(), "sequence", record.getSequence(),
                    "projectiles", record.getSpawnedProjectileIds(), "visual", pending.kind,
                    "position", position, "direction", direction,
                    "suppress_forward_spray", suppressForwardSpray);
        }
        switch (pending.kind) {
            case TANK_CANNON -> {
                BvpMuzzleFlashRenderer.enqueueTankCannonBurst(
                        position, direction, visualSeed, suppressForwardSpray);
                if (!suppressForwardSpray) {
                    spawnTankCannonSmoke(level, position, direction, visualSeed);
                }
            }
            case AUTOCANNON -> BvpMuzzleFlashRenderer.enqueueAutocannonBurst(
                    position, direction, visualSeed, suppressForwardSpray);
            case PASSENGER_HMG -> BvpMuzzleFlashRenderer.enqueuePassengerHmgBurst(
                    position, direction, visualSeed, suppressForwardSpray);
            case COAX -> BvpMuzzleFlashRenderer.enqueueCoaxBurst(
                    position, direction, visualSeed, suppressForwardSpray);
            default -> {
            }
        }
    }

    private static void resolveTracerLaunch(PendingVisual pending, EffectFrame effect,
                                            float partialTick) {
        if (pending.kind != VisualKind.TANK_CANNON) {
            BvpTracerRenderer.resolveLaunch(pending.record, effect.position, partialTick);
        }
    }

    private static boolean validAttachmentName(String value) {
        return value != null && !value.isBlank() && value.length() <= MAX_ATTACHMENT_NAME_CHARS;
    }

    /** Mirrors tools/bvp/projectile_profiles.mjs weaponPath so identity checks fail closed. */
    private static String generatedWeaponPath(String weaponName) {
        if (weaponName == null || weaponName.isBlank()) {
            return null;
        }
        boolean template = weaponName.charAt(0) == '@';
        String raw = template ? weaponName.substring(1) : weaponName;
        StringBuilder normalized = new StringBuilder(raw.length());
        boolean separatorPending = false;
        for (int i = 0; i < raw.length(); i++) {
            char value = Character.toLowerCase(raw.charAt(i));
            boolean alphaNumeric = value >= 'a' && value <= 'z' || value >= '0' && value <= '9';
            if (alphaNumeric) {
                if (separatorPending && !normalized.isEmpty()) {
                    normalized.append('_');
                }
                normalized.append(value);
                separatorPending = false;
            } else if (!normalized.isEmpty()) {
                separatorPending = true;
            }
        }
        if (normalized.isEmpty()) {
            return null;
        }
        return template ? "template_" + normalized : normalized.toString();
    }

    private static void spawnTankCannonSmoke(ClientLevel level, Vec3 position, Vec3 direction, long seed) {
        if (direction == null || direction.m_82556_() <= MIN_MUZZLE_DIRECTION_SQR) {
            return;
        }
        Vec3 forward = direction.m_82541_();
        Vec3 reference = Math.abs(forward.f_82480_) < 0.95D ? WORLD_UP : WORLD_X;
        Vec3 right = reference.m_82537_(forward).m_82541_();
        Vec3 up = forward.m_82537_(right).m_82541_();
        Random random = new Random(seed);

        for (int i = 0; i < CANNON_MUZZLE_SMOKE_COUNT; i++) {
            Vec3 spawnPosition = randomRadialVector(position, right, up, CANNON_MUZZLE_RADIUS, random);
            Vec3 velocity = randomRadialVector(forward, right, up, CANNON_MUZZLE_CONE_SPREAD, random)
                    .m_82549_(CANNON_MUZZLE_SMOKE_UPWARD)
                    .m_82541_();
            double speed = randomBetween(random,
                    CANNON_MUZZLE_SMOKE_MIN_PACKET_SPEED, CANNON_MUZZLE_SMOKE_MAX_PACKET_SPEED);
            level.m_6493_(CANNON_MUZZLE_SMOKE, true,
                    spawnPosition.f_82479_, spawnPosition.f_82480_, spawnPosition.f_82481_,
                    velocity.f_82479_ * speed, velocity.f_82480_ * speed, velocity.f_82481_ * speed);
        }
    }

    private static long visualSeed(FiredVisualRecord record) {
        return record.getSequence()
                ^ record.getServerSessionId().getMostSignificantBits()
                ^ record.getServerSessionId().getLeastSignificantBits();
    }

    private static Vec3 randomRadialVector(Vec3 center, Vec3 right, Vec3 up, double maxRadius, Random random) {
        double angle = random.nextDouble() * Math.PI * 2.0D;
        double radius = Math.sqrt(random.nextDouble()) * maxRadius;
        double rightScale = Math.cos(angle) * radius;
        double upScale = Math.sin(angle) * radius;
        return new Vec3(
                center.f_82479_ + right.f_82479_ * rightScale + up.f_82479_ * upScale,
                center.f_82480_ + right.f_82480_ * rightScale + up.f_82480_ * upScale,
                center.f_82481_ + right.f_82481_ * rightScale + up.f_82481_ * upScale);
    }

    private static double randomBetween(Random random, double min, double max) {
        return min + random.nextDouble() * (max - min);
    }

    private static boolean finite(Vec3 value) {
        return value != null
                && Double.isFinite(value.f_82479_)
                && Double.isFinite(value.f_82480_)
                && Double.isFinite(value.f_82481_);
    }

    private static void remember(Map<UUID, Long> sequences, UUID sourceUuid, long sequence) {
        if (sourceUuid != null) {
            sequences.put(sourceUuid, sequence);
        }
    }

    private static void syncLevel(ClientLevel level) {
        if (activeLevel == level) {
            return;
        }
        PENDING_VISUALS.clear();
        activeLevel = level;
    }

    private static Map<UUID, Long> boundedSequenceMap() {
        return new LinkedHashMap<>(64, 0.75F, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<UUID, Long> eldest) {
                return size() > MAX_TRACKED_SOURCES;
            }
        };
    }

    private record PendingVisual(
            FiredVisualRecord record, VisualKind kind, LaunchPresentation presentation) {
    }

    /** Freezes one observer-local attachment frame for every visual belonging to one shot. */
    private static final class LaunchPresentation {
        private final FiredVisualRecord record;
        private EffectFrame resolved;

        private LaunchPresentation(FiredVisualRecord record) {
            this.record = record;
        }

        private EffectFrame resolve(ClientLevel level, float partialTick) {
            if (resolved == null) {
                resolved = resolveEffectFrame(level, record, partialTick);
            }
            return resolved;
        }
    }

    private record EffectFrame(Vec3 position, Vec3 direction) {
        static EffectFrame fallback(FiredVisualRecord record) {
            return new EffectFrame(record.getEffectPosition(), record.getEffectDirection());
        }
    }
}
