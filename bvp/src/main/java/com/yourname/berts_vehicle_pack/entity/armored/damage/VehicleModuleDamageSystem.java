package com.yourname.berts_vehicle_pack.entity.armored.damage;

import com.atsuishio.superbwarfare.api.vehicle.module.VehicleModuleState;
import com.atsuishio.superbwarfare.api.vehicle.action.VehicleActionTransactionJournal;
import com.yourname.berts_vehicle_pack.BertsVehiclePack;
import com.yourname.berts_vehicle_pack.armor.ArmorProfiles;
import com.yourname.berts_vehicle_pack.armor.ArmorProfiles.ArmorBox;
import com.yourname.berts_vehicle_pack.armor.VehicleModuleHealth;
import com.yourname.berts_vehicle_pack.entity.ArmoredVehicleEntity;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;

public final class VehicleModuleDamageSystem {
    public static final int REPAIR_TARGET_NONE = 0;
    public static final int REPAIR_TARGET_WEAPONS = 1;
    public static final int REPAIR_TARGET_AMMO = 2;
    public static final int REPAIR_TARGET_ENGINE = 3;
    public static final int REPAIR_TARGET_TRACKS = 4;
    public static final int REPAIR_TARGET_HULL = 5;

    private static final float HEALTH_EPSILON = 0.001F;

    private final ArmoredVehicleEntity vehicle;

    public VehicleModuleDamageSystem(ArmoredVehicleEntity vehicle) {
        this.vehicle = vehicle;
    }

    public void damageModule(String moduleId, Vec3 hitVec, double damageAmount) {
        String normalized = normalizeModuleId(moduleId);
        double damage = normalizedModuleDamage(damageAmount);
        switch (normalized) {
            case "lefttrack" -> damageTrackSide(true, hitVec, damage);
            case "righttrack" -> damageTrackSide(false, hitVec, damage);
            case "track" -> {
                damageTrackSide(true, hitVec, damage);
                damageTrackSide(false, hitVec, damage);
            }
            case "engine" -> damageEngine(hitVec, damage);
            default -> damageGenericModule(normalized, damage);
        }
    }

    public float getModuleHealth(String moduleId) {
        String normalized = normalizeModuleId(moduleId);
        return switch (normalized) {
            case "lefttrack" -> health(BvpVehicleModules.LEFT_TRACK, (float) VehicleModuleHealth.TRACK_HP);
            case "righttrack" -> health(BvpVehicleModules.RIGHT_TRACK, (float) VehicleModuleHealth.TRACK_HP);
            case "track" -> Math.min(
                    health(BvpVehicleModules.LEFT_TRACK, (float) VehicleModuleHealth.TRACK_HP),
                    health(BvpVehicleModules.RIGHT_TRACK, (float) VehicleModuleHealth.TRACK_HP));
            case "engine" -> Math.min(
                    health(BvpVehicleModules.MAIN_ENGINE, (float) VehicleModuleHealth.ENGINE_HP),
                    health(BvpVehicleModules.SUB_ENGINE, (float) VehicleModuleHealth.ENGINE_HP));
            default -> health(BvpVehicleModules.idForNormalized(normalized), maxHealthFor(normalized));
        };
    }

    public boolean isModuleDestroyed(String moduleId) {
        String normalized = normalizeModuleId(moduleId);
        return switch (normalized) {
            case "lefttrack" -> destroyed(BvpVehicleModules.LEFT_TRACK);
            case "righttrack" -> destroyed(BvpVehicleModules.RIGHT_TRACK);
            case "track" -> destroyed(BvpVehicleModules.LEFT_TRACK) && destroyed(BvpVehicleModules.RIGHT_TRACK);
            case "engine" -> destroyed(BvpVehicleModules.MAIN_ENGINE) || destroyed(BvpVehicleModules.SUB_ENGINE);
            default -> destroyed(BvpVehicleModules.idForNormalized(normalized));
        };
    }

    private void damageEngine(Vec3 hitVec, double damage) {
        if (damageLegacyModule(BvpVehicleModules.MAIN_ENGINE, damage)) {
            vehicle.bvpOnEngine1Damaged(hitVec);
        }
        if (damageLegacyModule(BvpVehicleModules.SUB_ENGINE, damage)) {
            vehicle.bvpOnEngine2Damaged(hitVec);
        }
        if (isModuleDestroyed("engine")) {
            disableEngineMobility();
        }
    }

    public void applyMobilityLimit() {
        if (vehicle.isEngineDisabled()) {
            stopAllMovementInput();
        }
        // Native SBW running-gear damage owns broken-track drift and speed loss.
    }

