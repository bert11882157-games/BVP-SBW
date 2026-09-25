package com.yourname.berts_vehicle_pack.entity;

import com.atsuishio.superbwarfare.api.weapon.ReloadTransitionPolicy;
import com.atsuishio.superbwarfare.api.projectile.ProjectileCollisionTarget;
import com.atsuishio.superbwarfare.api.vehicle.render.FarVehicleCopies;
import com.atsuishio.superbwarfare.api.vehicle.presentation.VehicleModuleHudProvider;
import com.atsuishio.superbwarfare.api.vehicle.presentation.VehicleModuleHudLayoutProvider;
import com.atsuishio.superbwarfare.api.vehicle.presentation.VehicleModuleHudMarker;
import com.yourname.berts_vehicle_pack.armor.ArmorModuleHudLayout;
import com.atsuishio.superbwarfare.api.vehicle.presentation.VehicleModuleHudState;
import com.atsuishio.superbwarfare.api.vehicle.presentation.VehicleModuleHudHealth;
import com.atsuishio.superbwarfare.api.vehicle.render.FarVehicleVisualExtension;
import com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics;
import com.atsuishio.superbwarfare.api.vehicle.aim.VehicleAimChannel;
import com.atsuishio.superbwarfare.api.vehicle.aim.VehicleAimMode;
import com.atsuishio.superbwarfare.api.vehicle.aim.VehicleAimProfile;
import com.atsuishio.superbwarfare.api.vehicle.aim.VehicleAimProfileProvider;
import com.atsuishio.superbwarfare.api.vehicle.aim.VehicleAimReticleProfile;
import com.atsuishio.superbwarfare.api.vehicle.aim.VehicleAimReticleProfileProvider;
import com.atsuishio.superbwarfare.api.vehicle.aim.VehicleAimReticleRole;
import com.atsuishio.superbwarfare.api.vehicle.aim.VehicleAimSnapshot;
import com.atsuishio.superbwarfare.api.vehicle.camera.VehicleCameraMode;
import com.atsuishio.superbwarfare.api.vehicle.camera.VehicleSeatPoseProvider;
import com.atsuishio.superbwarfare.api.vehicle.camera.VehicleSeatPoseSnapshot;
import com.atsuishio.superbwarfare.data.vehicle.subdata.CameraPos;
import com.atsuishio.superbwarfare.data.vehicle.subdata.SeatInfo;
import com.atsuishio.superbwarfare.entity.vehicle.base.GeoVehicleEntity;
import com.yourname.berts_vehicle_pack.armor.ArmorProfiles;
import com.yourname.berts_vehicle_pack.armor.BvpProjectileCollision;
import com.yourname.berts_vehicle_pack.armor.EraBrickIds;
import com.yourname.berts_vehicle_pack.armor.VehicleModuleHealth;
import com.yourname.berts_vehicle_pack.entity.armored.damage.BvpFieldRepairAction;
import com.yourname.berts_vehicle_pack.entity.armored.damage.VehicleAmmoRackSystem;
import com.yourname.berts_vehicle_pack.entity.armored.damage.VehicleModuleDamageSystem;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4d;
import org.joml.Vector4d;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

