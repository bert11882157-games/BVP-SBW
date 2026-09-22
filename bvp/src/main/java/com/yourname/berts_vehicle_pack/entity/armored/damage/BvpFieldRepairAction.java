package com.yourname.berts_vehicle_pack.entity.armored.damage;

import com.atsuishio.superbwarfare.api.vehicle.action.VehicleAction;
import com.atsuishio.superbwarfare.api.aircraft.AircraftSurfaceModules;
import com.atsuishio.superbwarfare.api.vehicle.action.VehicleActionContext;
import com.atsuishio.superbwarfare.api.vehicle.action.VehicleActionControlPolicy;
import com.atsuishio.superbwarfare.api.vehicle.action.VehicleActionRegistry;
import com.atsuishio.superbwarfare.api.vehicle.action.VehicleActionSnapshot;
import com.atsuishio.superbwarfare.api.vehicle.action.VehicleActionState;
import com.atsuishio.superbwarfare.api.vehicle.action.VehicleActionUpdate;
import com.atsuishio.superbwarfare.api.vehicle.action.VehicleActionTransactionJournal;
import com.atsuishio.superbwarfare.api.vehicle.action.VehicleCriticalPartFailureMode;
import com.atsuishio.superbwarfare.api.vehicle.action.VehicleDamagedRecoveryMode;
import com.atsuishio.superbwarfare.api.vehicle.action.VehicleRepairPolicies;
import com.atsuishio.superbwarfare.api.vehicle.action.VehicleRepairPolicy;
import com.yourname.berts_vehicle_pack.BertsVehiclePack;
import com.yourname.berts_vehicle_pack.armor.VehicleModuleHealth;
import com.yourname.berts_vehicle_pack.entity.ArmoredVehicleEntity;
import net.minecraft.resources.ResourceLocation;

public final class BvpFieldRepairAction extends VehicleAction {
    public static final ResourceLocation ACTION_ID = id("field_repair");
    public static final ResourceLocation PHASE_NORMAL = id("field_repair/normal");
    public static final ResourceLocation PHASE_EMERGENCY_HOLD = id("field_repair/emergency_hold");
    public static final ResourceLocation PHASE_EMERGENCY = id("field_repair/emergency");

    public static final ResourceLocation TARGET_NONE = id("field_repair/target/none");
    public static final ResourceLocation TARGET_WEAPONS = id("field_repair/target/weapons");
    public static final ResourceLocation TARGET_AMMO = id("field_repair/target/ammo");
    public static final ResourceLocation TARGET_ENGINE = id("field_repair/target/engine");
    public static final ResourceLocation TARGET_TRACKS = id("field_repair/target/tracks");
    public static final ResourceLocation TARGET_HULL = id("field_repair/target/hull");
    public static final ResourceLocation TARGET_AIRCRAFT = id("field_repair/target/aircraft_surfaces");
    public static final int REPAIR_TARGET_AIRCRAFT = 6;

    public static final int MODE_IDLE = 0;
    public static final int MODE_NORMAL = 1;
    public static final int MODE_EMERGENCY_HOLD = 2;
    public static final int MODE_EMERGENCY = 3;

    public static final int SEGMENT_DURATION_TICKS = 200;
    public static final int EMERGENCY_HOLD_TICKS = 50;
    /** Duration ratio for the repair-rate balance; transaction and rollback cadence is unchanged. */
    public static final float REPAIR_DURATION_SCALE = 4.0F;
    public static final float MODULE_REPAIR_PER_TICK = 0.025F;
    public static final float HULL_REPAIR_PER_TICK = 0.0625F;
    public static final float EMERGENCY_HULL_REPAIR_PER_TICK = 0.1F;

    private static final float HEALTH_EPSILON = 0.001F;
    private static final ResourceLocation REPAIR_POLICY_ID = id("field_repair_policy");
    private static final VehicleRepairPolicy BVP_REPAIR_POLICY = new VehicleRepairPolicy(
            false,
            0.0F,
            VehicleDamagedRecoveryMode.EXPLICIT,
            0.95F,
            VehicleCriticalPartFailureMode.TURRET_AND_ENGINES
    );