    public double engineMobilityMultiplier() {
        if (vehicle.isEngineDisabled()) {
            return 0.0D;
        }
        float health = getModuleHealth("engine");
        if (health <= 10.0F) {
            return 0.2D;
        }
        if (health <= 20.0F) {
            return 1.0D / 3.0D;
        }
        if (health <= 40.0F) {
            return 2.0D / 3.0D;
        }
        return 1.0D;
    }

    public boolean hasRepairableModuleDamage() {
        return hasDamagedGenericGroup(REPAIR_TARGET_WEAPONS)
                || hasDamagedGenericGroup(REPAIR_TARGET_AMMO)
                || hasDamagedEngine()
                || vehicle.usesBvpTrackModuleRepair() && hasDamagedTracks();
    }

    public int repairHighestPriorityGroup(float amount, VehicleActionTransactionJournal journal) {
        float repairAmount = Math.max(0.0F, amount);
        if (hasDamagedGenericGroup(REPAIR_TARGET_WEAPONS)) {
            repairGenericGroup(REPAIR_TARGET_WEAPONS, repairAmount, journal);
            return REPAIR_TARGET_WEAPONS;
        }
        if (hasDamagedGenericGroup(REPAIR_TARGET_AMMO)) {
            repairGenericGroup(REPAIR_TARGET_AMMO, repairAmount, journal);
            return REPAIR_TARGET_AMMO;
        }
        if (hasDamagedEngine()) {
            repairEngine(repairAmount, journal);
            return REPAIR_TARGET_ENGINE;
        }
        if (vehicle.usesBvpTrackModuleRepair() && hasDamagedTracks()) {
            repairTracks(repairAmount, journal);
            return REPAIR_TARGET_TRACKS;
        }
        return REPAIR_TARGET_NONE;
    }

    public boolean hasDestroyedWeaponsSystems() {
        return hasDestroyedGenericGroup(REPAIR_TARGET_WEAPONS);
    }

    public boolean hasDestroyedAmmoRackModules() {
        return hasDestroyedGenericGroup(REPAIR_TARGET_AMMO);
    }

    public void setModuleHealth(String moduleId, double health) {
        String normalized = normalizeModuleId(moduleId);
        if (normalized.isBlank()) {
            return;
        }
        switch (normalized) {
            case "lefttrack" -> setTrackModuleHealth(true, health);
            case "righttrack" -> setTrackModuleHealth(false, health);
            case "track" -> {
                setTrackModuleHealth(true, health);
                setTrackModuleHealth(false, health);
            }
            case "engine" -> setEngineModuleHealth(health);
            default -> vehicle.setVehicleModuleHealth(BvpVehicleModules.idForNormalized(normalized), health);
        }
    }

    private void damageTrackSide(boolean left, Vec3 hitVec, double damage) {
        ResourceLocation id = left ? BvpVehicleModules.LEFT_TRACK : BvpVehicleModules.RIGHT_TRACK;
        if (!damageLegacyModule(id, damage)) {
            return;
        }
        if (left) {
            vehicle.bvpOnLeftWheelDamaged(hitVec);
        } else {
            vehicle.bvpOnRightWheelDamaged(hitVec);
        }
    }

    private boolean damageLegacyModule(ResourceLocation id, double damage) {
        VehicleModuleState previous = vehicle.getVehicleModuleState(id);
        VehicleModuleState current = vehicle.damageVehicleModule(id, damage);
        return previous != null && current != null && !previous.getDestroyed() && current.getDestroyed();
    }

    private void damageGenericModule(String moduleId, double damage) {
        if (moduleId == null || moduleId.isBlank() || damage <= 0.0D) {
            return;
        }
        vehicle.damageVehicleModule(BvpVehicleModules.idForNormalized(moduleId), damage);
    }

    private void repairGenericGroup(int group, float amount, VehicleActionTransactionJournal journal) {
        for (VehicleModuleState state : vehicle.getVehicleModuleStates()) {
            if (!isGenericModuleState(state) || !isGenericGroup(normalizedId(state), group) || !isDamaged(state)) {
                continue;
            }
            float previousHealth = state.getHealth();
            boolean wasDestroyed = state.getDestroyed();
            float repairedHealth = Math.min(state.getMaxHealth(), previousHealth + amount);
            boolean destroyed = repairedHealth < state.getMaxHealth() && wasDestroyed;
            vehicle.setVehicleModuleState(state.getId(), repairedHealth, destroyed);
            journal.recordModuleGain(state.getId(), repairedHealth - previousHealth, wasDestroyed);
        }
    }

