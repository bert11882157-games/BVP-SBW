package com.yourname.berts_vehicle_pack.armor;

public final class VehicleModuleHealth {
    // Damage normalization 2026-09-28: engine, weapon systems and every ammo rack 100, the launcher tube 30.
    // A round's ModuleDamage (0.6 x its hull damage, at most 100) goes to each module it crosses.
    public static final double AMMO_RACK_HP = 100.0D;
    public static final double ENGINE_HP = 100.0D;
    public static final double TRACK_HP = 40.0D;
    public static final double WEAPONS_SYSTEMS_HP = 100.0D;
    public static final double LAUNCHER_HP = 30.0D;
    public static final double GENERIC_MODULE_HP = 10.0D;
    public static final String WEAPONS_SYSTEMS_ID = "weaponsystems";

    private VehicleModuleHealth() {
    }
}