    private final ArmoredVehicleEntity vehicle;
    private final VehicleModuleDamageSystem modules;
    private int mode = MODE_IDLE;
    private int phaseTicks;
    private int target = VehicleModuleDamageSystem.REPAIR_TARGET_NONE;
    private boolean repairKeyDown;

    private BvpFieldRepairAction(ArmoredVehicleEntity vehicle) {
        this.vehicle = vehicle;
        this.modules = new VehicleModuleDamageSystem(vehicle);
    }

    public static void register() {
        VehicleActionRegistry.register(ACTION_ID,
                vehicle -> vehicle instanceof ArmoredVehicleEntity armored
                        ? new BvpFieldRepairAction(armored)
                        : null);
        VehicleRepairPolicies.register(REPAIR_POLICY_ID,
                vehicle -> vehicle instanceof ArmoredVehicleEntity ? BVP_REPAIR_POLICY : null);
    }

    @Override
    public VehicleActionUpdate handleInput(VehicleActionContext context, boolean held) {
        if (!held) {
            repairKeyDown = false;
            return mode == MODE_EMERGENCY_HOLD
                    ? VehicleActionUpdate.COMPLETE_COMMIT
                    : VehicleActionUpdate.CONTINUE;
        }

        if (mode == MODE_NORMAL || mode == MODE_EMERGENCY) {
            return VehicleActionUpdate.COMPLETE_ROLLBACK;
        }
        if (mode == MODE_EMERGENCY_HOLD) {
            return VehicleActionUpdate.CONTINUE;
        }

        repairKeyDown = true;
        if (!canRepair()) {
            return VehicleActionUpdate.COMPLETE_COMMIT;
        }
        if (isHullBelowHalf()) {
            setState(MODE_EMERGENCY_HOLD, 0, VehicleModuleDamageSystem.REPAIR_TARGET_HULL);
            return VehicleActionUpdate.CONTINUE;
        }
        if (hasAnyRepairableDamage()) {
            beginSegment(MODE_NORMAL);
            return VehicleActionUpdate.CONTINUE;
        }
        return VehicleActionUpdate.COMPLETE_COMMIT;
    }

    @Override
    public VehicleActionUpdate tick(VehicleActionContext context) {
        if (!canRepair()) {
            return VehicleActionUpdate.COMPLETE_ROLLBACK;
        }
        return switch (mode) {
            case MODE_EMERGENCY_HOLD -> tickEmergencyHold();
            case MODE_NORMAL -> tickNormalRepair(context);
            case MODE_EMERGENCY -> tickEmergencyRepair(context);
            default -> VehicleActionUpdate.COMPLETE_COMMIT;
        };
    }

    @Override
    public VehicleActionState state(VehicleActionContext context) {
        return new VehicleActionState(phaseId(mode), phaseTicks, targetId(target));
    }

    @Override
    public VehicleActionControlPolicy controlPolicy(VehicleActionContext context) {
        return VehicleActionControlPolicy.BLOCK_MOVEMENT_AND_FIRE;
    }

    public static int mode(VehicleActionSnapshot snapshot) {
        if (snapshot == null || !ACTION_ID.equals(snapshot.getActionId())) {
            return MODE_IDLE;
        }
        ResourceLocation phase = snapshot.getPhaseId();
        if (PHASE_NORMAL.equals(phase)) {
            return MODE_NORMAL;
        }
        if (PHASE_EMERGENCY_HOLD.equals(phase)) {
            return MODE_EMERGENCY_HOLD;
        }
        if (PHASE_EMERGENCY.equals(phase)) {
            return MODE_EMERGENCY;
        }
        return MODE_IDLE;
    }

    public static int target(VehicleActionSnapshot snapshot) {
        ResourceLocation targetId = snapshot == null ? null : snapshot.getTargetId();
        if (targetId == null) {
            return VehicleModuleDamageSystem.REPAIR_TARGET_NONE;
        }
        if (TARGET_WEAPONS.equals(targetId)) {
            return VehicleModuleDamageSystem.REPAIR_TARGET_WEAPONS;
        }
        if (TARGET_AMMO.equals(targetId)) {
            return VehicleModuleDamageSystem.REPAIR_TARGET_AMMO;
        }
        if (TARGET_ENGINE.equals(targetId)) {
            return VehicleModuleDamageSystem.REPAIR_TARGET_ENGINE;
        }
        if (TARGET_TRACKS.equals(targetId)) {
            return VehicleModuleDamageSystem.REPAIR_TARGET_TRACKS;
        }
        if (TARGET_HULL.equals(targetId)) {
            return VehicleModuleDamageSystem.REPAIR_TARGET_HULL;
        }
        if (TARGET_AIRCRAFT.equals(targetId)) return REPAIR_TARGET_AIRCRAFT;
        return VehicleModuleDamageSystem.REPAIR_TARGET_NONE;
    }