public abstract class ArmoredVehicleEntity extends GeoVehicleEntity implements VehicleAimProfileProvider,
        VehicleAimReticleProfileProvider, VehicleSeatPoseProvider, FarVehicleVisualExtension, ProjectileCollisionTarget, VehicleModuleHudProvider, VehicleModuleHudLayoutProvider {
    private static final double BVP_FIRST_PERSON_SENSITIVITY_MULTIPLIER = 0.85D;
    private static final VehicleAimReticleProfile BVP_AIM_RETICLE = new VehicleAimReticleProfile(
            0xF0FF4040, 0xF058E676, 5.0D, 14, 12, 5);
    private static final EntityDataAccessor<String> LAST_ARMOR_HIT_PLATE =
            SynchedEntityData.m_135353_(ArmoredVehicleEntity.class, EntityDataSerializers.f_135030_);
    private static final EntityDataAccessor<Integer> LAST_ARMOR_HIT_TICK =
            SynchedEntityData.m_135353_(ArmoredVehicleEntity.class, EntityDataSerializers.f_135028_);
    private static final EntityDataAccessor<String> SPENT_ERA_BRICKS =
            SynchedEntityData.m_135353_(ArmoredVehicleEntity.class, EntityDataSerializers.f_135030_);
    private static final String TAG_SPENT_ERA_BRICKS = "BvpSpentEraBricks";
    private static final int ERA_REGEN_DURATION_TICKS = 20 * 60;

    private final String armorProfileId;
    private final VehicleModuleDamageSystem bvpModuleDamage = new VehicleModuleDamageSystem(this);
    private final VehicleAmmoRackSystem bvpAmmoRack = new VehicleAmmoRackSystem(this);
    private final Map<String, Integer> bvpSpentEraExpiryTicks = new HashMap<>();
    private int bvpNextEraExpiryTick = Integer.MAX_VALUE;

    protected ArmoredVehicleEntity(EntityType<?> type, Level world, String armorProfileId) {
        super(type, world);
        this.armorProfileId = armorProfileId;
        // Armor meshes are parsed once per profile; start that off the game and render threads so
        // hasArmorHitboxes()/usesBvpArmorResolution() never parse a mesh on first use.
        ArmorProfiles.prefetch(armorProfileId);
    }

    public boolean usesBvpArmorResolution() {
        // Fragile platforms without authored armor take native projectile damage. Once the
        // user authors their plates, normal BVP penetration applies below the lethal caliber.
        return computed().getLethalDirectCaliberMm() == null
                || !com.yourname.berts_vehicle_pack.armor.ArmorProfiles.get(armorProfileId).plates.isEmpty();
    }

    @Override
    public boolean allowsTurretEjection() {
        return !com.yourname.berts_vehicle_pack.effects.BvpAbramsCookoff.applies(this);
    }

    /** Authored armor plates resolve direct hits; small TNT-equivalent charges leave this hull to them. */
    @Override
    public boolean hasArmorHitboxes() {
        return !ArmorProfiles.get(armorProfileId).plates.isEmpty();
    }

    @Override
    public boolean usesDetailedProjectileCollision() {
        return super.usesDetailedProjectileCollision() || BvpProjectileCollision.supports(armorProfileId);
    }

    @Override
    public ProjectileCollisionTarget.Hit clipProjectile(Vec3 start, Vec3 end) {
        return super.usesDetailedProjectileCollision() ? super.clipProjectile(start, end)
                : BvpProjectileCollision.clip(this, start, end);
    }

    @Override
    public double getTargetSpeed() {
        double targetSpeed = super.getTargetSpeed();
        if (this.bvpModuleDamage == null || !usesBvpGroundMobilityLimits()) {
            return targetSpeed;
        }
        return targetSpeed * this.bvpModuleDamage.engineMobilityMultiplier();
    }

    @Override
    public ReloadTransitionPolicy vehicleReloadTransitionPolicy() {
        return ReloadTransitionPolicy.VEHICLE_DEFAULT;
    }

    @Override
    protected int initialPassengerReloadProgressPercent() {
        return ReloadTransitionPolicy.VEHICLE_PARTIAL_RELOAD_PROGRESS_PERCENT;
    }

    @Override
    protected void afterVehicleTick() {
        super.afterVehicleTick();
        if (!this.m_9236_().f_46443_) {
            if (usesBvpGroundMobilityLimits()) {
                bvpModuleDamage.applyMobilityLimit();
            }
            if (usesBvpAmmoRackWarnings()) {
                bvpAmmoRack.tickWarning();
            }
            tickBvpEraRegeneration();
        }
        if (EliteDiagnostics.isEnabled(this.m_9236_()) && (this.f_19797_ + this.m_19879_()) % 20 == 0) {
            EliteDiagnostics.record(this, "armor", "module_snapshot", "profile", armorProfileId,
                    "engine_hp", getModuleHealth("engine"), "left_track_hp", getModuleHealth("lefttrack"),
                    "right_track_hp", getModuleHealth("righttrack"),
                    "weapons_hp", getBvpWeaponsSystemsHealth(), "spent_era", getBvpSpentEraBricks(),
                    "last_hit_plate", getLastArmorHitPlate(), "last_hit_age", getLastArmorHitAge());
        }
    }

    /**
     * Allows 360-degree first-person look for eligible seats without requiring a synchronized
     * aim snapshot or a successfully resolved pose.
     */
    @Override
    public void m_7340_(Entity passenger) {
        if (passenger instanceof Player player && usesBvpUnclampedFirstPerson(player)) {
            if (usesBvpPassengerTurnBodyRefresh()) {
                this.m_7332_(passenger);
            }
            return;
        }
        super.m_7340_(passenger);
    }

    @Override
    public Vec2 getCameraRotation(float partialTicks, Player player, boolean zooming, boolean firstPerson) {
        if (firstPerson && player != null && usesBvpUnclampedFirstPerson(player)) {
            return new Vec2(player.m_146908_(), player.m_146909_());
        }
        return super.getCameraRotation(partialTicks, player, zooming, firstPerson);
    }

    private boolean usesBvpUnclampedFirstPerson(Player player) {
        SeatInfo seat = getSeat(player);
        CameraPos camera = seat == null ? null : seat.getCameraPos();
        // Aircraft seats own their rotation through AIRCRAFT_FREELOOK. Treating their authored
        // fixed eye anchor as a tank-style clamp bypass leaves the camera world-locked while the
        // synchronized flight attitude rotates the model.
        if (camera != null && camera.getUseAircraftCamera()) {
            return false;
        }
        if (isBvpTurretControlledBy(player) || isBvpPassengerWeaponControlledBy(player)) {
            return true;
        }
        return camera != null && (camera.getUseFixedCameraPos()
                || (camera.getEyeAttachment() != null && !camera.getEyeAttachment().isBlank()));
    }

    @Override
    public Entity getAmmoSupplier() {
        return this;
    }

    @Override
    protected boolean areVehicleWeaponModulesOperational() {
        return !isBvpWeaponsModuleDestroyed();
    }

    protected boolean usesNativeTurretAimProfile() {
        return true;
    }

    protected boolean usesNativePassengerWeaponAimProfile() {
        return true;
    }

    protected boolean usesBvpTrackMobilitySystems() {
        return true;
    }

    /** Ground drive damage may stop translation; flight owners must retain glide momentum. */
    public boolean usesBvpGroundMobilityLimits() {
        return true;
    }

    protected boolean usesBvpAmmoRackWarnings() {
        return true;
    }

    protected float bvpTurretAimYawRateDegreesPerSecond() {
        return Math.abs(this.getTurretTurnYSpeed());
    }

    protected float bvpTurretAimPitchRateDegreesPerSecond() {
        return Math.abs(this.getTurretTurnXSpeed());
    }

    protected float bvpTurretAimToleranceDegrees() {
        return 0.35F;
    }

    protected float bvpTurretSoftYawLimitDegrees() {
        return 0.0F;
    }

    protected float bvpTurretSoftPitchLimitDegrees() {
        return 0.0F;
    }

    protected float bvpPassengerWeaponAimToleranceDegrees() {
        return 0.35F;
    }

    /** Compatibility opt-in for attachment-directed zoom when ZoomDirection is absent. */
    protected boolean usesBvpAttachmentZoomDirection() {
        return false;
    }

    /** Compatibility opt-in to refresh the controlling rider's body on every unclamped turn. */
    protected boolean usesBvpPassengerTurnBodyRefresh() {
        return false;
    }

    @Override
    public VehicleAimProfile createVehicleAimProfile(int seatIndex, int selectedWeaponIndex) {
        if (usesNativeTurretAimProfile()
                && hasTurret()
                && getBarrelPosition() != null
                && seatIndex == getTurretControllerIndex()) {
            return VehicleAimProfile.builder(VehicleAimChannel.TURRET)
                    .rates(Math.abs(getTurretTurnYSpeed()), Math.abs(getTurretTurnXSpeed()))
                    .yawRange(-getTurretMaxYaw(), -getTurretMinYaw())
                    .pitchRange(-getTurretMaxPitch(), -getTurretMinPitch())
                    .softLimits(bvpTurretSoftYawLimitDegrees(), bvpTurretSoftPitchLimitDegrees())
                    .lockTolerance(bvpTurretAimToleranceDegrees())
                    .defaultMode(VehicleAimMode.PLAYER_LOOK_AIM)
                    .build();
        }
        if (usesNativePassengerWeaponAimProfile()
                && hasPassengerWeaponStation()
                && seatIndex == getPassengerWeaponStationControllerIndex()) {
            return VehicleAimProfile.builder(VehicleAimChannel.PASSENGER_WEAPON)
                    .rates(Math.abs(getPassengerWeaponYSpeed()), Math.abs(getPassengerWeaponXSpeed()))
                    .yawRange(-getPassengerWeaponMaxYaw(), -getPassengerWeaponMinYaw())
                    .pitchRange(-getPassengerWeaponMaxPitch(), -getPassengerWeaponMinPitch())
                    .lockTolerance(bvpPassengerWeaponAimToleranceDegrees())
                    .defaultMode(VehicleAimMode.PLAYER_LOOK_AIM)
                    .build();
        }
        return null;
    }

    @Override
    public float resolveVehicleAimYawRateDegreesPerSecond(VehicleAimChannel channel, float configuredRate) {
        if (channel == VehicleAimChannel.TURRET && isBvpWeaponsModuleDestroyed()) {
            return 0.0F;
        }
        return super.resolveVehicleAimYawRateDegreesPerSecond(channel, configuredRate);
    }

    @Override
    public float resolveVehicleAimPitchRateDegreesPerSecond(VehicleAimChannel channel, float configuredRate) {
        if (channel == VehicleAimChannel.TURRET && isBvpWeaponsModuleDestroyed()) {
            return 0.0F;
        }
        return super.resolveVehicleAimPitchRateDegreesPerSecond(channel, configuredRate);
    }

    @Override
    public VehicleAimReticleProfile getVehicleAimReticleProfile(int seatIndex, int selectedWeaponIndex) {
        if (seatIndex < 0 || selectedWeaponIndex < 0) {
            return null;
        }
        if (this instanceof Ka50Entity) {
            return seatIndex == 0 && selectedWeaponIndex == 1 ? BVP_AIM_RETICLE : null;
        }
        if (this instanceof Mi24VEntity || this instanceof Mi28NEntity) {
            return seatIndex == 1 && selectedWeaponIndex == 0 ? BVP_AIM_RETICLE : null;
        }
        return BVP_AIM_RETICLE;
    }

    @Override
    public VehicleAimReticleRole getVehicleAimReticleRole(int seatIndex, int selectedWeaponIndex) {
        SeatInfo opticSeat = getSeat(seatIndex);
        if (opticSeat != null && opticSeat.getCameraPos() != null
                && "operator_scope".equals(opticSeat.getCameraPos().getZoomEyeAttachment())) {
            // The fitted emplacement scope is an explicit physical eye anchor. Tank gunner
            // optics retain their existing FOV-only policy and never shift to a gun muzzle.
            return VehicleAimReticleRole.EMPLACEMENT_OPTIC;
        }
        if (hasPassengerWeaponStation() && seatIndex == getPassengerWeaponStationControllerIndex()) {
            return VehicleAimReticleRole.PASSENGER_HMG;
        }
        String weaponId = getGunName(seatIndex, selectedWeaponIndex);
        if ("MachineGun".equals(weaponId) || "MainMachineGun".equals(weaponId)) {
            return VehicleAimReticleRole.COAX;
        }
        return VehicleAimReticleRole.MAIN_CANNON;
    }

    @Override
    public VehicleSeatPoseSnapshot createVehicleSeatPose(Entity passenger, int seatIndex,
                                                         int selectedWeaponIndex, float partialTicks,
                                                         boolean zooming) {
        SeatInfo seat = getSeat(passenger);
        if (seat == null) {
            return null;
        }
        CameraPos camera = seat.getCameraPos();
        if (camera != null
                && seat.getBodyAttachment() != null && !seat.getBodyAttachment().isBlank()
                && camera.getEyeAttachment() != null && !camera.getEyeAttachment().isBlank()) {
            // Native SBW owns fully authored attachment seats.
            return null;
        }
        Vec3 bodyPosition = bvpSeatBodyPosition(seat.getPosition(), seat.transform, partialTicks);
        if (bodyPosition == null) {
            return null;
        }

        Vec3 eyePosition = bvpSeatEyePosition(passenger, partialTicks, zooming);
        if (eyePosition == null) {
            eyePosition = bodyPosition.m_82520_(0.0D, passenger.m_20192_(), 0.0D);
        }
        Vec3 direction = getTransformDirection(partialTicks, passenger);

        String bodyAnchor = seat.transform == null || seat.transform.isBlank()
                ? "Vehicle"
                : seat.transform;
        String eyeAnchor = "Seat" + seatIndex + (zooming ? ":ZoomEye" : ":Eye");
        String directionAnchor = "Seat" + seatIndex + ":Direction";

        return new VehicleSeatPoseSnapshot(
                seatIndex,
                selectedWeaponIndex,
                bodyAnchor,
                eyeAnchor,
                directionAnchor,
                bodyPosition,
                eyePosition,
                direction,
                bvpSeatDefaultCameraMode(passenger, seatIndex, selectedWeaponIndex),
                bvpSeatAimCameraMode(passenger, seatIndex, selectedWeaponIndex)
        );
    }

    protected Vec3 bvpSeatEyePosition(Entity passenger, float partialTicks, boolean zooming) {
        SeatInfo seat = getSeat(passenger);
        CameraPos camera = seat == null ? null : seat.getCameraPos();
        if (camera == null || !camera.getUseFixedCameraPos()) {
            return null;
        }
        Vec3 local = zooming && camera.getZoomPosition() != null
                ? camera.getZoomPosition()
                : camera.getPosition();
        if (local == null) {
            return null;
        }
        Matrix4d transform = getTransformFromString(camera.getTransform(), partialTicks);
        Vector4d world = transformPosition(transform, local.f_82479_, local.f_82480_, local.f_82481_);
        return new Vec3(world.x, world.y, world.z);
    }

    protected VehicleCameraMode bvpSeatDefaultCameraMode(Entity passenger, int seatIndex,
                                                         int selectedWeaponIndex) {
        if (resolveVehicleAimProfile(seatIndex, selectedWeaponIndex) != null) {
            return VehicleCameraMode.PLAYER_LOOK_AIM;
        }
        SeatInfo seat = getSeat(passenger);
        CameraPos camera = seat == null ? null : seat.getCameraPos();
        return camera != null && camera.getUseAircraftCamera()
                ? VehicleCameraMode.AIRCRAFT_FREELOOK
                : VehicleCameraMode.FIXED_ATTACHMENT;
    }

    protected VehicleCameraMode bvpSeatAimCameraMode(Entity passenger, int seatIndex,
                                                     int selectedWeaponIndex) {
        return VehicleCameraMode.PLAYER_LOOK_AIM;
    }

    protected final Vec3 bvpAuthoredSeatBodyPosition(Entity passenger, float partialTicks) {
        if (passenger == null) {
            return null;
        }
        SeatInfo seat = getSeat(passenger);
        if (seat == null) {
            return null;
        }
        return bvpSeatBodyPosition(seat.getPosition(), seat.transform, partialTicks);
    }

    protected Vec3 bvpSeatBodyPosition(Vec3 position, String transformName, float partialTicks) {
        if (position == null) {
            return null;
        }
        Matrix4d transform = getTransformFromString(transformName, partialTicks);
        Vector4d world = transformPosition(
                transform,
                position.f_82479_,
                position.f_82480_,
                position.f_82481_
        );
        return new Vec3(world.x, world.y, world.z);
    }

    @Override
    public double getSensitivity(double sensitivity, boolean zooming, int seatIndex, boolean onGround) {
        int selectedWeaponIndex = getSelectedWeapon(seatIndex);
        VehicleAimProfile profile = resolveVehicleAimProfile(seatIndex, selectedWeaponIndex);
        boolean playerLookActive = profile != null
                && getVehicleAimPresentationMode(seatIndex, selectedWeaponIndex) == VehicleAimMode.PLAYER_LOOK_AIM;
        if (playerLookActive) {
            return sensitivity * BVP_FIRST_PERSON_SENSITIVITY_MULTIPLIER;
        }
        return super.getSensitivity(sensitivity, zooming, seatIndex, onGround);
    }

    @Override
    public Vec3 getZoomDirection(Entity entity, float partialTick) {
        SeatInfo seat = getSeat(entity);
        CameraPos camera = seat == null ? null : seat.getCameraPos();
        if ((camera != null && camera.getZoomDirection() != null) || usesBvpAttachmentZoomDirection()) {
            return super.getZoomDirection(entity, partialTick);
        }
        return entity.m_20252_(partialTick);
    }

    @Override
    protected void m_8097_() {
        super.m_8097_();
        this.f_19804_.m_135372_(LAST_ARMOR_HIT_PLATE, "");
        this.f_19804_.m_135372_(LAST_ARMOR_HIT_TICK, 0);
        this.f_19804_.m_135372_(SPENT_ERA_BRICKS, "");
    }

    @Override
    protected void m_7378_(CompoundTag tag) {
        super.m_7378_(tag);
        readBvpEraState(tag);
    }

    @Override
    public void m_7380_(CompoundTag tag) {
        super.m_7380_(tag);
        addBvpEraState(tag);
    }

    public final String getArmorProfileId() {
        return this.armorProfileId;
    }

    public final boolean isTurretEjected() {
        return hasTurret() && getSympatheticDetonated();
    }

    public final double getApPenetrationMm() {
        return ArmorProfiles.get(this.armorProfileId).apPenetrationMm;
    }

    public final void markArmorPlateHit(String plateName) {
        this.f_19804_.m_135381_(LAST_ARMOR_HIT_PLATE, plateName == null ? "" : plateName);
        this.f_19804_.m_135381_(LAST_ARMOR_HIT_TICK, this.f_19797_);
    }

    public final boolean isBvpEraBrickSpent(String brickName) {
        return this.bvpSpentEraExpiryTicks.containsKey(EraBrickIds.stateId(brickName));
    }

    public final void bvpDetonateEraBrick(String brickName) {
        String normalized = EraBrickIds.stateId(brickName);
        if (normalized.isEmpty()) {
            return;
        }
        int expiryTick = this.f_19797_ + ERA_REGEN_DURATION_TICKS;
        this.bvpSpentEraExpiryTicks.put(normalized, expiryTick);
        this.bvpNextEraExpiryTick = Math.min(this.bvpNextEraExpiryTick, expiryTick);
        syncBvpSpentEraBricks();
        markArmorPlateHit("era:" + normalized);
    }

    public final String getBvpSpentEraBricks() {
        return this.f_19804_.m_135370_(SPENT_ERA_BRICKS);
    }

    @Override
    public final Map<String, String> captureFarRenderVisuals() {
        if (this instanceof com.yourname.berts_vehicle_pack.entity.helicopter.AuthoredHelicopter helicopter) {
            return Map.of(BvpFarVehicleVisuals.SPENT_ERA, getBvpSpentEraBricks(),
                    BvpFarVehicleVisuals.ENGINE_DISABLED, Boolean.toString(isEngineDisabled()),
                    BvpFarVehicleVisuals.ENGINE_RUNNING, Boolean.toString(engineRunning()),
                    BvpFarVehicleVisuals.ROTOR_ACTIVE, Boolean.toString(BvpFarVehicleVisuals.rotorActive(this)),
                    BvpFarVehicleVisuals.ROTOR_SPOOL, Double.toString(helicopter.getBvpRotorLiftPower()),
                    BvpFarVehicleVisuals.LEFT_TRACK_BROKEN, Boolean.toString(isLeftTrackBroken()),
                    BvpFarVehicleVisuals.RIGHT_TRACK_BROKEN, Boolean.toString(isRightTrackBroken()));
        }
        return Map.of(BvpFarVehicleVisuals.SPENT_ERA, getBvpSpentEraBricks(),
                BvpFarVehicleVisuals.ENGINE_DISABLED, Boolean.toString(isEngineDisabled()),
                BvpFarVehicleVisuals.ENGINE_RUNNING, Boolean.toString(engineRunning()),
                BvpFarVehicleVisuals.ROTOR_ACTIVE, Boolean.toString(BvpFarVehicleVisuals.rotorActive(this)),
                BvpFarVehicleVisuals.LEFT_TRACK_BROKEN, Boolean.toString(isLeftTrackBroken()),
                BvpFarVehicleVisuals.RIGHT_TRACK_BROKEN, Boolean.toString(isRightTrackBroken()));
    }

    @Override
    public final void applyFarRenderVisuals(Map<String, String> values) {
        if (!FarVehicleCopies.isCopy(this)) {
            throw new IllegalStateException("Cosmetic far state requires a render copy");
        }
        String spent = values.getOrDefault(BvpFarVehicleVisuals.SPENT_ERA, "");
        if (!getBvpSpentEraBricks().equals(spent)) {
            this.f_19804_.m_135381_(SPENT_ERA_BRICKS, spent);
        }
    }

    public final String getLastArmorHitPlate() {
        return this.f_19804_.m_135370_(LAST_ARMOR_HIT_PLATE);
    }

    public final int getLastArmorHitAge() {
        int tick = this.f_19804_.m_135370_(LAST_ARMOR_HIT_TICK);
        return tick <= 0 ? Integer.MAX_VALUE : this.f_19797_ - tick;
    }

    public final void damageModule(String moduleId, Vec3 hitVec, double damageAmount) {
        float before = EliteDiagnostics.isEnabled(this.m_9236_()) ? getModuleHealth(moduleId) : 0;
        bvpModuleDamage.damageModule(moduleId, hitVec, damageAmount);
        if (EliteDiagnostics.isEnabled(this.m_9236_())) {
            EliteDiagnostics.record(this, "damage", "module_commit", "module", moduleId,
                    "position", hitVec, "requested_damage", damageAmount,
                    "health_before", before, "health_after", getModuleHealth(moduleId));
        }
    }

    public final void setModuleHealth(String moduleId, double health) {
        float before = EliteDiagnostics.isEnabled(this.m_9236_()) ? getModuleHealth(moduleId) : 0;
        bvpModuleDamage.setModuleHealth(moduleId, health);
        if (EliteDiagnostics.isEnabled(this.m_9236_())) {
            EliteDiagnostics.record(this, "damage", "module_set", "module", moduleId,
                    "health_before", before, "health_after", getModuleHealth(moduleId));
        }
    }

    public final float getModuleHealth(String moduleId) {
        return bvpModuleDamage.getModuleHealth(moduleId);
    }

    public final boolean isModuleDestroyed(String moduleId) {
        return bvpModuleDamage.isModuleDestroyed(moduleId);
    }

    public final boolean isLeftTrackBroken() {
        return getLeftWheelDamaged();
    }

    public final boolean isRightTrackBroken() {
        return getRightWheelDamaged();
    }

    public final boolean isEngineDisabled() {
        return getMainEngineDamaged() || getSubEngineDamaged();
    }

    public final boolean usesBvpTrackModuleRepair() {
        return usesBvpTrackMobilitySystems();
    }

    public final boolean hasBvpWeaponsSystemsModule() {
        return bvpModuleDamage.hasWeaponsSystemsModules();
    }

    public final float getBvpWeaponsSystemsHealth() {
        return bvpModuleDamage.genericGroupHealth(VehicleModuleDamageSystem.REPAIR_TARGET_WEAPONS,
                (float) VehicleModuleHealth.WEAPONS_SYSTEMS_HP);
    }

    public final boolean isBvpWeaponsModuleDestroyed() {
        return bvpModuleDamage.hasDestroyedWeaponsSystems();
    }

    @Override
    public final List<VehicleModuleHudMarker> vehicleModuleHudLayout(float partialTick) {
        return ArmorModuleHudLayout.sample(this, partialTick);
    }

    @Override
    public final VehicleModuleHudState vehicleModuleHudState() {
        return new VehicleModuleHudState(
                new VehicleModuleHudHealth(getModuleHealth("engine"), (float) VehicleModuleHealth.ENGINE_HP,
                        isEngineDisabled()),
                usesBvpTrackModuleRepair() ? new VehicleModuleHudHealth(getModuleHealth("lefttrack"),
                        (float) VehicleModuleHealth.TRACK_HP, isLeftTrackBroken()) : null,
                usesBvpTrackModuleRepair() ? new VehicleModuleHudHealth(getModuleHealth("righttrack"),
                        (float) VehicleModuleHealth.TRACK_HP, isRightTrackBroken()) : null,
                hasBvpWeaponsSystemsModule() ? new VehicleModuleHudHealth(getBvpWeaponsSystemsHealth(),
                        (float) VehicleModuleHealth.WEAPONS_SYSTEMS_HP, isBvpWeaponsModuleDestroyed()) : null);
    }

    public final boolean hasBvpAmmoRackModule() {
        return bvpModuleDamage.hasAmmoRackModules();
    }

    public final float getBvpAmmoRackHealth() {
        return bvpModuleDamage.genericGroupHealth(VehicleModuleDamageSystem.REPAIR_TARGET_AMMO,
                (float) VehicleModuleHealth.AMMO_RACK_HP);
    }

    public final boolean isBvpAmmoRackDestroyed() {
        return bvpModuleDamage.isModuleDestroyed("ammorack")
                || bvpModuleDamage.hasDestroyedAmmoRackModules();
    }

    public final boolean isBvpTurretControlledBy(Player player) {
        return player != null && hasTurret() && getNthEntity(getTurretControllerIndex()) == player;
    }

    public final boolean isBvpPassengerWeaponControlledBy(Player player) {
        return player != null && hasPassengerWeaponStation()
                && getNthEntity(getPassengerWeaponStationControllerIndex()) == player;
    }

    public final boolean isBvpWeaponSeat(Player player) {
        if (player == null) {
            return false;
        }
        int seatIndex = getSeatIndex(player);
        return seatIndex >= 0 && (resolveVehicleSeatPose(player, 1.0F, false) != null
                || resolveVehicleAimProfile(seatIndex, getSelectedWeapon(seatIndex)) != null);
    }

    public final boolean isBvpAimAligned(Player player) {
        if (player == null) {
            return false;
        }
        int seatIndex = getSeatIndex(player);
        VehicleAimSnapshot snapshot = seatIndex < 0
                ? null
                : getVehicleAimSnapshot(seatIndex, getSelectedWeapon(seatIndex));
        return snapshot != null && snapshot.getLocked();
    }

    public final Player getBvpTurretController() {
        Entity controller = hasTurret() ? getNthEntity(getTurretControllerIndex()) : null;
        return controller instanceof Player player ? player : null;
    }

    public final Player getBvpPassengerWeaponController() {
        Entity controller = hasPassengerWeaponStation()
                ? getNthEntity(getPassengerWeaponStationControllerIndex())
                : null;
        return controller instanceof Player player ? player : null;
    }

    private void tickBvpEraRegeneration() {
        if (this.bvpSpentEraExpiryTicks.isEmpty() || this.f_19797_ < this.bvpNextEraExpiryTick) {
            return;
        }
        boolean changed = false;
        int nextExpiryTick = Integer.MAX_VALUE;
        Iterator<Map.Entry<String, Integer>> iterator = this.bvpSpentEraExpiryTicks.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Integer> entry = iterator.next();
            int expiryTick = entry.getValue();
            if (expiryTick <= this.f_19797_) {
                iterator.remove();
                changed = true;
            } else {
                nextExpiryTick = Math.min(nextExpiryTick, expiryTick);
            }
        }
        this.bvpNextEraExpiryTick = nextExpiryTick;
        if (changed) {
            syncBvpSpentEraBricks();
        }
    }

    private void readBvpEraState(CompoundTag tag) {
        this.bvpSpentEraExpiryTicks.clear();
        this.bvpNextEraExpiryTick = Integer.MAX_VALUE;
        CompoundTag spentEra = tag.m_128469_(TAG_SPENT_ERA_BRICKS);
        for (String brickName : spentEra.m_128431_()) {
            int ticks = spentEra.m_128451_(brickName);
            String normalized = EraBrickIds.stateId(brickName);
            if (ticks > 0 && !normalized.isEmpty()) {
                int expiryTick = this.f_19797_ + ticks;
                this.bvpSpentEraExpiryTicks.put(normalized, expiryTick);
                this.bvpNextEraExpiryTick = Math.min(this.bvpNextEraExpiryTick, expiryTick);
            }
        }
        syncBvpSpentEraBricks();
    }

    private void addBvpEraState(CompoundTag tag) {
        CompoundTag spentEra = new CompoundTag();
        for (Map.Entry<String, Integer> entry : this.bvpSpentEraExpiryTicks.entrySet()) {
            int remainingTicks = entry.getValue() - this.f_19797_;
            if (remainingTicks > 0) {
                spentEra.m_128405_(entry.getKey(), remainingTicks);
            }
        }
        if (!spentEra.m_128456_()) {
            tag.m_128365_(TAG_SPENT_ERA_BRICKS, spentEra);
        }
    }

    private void syncBvpSpentEraBricks() {
        StringJoiner joiner = new StringJoiner(",");
        for (String brickName : this.bvpSpentEraExpiryTicks.keySet()) {
            joiner.add(brickName);
        }
        this.f_19804_.m_135381_(SPENT_ERA_BRICKS, joiner.toString());
    }

    public final void bvpOnLeftWheelDamaged(Vec3 hitVec) {
        this.onLeftWheelDamaged(hitVec);
    }

    public final void bvpOnRightWheelDamaged(Vec3 hitVec) {
        this.onRightWheelDamaged(hitVec);
    }

    public final void bvpOnEngine1Damaged(Vec3 hitVec) {
        this.onEngine1Damaged(hitVec);
    }

    @Override
    public void onEngine1Damaged(Vec3 hitVec) {
        // Ground engine smoke and intermittent flame originate at the authored exhaust outlets.
        // The native module effect would add an unrelated vanilla smoke/spark plume in the hull.
        if (!usesBvpGroundMobilityLimits()) {
            super.onEngine1Damaged(hitVec);
        }
    }

    @Override
    public void onEngine2Damaged(Vec3 hitVec) {
        if (!usesBvpGroundMobilityLimits()) {
            super.onEngine2Damaged(hitVec);
        }
    }

    public final void bvpOnEngine2Damaged(Vec3 hitVec) {
        this.onEngine2Damaged(hitVec);
    }

}
