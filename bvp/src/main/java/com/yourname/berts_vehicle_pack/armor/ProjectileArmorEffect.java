package com.yourname.berts_vehicle_pack.armor;

import com.atsuishio.superbwarfare.entity.projectile.CannonShellEntity;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

final class ProjectileArmorEffect {
    private static final double AMMO_RACK_INSTANT_DETONATION_CHANCE_PER_DAMAGE = 0.08D;

    final ArmorDamageType damageType;
    final double penetrationMm;
    final CannonShellEntity shell;
    final boolean atgm;
    final boolean tandemWarhead;
    final ImpactVisual impactVisual;

    private final double defaultModuleDamage;
    private final Map<String, Double> moduleDamageById;

    final double vehicleDamage;
    /**
     * Typed (profiled) rounds: the chance, per ammo rack the shot crosses, that the rack goes up at once (the
     * profile's AmmoRackDamage in per mille). Independent of how full the racks are. Negative for the legacy
     * effects, whose chance is their ammo-rack damage x {@value #AMMO_RACK_INSTANT_DETONATION_CHANCE_PER_DAMAGE}.
     */
    final double ammoRackChance;
    /**
     * Dual-aspect rounds (SAPHEI-T): the HE charge as its own chemical effect, with the profile's HE penetration
     * and HE damage. This effect is then the kinetic body. Null for every single-aspect round.
     */
    final ProjectileArmorEffect heAspect;

    ProjectileArmorEffect(ArmorDamageType damageType, double penetrationMm, CannonShellEntity shell,
                          double defaultModuleDamage) {
        this(damageType, penetrationMm, shell, false, defaultModuleDamage, Collections.emptyMap());
    }

    ProjectileArmorEffect(ArmorDamageType damageType, double penetrationMm, CannonShellEntity shell,
                          double defaultModuleDamage, Map<String, Double> moduleDamageById) {
        this(damageType, penetrationMm, shell, false, defaultModuleDamage, moduleDamageById);
    }

    ProjectileArmorEffect(ArmorDamageType damageType, double penetrationMm, CannonShellEntity shell,
                          boolean atgm, double defaultModuleDamage, Map<String, Double> moduleDamageById) {
        this(damageType, penetrationMm, shell, atgm, defaultModuleDamage, moduleDamageById, -1.0D);
    }

    ProjectileArmorEffect(ArmorDamageType damageType, double penetrationMm, CannonShellEntity shell,
                          boolean atgm, double defaultModuleDamage, Map<String, Double> moduleDamageById,
                          double vehicleDamage) {
        this(damageType, penetrationMm, shell, atgm, defaultModuleDamage, moduleDamageById,
                vehicleDamage, false, ImpactVisual.NONE, false, -1.0D, null);
    }

    private ProjectileArmorEffect(ArmorDamageType damageType, double penetrationMm, CannonShellEntity shell,
                                  boolean atgm, double defaultModuleDamage, Map<String, Double> moduleDamageById,
                                  double vehicleDamage, boolean tandemWarhead, ImpactVisual impactVisual,
                                  boolean overridesNormalized, double ammoRackChance,
                                  ProjectileArmorEffect heAspect) {
        this.damageType = damageType;
        this.penetrationMm = penetrationMm;
        this.shell = shell;
        this.atgm = atgm;
        this.tandemWarhead = tandemWarhead;
        this.impactVisual = impactVisual;
        this.defaultModuleDamage = defaultModuleDamage;
        this.moduleDamageById = overridesNormalized ? moduleDamageById : normalizeOverrides(moduleDamageById);
        this.vehicleDamage = vehicleDamage;
        this.ammoRackChance = ammoRackChance;
        this.heAspect = heAspect;
    }

    double moduleDamage() {
        return defaultModuleDamage;
    }

    ProjectileArmorEffect withDirectDamageScale(double factor) {
        if (factor == 1.0D) return this;
        Map<String, Double> overrides = new HashMap<>();
        moduleDamageById.forEach((id, damage) -> overrides.put(id, damage * factor));
        return new ProjectileArmorEffect(damageType, penetrationMm, shell, atgm,
                defaultModuleDamage * factor, overrides,
                vehicleDamage < 0.0D ? vehicleDamage : vehicleDamage * factor,
                tandemWarhead, impactVisual, true, ammoRackChance,
                heAspect == null ? null : heAspect.withDirectDamageScale(factor));
    }

    double moduleDamage(String moduleId) {
        String normalized = normalizeModuleId(moduleId);
        Double override = moduleDamageById.get(normalized);
        if (override == null && normalized.startsWith(ArmorModuleResolver.AMMO_RACK + ":")) {
            override = moduleDamageById.get(ArmorModuleResolver.AMMO_RACK);
        }
        return override == null ? defaultModuleDamage : override;
    }

    /** Damage to an ammo rack module the shot crosses without setting it off: the round's module damage. */
    double ammoRackDamage() {
        return ammoRackChance >= 0.0D ? defaultModuleDamage : moduleDamage(ArmorModuleResolver.AMMO_RACK);
    }

    double ammoRackInstantDetonationChance() {
        if (ammoRackChance >= 0.0D) {
            return Math.min(1.0D, ammoRackChance);
        }
        return Math.max(0.0D, Math.min(1.0D,
                moduleDamage(ArmorModuleResolver.AMMO_RACK) * AMMO_RACK_INSTANT_DETONATION_CHANCE_PER_DAMAGE));
    }

    /** The typed per-rack detonation chance (0..1); see {@link #ammoRackChance}. */
    ProjectileArmorEffect withAmmoRackChance(double chance) {
        return new ProjectileArmorEffect(this.damageType, this.penetrationMm, this.shell, this.atgm,
                this.defaultModuleDamage, this.moduleDamageById, this.vehicleDamage,
                this.tandemWarhead, this.impactVisual, true, Math.max(0.0D, Math.min(1.0D, chance)), this.heAspect);
    }