    private void repairEngine(float amount, VehicleActionTransactionJournal journal) {
        repairLegacyModule(BvpVehicleModules.MAIN_ENGINE, amount, journal);
        repairLegacyModule(BvpVehicleModules.SUB_ENGINE, amount, journal);
    }

    private void repairTracks(float amount, VehicleActionTransactionJournal journal) {
        repairTrack(true, amount, journal);
        repairTrack(false, amount, journal);
    }

    private void repairTrack(boolean left, float amount, VehicleActionTransactionJournal journal) {
        repairLegacyModule(left ? BvpVehicleModules.LEFT_TRACK : BvpVehicleModules.RIGHT_TRACK, amount, journal);
    }

    private void repairLegacyModule(ResourceLocation id, float amount, VehicleActionTransactionJournal journal) {
        VehicleModuleState state = vehicle.getVehicleModuleState(id);
        if (state == null) {
            return;
        }
        float repairedHealth = Math.min(state.getMaxHealth(), state.getHealth() + amount);
        boolean destroyed = repairedHealth < state.getMaxHealth() && state.getDestroyed();
        vehicle.setVehicleModuleState(id, repairedHealth, destroyed);
        journal.recordModuleGain(id, repairedHealth - state.getHealth(), state.getDestroyed());
    }

    private void setTrackModuleHealth(boolean left, double health) {
        vehicle.setVehicleModuleHealth(left ? BvpVehicleModules.LEFT_TRACK : BvpVehicleModules.RIGHT_TRACK, health);
    }

    private void setEngineModuleHealth(double health) {
        vehicle.setVehicleModuleHealth(BvpVehicleModules.MAIN_ENGINE, health);
        vehicle.setVehicleModuleHealth(BvpVehicleModules.SUB_ENGINE, health);
    }

    private boolean hasDamagedEngine() {
        return isDamaged(vehicle.getVehicleModuleState(BvpVehicleModules.MAIN_ENGINE))
                || isDamaged(vehicle.getVehicleModuleState(BvpVehicleModules.SUB_ENGINE));
    }

    private boolean hasDamagedTracks() {
        return isDamaged(vehicle.getVehicleModuleState(BvpVehicleModules.LEFT_TRACK))
                || isDamaged(vehicle.getVehicleModuleState(BvpVehicleModules.RIGHT_TRACK));
    }