    public static boolean hasRepairableDamage(ArmoredVehicleEntity vehicle) {
        if (vehicle == null) {
            return false;
        }
        for (ResourceLocation id : surfaceIds()) {
            var state = vehicle.getVehicleModuleState(id);
            if (state != null && (state.getDestroyed() || state.getHealth() < state.getMaxHealth() - HEALTH_EPSILON)) return true;
        }
        if (vehicle.getHealth() < vehicle.getMaxHealth() - HEALTH_EPSILON
                || vehicle.getModuleHealth("engine") < VehicleModuleHealth.ENGINE_HP - HEALTH_EPSILON
                || vehicle.isEngineDisabled()) {
            return true;
        }
        if (vehicle.usesBvpTrackModuleRepair()
                && (vehicle.getModuleHealth("lefttrack") < VehicleModuleHealth.TRACK_HP - HEALTH_EPSILON
                || vehicle.getModuleHealth("righttrack") < VehicleModuleHealth.TRACK_HP - HEALTH_EPSILON
                || vehicle.isLeftTrackBroken() || vehicle.isRightTrackBroken())) {
            return true;
        }
        return vehicle.hasBvpWeaponsSystemsModule()
                && (vehicle.isBvpWeaponsModuleDestroyed()
                || vehicle.getBvpWeaponsSystemsHealth() < VehicleModuleHealth.WEAPONS_SYSTEMS_HP - HEALTH_EPSILON)
                || vehicle.hasBvpAmmoRackModule()
                && (vehicle.isBvpAmmoRackDestroyed()
                || vehicle.getBvpAmmoRackHealth() < VehicleModuleHealth.AMMO_RACK_HP - HEALTH_EPSILON);
    }

    private VehicleActionUpdate tickEmergencyHold() {
        if (!isHullBelowHalf() || !repairKeyDown) {
            return VehicleActionUpdate.COMPLETE_COMMIT;
        }
        phaseTicks++;
        if (phaseTicks >= EMERGENCY_HOLD_TICKS) {
            beginSegment(MODE_EMERGENCY);
        }
        return VehicleActionUpdate.CONTINUE;
    }

    private VehicleActionUpdate tickNormalRepair(VehicleActionContext context) {
        if (isHullBelowHalf()) {
            return VehicleActionUpdate.COMPLETE_ROLLBACK;
        }
        if (!hasAnyRepairableDamage()) {
            return VehicleActionUpdate.COMPLETE_COMMIT;
        }

        int repairedTarget = repairAircraftSurface(context) ? REPAIR_TARGET_AIRCRAFT : modules.repairHighestPriorityGroup(
                MODULE_REPAIR_PER_TICK,
                context.getJournal());
        if (repairedTarget == VehicleModuleDamageSystem.REPAIR_TARGET_NONE) {
            repairedTarget = VehicleModuleDamageSystem.REPAIR_TARGET_HULL;
            repairHull(context, HULL_REPAIR_PER_TICK, vehicle.getMaxHealth());
        }
        target = repairedTarget;
        return advanceSegment(context);
    }

    private VehicleActionUpdate tickEmergencyRepair(VehicleActionContext context) {
        float halfHealth = vehicle.getMaxHealth() * 0.5F;
        if (vehicle.getHealth() >= halfHealth - HEALTH_EPSILON) {
            return VehicleActionUpdate.COMPLETE_COMMIT;
        }
        target = VehicleModuleDamageSystem.REPAIR_TARGET_HULL;
        repairHull(context, EMERGENCY_HULL_REPAIR_PER_TICK, halfHealth);
        if (vehicle.getHealth() >= halfHealth - HEALTH_EPSILON) {
            return VehicleActionUpdate.COMPLETE_COMMIT;
        }
        return advanceSegment(context);
    }

