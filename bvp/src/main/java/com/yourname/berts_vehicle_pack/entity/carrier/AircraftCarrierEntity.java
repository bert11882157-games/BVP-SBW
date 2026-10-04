package com.yourname.berts_vehicle_pack.entity.carrier;

import com.atsuishio.superbwarfare.api.vehicle.deck.DeckCarry;
import com.atsuishio.superbwarfare.api.vehicle.deck.DeckPose;
import com.atsuishio.superbwarfare.api.vehicle.deck.DeckSurface;
import com.atsuishio.superbwarfare.api.vehicle.deck.DeckSurfaceEntity;
import com.yourname.berts_vehicle_pack.carrier.BvpDeckSurfaces;
import com.yourname.berts_vehicle_pack.carrier.CarrierHull;
import com.yourname.berts_vehicle_pack.entity.ArmoredVehicleEntity;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * An aircraft carrier: a 1:1 hull (Essex 267 blocks, Kiev 269) floating with its origin on the water surface. Its
 * deck heightfield is terrain (SBW DeckSurfaceEntity): players walk on it at any heading, aircraft land and brake
 * on it, vehicles are placed and driven on it, and whatever stands on it moves and turns with the ship.
 *
 * The helm (seat 0, on the island) steers: W/S set the engine order (latched, -35 % astern .. 100 % ahead),
 * A/D the rudder. The ship keeps its waterline, never pitches or rolls, and stops instead of grounding or
 * sailing into land (CarrierHull). Speed and turn rate are server state, synced, and applied the same way on the
 * client so the deck the client collides with moves with the drawn hull.
 */