    private boolean hasDamagedGenericGroup(int group) {
        for (VehicleModuleState state : vehicle.getVehicleModuleStates()) {
            if (isGenericModuleState(state) && isGenericGroup(normalizedId(state), group) && isDamaged(state)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasDestroyedGenericGroup(int group) {
        for (VehicleModuleState state : vehicle.getVehicleModuleStates()) {
            if (isGenericModuleState(state) && isGenericGroup(normalizedId(state), group)
                    && state.getDestroyed()) {
                return true;
            }
        }
        return false;
    }

    public float genericGroupHealth(int group, float healthyFallback) {
        float health = healthyFallback;
        for (VehicleModuleState state : vehicle.getVehicleModuleStates()) {
            if (isGenericModuleState(state) && isGenericGroup(normalizedId(state), group)) {
                health = Math.min(health, state.getHealth());
            }
        }
        return health;
    }

    public boolean hasWeaponsSystemsModules() {
        if (hasGenericGroupState(REPAIR_TARGET_WEAPONS)) {
            return true;
        }
        for (ArmorBox box : ArmorProfiles.get(vehicle.getArmorProfileId()).moduleBoxes) {
            String moduleId = normalizeModuleId(box.module.isBlank() ? box.name : box.module);
            if (isGenericGroup(moduleId, REPAIR_TARGET_WEAPONS)) {
                return true;
            }
        }
        return false;
    }

    public boolean hasAmmoRackModules() {
        return hasGenericGroupState(REPAIR_TARGET_AMMO)
                || !ArmorProfiles.get(vehicle.getArmorProfileId()).ammoRacks.isEmpty();
    }

    private boolean hasGenericGroupState(int group) {
        for (VehicleModuleState state : vehicle.getVehicleModuleStates()) {
            if (isGenericModuleState(state) && isGenericGroup(normalizedId(state), group)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isGenericModuleState(VehicleModuleState state) {
        return BertsVehiclePack.MODID.equals(state.getId().m_135827_()) && !isLegacyAlias(state.getId());
    }

    private static boolean isLegacyAlias(ResourceLocation id) {
        return id.equals(BvpVehicleModules.LEFT_TRACK) || id.equals(BvpVehicleModules.RIGHT_TRACK)
                || id.equals(BvpVehicleModules.MAIN_ENGINE) || id.equals(BvpVehicleModules.SUB_ENGINE);
    }

    private static String normalizedId(VehicleModuleState state) {
        return state.getId().m_135815_().replace('/', ':');
    }

    private static boolean isGenericGroup(String moduleId, int group) {
        return switch (group) {
            case REPAIR_TARGET_WEAPONS -> isWeaponsSystemsModule(moduleId);
            case REPAIR_TARGET_AMMO -> isAmmoRackModule(moduleId);
            default -> false;
        };
    }

    private static boolean isDamaged(VehicleModuleState state) {
        return state != null && (state.getDestroyed() || state.getHealth() < state.getMaxHealth() - HEALTH_EPSILON);
    }

    private void disableEngineMobility() {
        vehicle.setPower(0.0F);
        vehicle.setTargetSpeed(0.0D);
    }

    private void stopAllMovementInput() {
        vehicle.setForwardInputDown(false);
        vehicle.setBackInputDown(false);
        vehicle.setLeftInputDown(false);
        vehicle.setRightInputDown(false);
        vehicle.setTargetSpeed(0.0D);
        vehicle.setPower(0.0F);
        Vec3 motion = vehicle.m_20184_();
        vehicle.m_20256_(new Vec3(0.0D, motion.f_82480_, 0.0D));
    }

    private static boolean isWeaponsSystemsModule(String moduleId) {
        return VehicleModuleHealth.WEAPONS_SYSTEMS_ID.equals(moduleId)
                || moduleId.startsWith(VehicleModuleHealth.WEAPONS_SYSTEMS_ID + ":");
    }

    private static boolean isAmmoRackModule(String moduleId) {
        return "ammorack".equals(moduleId) || moduleId.startsWith("ammorack:");
    }

    private static String normalizeModuleId(String moduleId) {
        String raw = moduleId == null ? "" : moduleId.trim().toLowerCase(Locale.ROOT);
        if (raw.startsWith("ammorack:") || raw.startsWith("ammo_rack:") || raw.startsWith("ammo-rack:")) {
            return "ammorack:" + compactModuleToken(raw.substring(raw.indexOf(':') + 1));
        }
        int separator = raw.indexOf(':');
        if (separator > 0) {
            String base = compactModuleToken(raw.substring(0, separator));
            if (base.equals("weaponssystem") || base.equals("weaponssystems") || base.equals("weaponsystem")) {
                return VehicleModuleHealth.WEAPONS_SYSTEMS_ID + ":"
                        + compactModuleToken(raw.substring(separator + 1));
            }
        }
        String normalized = compactModuleToken(raw);
        return switch (normalized) {
            case "left", "ltrack", "trackleft", "leftwheel", "leftwheels" -> "lefttrack";
            case "right", "rtrack", "trackright", "rightwheel", "rightwheels" -> "righttrack";
            case "tracks" -> "track";
            case "engines", "mainengine", "subengine", "motor", "motors", "powerpack" -> "engine";
            case "ammo", "ammoracks", "ammorack", "ammunitionrack" -> "ammorack";
            case "weaponssystem", "weaponssystems", "weaponsystem" -> VehicleModuleHealth.WEAPONS_SYSTEMS_ID;
            default -> normalized;
        };
    }

    private static String compactModuleToken(String token) {
        return token == null
                ? ""
                : token.trim().toLowerCase(Locale.ROOT).replace("-", "").replace("_", "").replace(" ", "");
    }

    private static double normalizedModuleDamage(double damageAmount) {
        if (!Double.isFinite(damageAmount)) {
            return Double.MAX_VALUE;
        }
        return Math.max(0.0D, damageAmount);
    }

    private VehicleModuleState state(ResourceLocation id) {
        return vehicle.getVehicleModuleState(id);
    }

    private float health(ResourceLocation id, float fallback) {
        VehicleModuleState state = state(id);
        return state == null ? fallback : state.getHealth();
    }

    private boolean destroyed(ResourceLocation id) {
        VehicleModuleState state = state(id);
        return state != null && state.getDestroyed();
    }

    private static float maxHealthFor(String moduleId) {
        if (isAmmoRackModule(moduleId)) {
            return (float) VehicleModuleHealth.AMMO_RACK_HP;
        }
        if (isWeaponsSystemsModule(moduleId)) {
            return (float) VehicleModuleHealth.WEAPONS_SYSTEMS_HP;
        }
        return (float) VehicleModuleHealth.GENERIC_MODULE_HP;
    }
}