    private VehicleActionUpdate advanceSegment(VehicleActionContext context) {
        phaseTicks++;
        if (phaseTicks < SEGMENT_DURATION_TICKS) {
            return VehicleActionUpdate.CONTINUE;
        }

        context.getJournal().commit();
        phaseTicks = 0;
        return hasAnyRepairableDamage()
                ? VehicleActionUpdate.CONTINUE
                : VehicleActionUpdate.COMPLETE_COMMIT;
    }

    private void repairHull(VehicleActionContext context, float amount, float maximum) {
        float previous = vehicle.getHealth();
        float repaired = Math.min(maximum, previous + amount);
        vehicle.setHealth(repaired);
        context.getJournal().recordHullGain(Math.max(0.0F, repaired - previous));
    }

    private void beginSegment(int repairMode) {
        mode = repairMode;
        phaseTicks = 0;
        target = repairMode == MODE_EMERGENCY
                ? VehicleModuleDamageSystem.REPAIR_TARGET_HULL
                : VehicleModuleDamageSystem.REPAIR_TARGET_NONE;
    }

    private void setState(int repairMode, int ticks, int repairTarget) {
        mode = repairMode;
        phaseTicks = ticks;
        target = repairTarget;
    }

    private boolean canRepair() {
        return !vehicle.isWreck() && vehicle.getHealth() > 0.0F
                && (!vehicle.isFixedWingFlightVehicle() || vehicle.m_20096_());
    }

    private boolean hasAnyRepairableDamage() {
        return modules.hasRepairableModuleDamage()
                || hasAircraftSurfaceDamage()
                || vehicle.getHealth() < vehicle.getMaxHealth() - HEALTH_EPSILON;
    }

    private boolean hasAircraftSurfaceDamage() {
        for (ResourceLocation id : surfaceIds()) {
            var state = vehicle.getVehicleModuleState(id);
            if (state != null && (state.getDestroyed() || state.getHealth() < state.getMaxHealth() - HEALTH_EPSILON)) return true;
        }
        return false;
    }

    private boolean repairAircraftSurface(VehicleActionContext context) {
        if (!vehicle.m_20096_()) return false;
        for (ResourceLocation id : surfaceIds()) {
            var state = vehicle.getVehicleModuleState(id);
            if (state == null || (!state.getDestroyed() && state.getHealth() >= state.getMaxHealth() - HEALTH_EPSILON)) continue;
            float repaired = Math.min(state.getMaxHealth(), state.getHealth() + state.getMaxHealth() / 400F);
            vehicle.setVehicleModuleState(id, repaired, state.getDestroyed() && repaired < state.getMaxHealth() - HEALTH_EPSILON);
            context.getJournal().recordModuleGain(id, repaired - state.getHealth(), state.getDestroyed());
            return true;
        }
        return false;
    }

    private static java.util.List<ResourceLocation> surfaceIds() {
        return AircraftSurfaceModules.ids;
    }

    private boolean isHullBelowHalf() {
        return vehicle.getHealth() < vehicle.getMaxHealth() * 0.5F - HEALTH_EPSILON;
    }

    private static ResourceLocation phaseId(int mode) {
        return switch (mode) {
            case MODE_EMERGENCY_HOLD -> PHASE_EMERGENCY_HOLD;
            case MODE_EMERGENCY -> PHASE_EMERGENCY;
            default -> PHASE_NORMAL;
        };
    }

    private static ResourceLocation targetId(int target) {
        return switch (target) {
            case VehicleModuleDamageSystem.REPAIR_TARGET_WEAPONS -> TARGET_WEAPONS;
            case VehicleModuleDamageSystem.REPAIR_TARGET_AMMO -> TARGET_AMMO;
            case VehicleModuleDamageSystem.REPAIR_TARGET_ENGINE -> TARGET_ENGINE;
            case VehicleModuleDamageSystem.REPAIR_TARGET_TRACKS -> TARGET_TRACKS;
            case VehicleModuleDamageSystem.REPAIR_TARGET_HULL -> TARGET_HULL;
            case REPAIR_TARGET_AIRCRAFT -> TARGET_AIRCRAFT;
            default -> TARGET_NONE;
        };
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation(BertsVehiclePack.MODID, path);
    }
}