    ProjectileArmorEffect withPenetration(double newPenetrationMm) {
        return new ProjectileArmorEffect(this.damageType, Math.max(0.0D, newPenetrationMm),
                this.shell, this.atgm, this.defaultModuleDamage, this.moduleDamageById,
                this.vehicleDamage, this.tandemWarhead, this.impactVisual, true, this.ammoRackChance, this.heAspect);
    }

    ProjectileArmorEffect withVehicleDamage(double newVehicleDamage) {
        return new ProjectileArmorEffect(this.damageType, this.penetrationMm, this.shell, this.atgm,
                this.defaultModuleDamage, this.moduleDamageById, newVehicleDamage,
                this.tandemWarhead, this.impactVisual, true, this.ammoRackChance, this.heAspect);
    }

    /**
     * Replaces the typed armor-effect damage basis without touching penetration, shell behavior,
     * impact presentation, or any other module-specific overrides.  The ammo-rack override is
     * explicit because the normalized small-caliber policies intentionally use a different rack
     * amount from their ordinary-module amount.
     */
    ProjectileArmorEffect withArmorDamage(double newModuleDamage, double newAmmoRackDamage,
                                          double newVehicleDamage) {
        Map<String, Double> overrides = new HashMap<>(this.moduleDamageById);
        overrides.put(ArmorModuleResolver.AMMO_RACK, newAmmoRackDamage);
        return new ProjectileArmorEffect(this.damageType, this.penetrationMm, this.shell, this.atgm,
                newModuleDamage, overrides, newVehicleDamage,
                this.tandemWarhead, this.impactVisual, true, this.ammoRackChance, this.heAspect);
    }

    ProjectileArmorEffect withImpactVisual(ImpactVisual newImpactVisual) {
        return new ProjectileArmorEffect(this.damageType, this.penetrationMm, this.shell, this.atgm,
                this.defaultModuleDamage, this.moduleDamageById, this.vehicleDamage,
                this.tandemWarhead, newImpactVisual, true, this.ammoRackChance, this.heAspect);
    }

    ProjectileArmorEffect withTandemWarhead() {
        return new ProjectileArmorEffect(this.damageType, this.penetrationMm, this.shell, this.atgm,
                this.defaultModuleDamage, this.moduleDamageById, this.vehicleDamage, true, this.impactVisual, true, this.ammoRackChance, this.heAspect);
    }

    /** Attaches {@code charge} as this kinetic body's HE aspect (see {@link #heAspect}). */
    ProjectileArmorEffect withHeAspect(ProjectileArmorEffect charge) {
        return new ProjectileArmorEffect(this.damageType, this.penetrationMm, this.shell, this.atgm,
                this.defaultModuleDamage, this.moduleDamageById, this.vehicleDamage,
                this.tandemWarhead, this.impactVisual, true, this.ammoRackChance,
                charge == null ? null : charge.withoutHeAspect());
    }

    /** The kinetic body alone, e.g. after explosive reactive armor has spent the HE charge. */
    ProjectileArmorEffect withoutHeAspect() {
        if (heAspect == null) return this;
        return new ProjectileArmorEffect(this.damageType, this.penetrationMm, this.shell, this.atgm,
                this.defaultModuleDamage, this.moduleDamageById, this.vehicleDamage,
                this.tandemWarhead, this.impactVisual, true, this.ammoRackChance, null);
    }

    /**
     * The HE charge of a dual-aspect round as a chemical effect: its own penetration, hull, module and rack damage,
     * with this round's shell and presentation.
     */
    ProjectileArmorEffect chemicalAspect(double chargePenetrationMm, double chargeModuleDamage,
                                         double chargeVehicleDamage, double chargeAmmoRackChance) {
        Map<String, Double> overrides = new HashMap<>();
        overrides.put(ArmorModuleResolver.AMMO_RACK, chargeModuleDamage);
        return new ProjectileArmorEffect(ArmorDamageType.CHEMICAL, Math.max(0.0D, chargePenetrationMm), this.shell,
                false, chargeModuleDamage, overrides, chargeVehicleDamage, false, this.impactVisual, false,
                Math.max(0.0D, Math.min(1.0D, chargeAmmoRackChance)), null);
    }

    /**
     * The aspect that decides a hit on {@code armorMm} of effective armor: the HE charge when it penetrates (its HE
     * damage), otherwise this kinetic body (its own penetration and AP damage, which may still fail).
     */
    ProjectileArmorEffect aspectAgainst(double armorMm) {
        return heAspect != null && heAspect.penetrationMm + 1.0E-4D >= armorMm ? heAspect : this;
    }

    Map<String, Double> moduleDamageOverrides() {
        return moduleDamageById;
    }

    private static Map<String, Double> normalizeOverrides(Map<String, Double> overrides) {
        if (overrides == null || overrides.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, Double> normalized = new HashMap<>();
        for (Map.Entry<String, Double> entry : overrides.entrySet()) {
            if (entry.getValue() != null) {
                normalized.put(normalizeModuleId(entry.getKey()), entry.getValue());
            }
        }
        return Collections.unmodifiableMap(normalized);
    }

    private static String normalizeModuleId(String moduleId) {
        return moduleId == null
                ? ""
                : moduleId.trim().toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
    }

    enum ImpactVisual {
        NONE,
        BULLET,
        HMG,
        AUTOCANNON_AP,
        AUTOCANNON_HE,
        APFSDS,
        HEAT_FS,
        HE,
        ATGM
    }
}