public class AircraftCarrierEntity extends ArmoredVehicleEntity implements DeckSurfaceEntity {
    private static final EntityDataAccessor<Float> THROTTLE =
            SynchedEntityData.defineId(AircraftCarrierEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> SPEED =
            SynchedEntityData.defineId(AircraftCarrierEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> TURN =
            SynchedEntityData.defineId(AircraftCarrierEntity.class, EntityDataSerializers.FLOAT);

    /** Engine order change per tick held (0..100 % in 5 s). */
    private static final float THROTTLE_STEP = 0.01F;
    private static final float ASTERN = 0.35F;
    /** Blocks per tick², full speed in about 20 s. */
    private static final float ACCELERATION = 0.002F;
    /** Degrees per tick at full rudder and speed (1.6 deg/s). */
    private static final float MAX_TURN = 0.08F;
    /** Rudder authority with little way on (thrusters and screw wash). */
    private static final float MIN_AUTHORITY = 0.15F;

    private final String carrierId;
    private final float maxSpeed;
    private float yawBeforeTurn;

    public AircraftCarrierEntity(EntityType<? extends AircraftCarrierEntity> type, Level level, String carrierId,
                                 float maxSpeedBlocksPerTick) {
        super(type, level, carrierId);
        this.carrierId = carrierId;
        this.maxSpeed = maxSpeedBlocksPerTick;
    }

    @Override
    public DeckSurface deckSurface() {
        return BvpDeckSurfaces.get(this.carrierId);
    }

    public String carrierId() {
        return this.carrierId;
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(THROTTLE, 0.0F);
        this.entityData.define(SPEED, 0.0F);
        this.entityData.define(TURN, 0.0F);
    }

    public float throttle() {
        return this.entityData.get(THROTTLE);
    }

    public float speed() {
        return this.entityData.get(SPEED);
    }

    @Override
    public void travel() {
        super.travel();
        this.yawBeforeTurn = this.getYRot();
        if (!this.level().isClientSide) {
            Entity helm = this.getFirstPassenger();
            float throttle = this.entityData.get(THROTTLE);
            float rudder = 0.0F;
            if (helm != null) {
                if (this.forwardInputDown()) throttle = Math.min(1.0F, throttle + THROTTLE_STEP);
                if (this.backInputDown()) throttle = Math.max(-ASTERN, throttle - THROTTLE_STEP);
                if (this.leftInputDown()) rudder -= 1.0F;
                if (this.rightInputDown()) rudder += 1.0F;
            }
            float speed = this.entityData.get(SPEED);
            speed += Mth.clamp(throttle * this.maxSpeed - speed, -ACCELERATION, ACCELERATION);
            if (throttle == 0.0F && Math.abs(speed) < 1.0E-4F) speed = 0.0F;
            float authority = Mth.clamp(Math.abs(speed) / this.maxSpeed * 2.0F, MIN_AUTHORITY, 1.0F);
            float turn = rudder * MAX_TURN * authority * (speed < 0.0F ? -1.0F : 1.0F);
            this.entityData.set(THROTTLE, throttle);
            this.entityData.set(SPEED, speed);
            this.entityData.set(TURN, turn);
        }
        float turn = this.entityData.get(TURN);
        if (turn != 0.0F) this.setYRot(this.getYRot() + turn);
        double yaw = Math.toRadians(this.getYRot());
        float speed = this.entityData.get(SPEED);
        this.setDeltaMovement(-Math.sin(yaw) * speed, 0.0D, Math.cos(yaw) * speed);
    }

    @Override
    public void move(MoverType type, Vec3 movement) {
        // Pistons, shulkers and explosions never shove a carrier; it moves only under its own way.
        if (type != MoverType.SELF) return;
        double dx = movement.x;
        double dz = movement.z;
        if (!this.level().isClientSide) {
            DeckSurface surface = this.deckSurface();
            float yaw = this.getYRot();
            boolean turned = yaw != this.yawBeforeTurn;
            if (surface != null && (dx * dx + dz * dz > 1.0E-10D || turned)) {
                DeckPose next = new DeckPose(this.getX() + dx, this.getY(), this.getZ() + dz, yaw);
                if (!CarrierHull.clear(this.level(), surface, next)) {
                    dx = 0.0D;
                    dz = 0.0D;
                    this.entityData.set(SPEED, 0.0F);
                    if (turned && !CarrierHull.clear(this.level(), surface,
                            new DeckPose(this.getX(), this.getY(), this.getZ(), yaw))) {
                        this.setYRot(this.yawBeforeTurn);
                    }
                    this.horizontalCollision = true;
                } else {
                    this.horizontalCollision = false;
                }
            }
        }
        // The waterline never changes: no gravity, buoyancy, pitch or roll.
        this.setPos(this.getX() + dx, this.getY(), this.getZ() + dz);
        this.setDeltaMovement(dx, 0.0D, dz);
    }

    @Override
    protected void afterVehicleTick() {
        super.afterVehicleTick();
        DeckCarry.afterOwnerMoved(this);
        if (this.level() instanceof ServerLevel) {
            DeckSurface surface = this.deckSurface();
            if (surface != null && this.computed().getKeepChunkLoaded()) {
                // the entity's own ticket covers its centre; the bow and stern sit 130 blocks away
                DeckPose pose = DeckPose.of(this);
                this.keepChunkLoaded(new Vec3(pose.worldX(0.0D, surface.getMaxZ() - 8.0D), this.getY(),
                        pose.worldZ(0.0D, surface.getMaxZ() - 8.0D)));
                this.keepChunkLoaded(new Vec3(pose.worldX(0.0D, surface.getMinZ() + 8.0D), this.getY(),
                        pose.worldZ(0.0D, surface.getMinZ() + 8.0D)));
            }
            if (this.tickCount % 10 == 0 && this.getFirstPassenger() instanceof ServerPlayer helm) {
                helm.displayClientMessage(helmReport(), true);
            }
        }
    }

    private Component helmReport() {
        float throttle = this.entityData.get(THROTTLE);
        float speed = this.entityData.get(SPEED);
        float turn = this.entityData.get(TURN);
        String order = throttle == 0.0F ? "STOP"
                : (throttle > 0.0F ? "AHEAD " : "ASTERN ") + Math.round(Math.abs(throttle) * 100.0F) + "%";
        // 1 block = 1 m: blocks/tick x 20 x 3600 / 1852
        double knots = Math.abs(speed) * 20.0D * 3600.0D / 1852.0D;
        String rudder = turn > 0.0F ? "  RUDDER RIGHT" : turn < 0.0F ? "  RUDDER LEFT" : "";
        String grounded = this.horizontalCollision ? "  -  BLOCKED" : "";
        return Component.literal(String.format("Engines %s  |  %.1f kn  |  heading %03d%s%s", order, knots,
                Math.floorMod(Math.round(this.getYRot()) + 180, 360), rudder, grounded));
    }

    /**
     * The periodic absolute refresh (every 20 s) carries yaw as a byte, 1.4 degree steps: snapping a 267-block hull
     * to it throws the bow up to 3 blocks sideways, and everyone on deck with it. Small refreshes are interpolated
     * instead, the yaw converging on the exact synced heading; real jumps (first sync, /tp) still snap.
     */
    @Override
    public void lerpTo(double x, double y, double z, float yaw, float pitch, int steps, boolean teleport) {
        boolean small = this.position().distanceToSqr(x, y, z) < 64.0D;
        super.lerpTo(x, y, z, yaw, pitch, steps, teleport && !small);
    }

    /** The engine order stays latched when the helm is left: a carrier can steam into the wind with nobody on it. */
    @Override
    protected void applyUnoccupiedParkingBrake() {
    }

    @Override
    public boolean canCrushEntities() {
        return false;
    }

    @Override
    public boolean usesBvpArmorResolution() {
        return false;
    }

    @Override
    public boolean usesBvpGroundMobilityLimits() {
        return false;
    }

    @Override
    protected boolean usesBvpTrackMobilitySystems() {
        return false;
    }

    @Override
    protected boolean usesBvpAmmoRackWarnings() {
        return false;
    }

    /** The whole hull, not the small physics box at its centre (a 3x3 column would cull the ship). */
    @Override
    public AABB getBoundingBoxForCulling() {
        DeckSurface surface = this.deckSurface();
        if (surface == null) return super.getBoundingBoxForCulling();
        return DeckPose.of(this).envelope(surface).inflate(2.0D);
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distanceSquared) {
        DeckSurface surface = this.deckSurface();
        double reach = (surface == null ? 0.0D : surface.getRadius()) + 512.0D;
        return distanceSquared < reach * reach;
    }
}
