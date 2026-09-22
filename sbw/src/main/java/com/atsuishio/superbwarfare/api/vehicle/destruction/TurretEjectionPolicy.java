package com.atsuishio.superbwarfare.api.vehicle.destruction;

/**
 * Selects how a vehicle destruction request treats its detachable turret.
 * DEFAULT preserves the vehicle's DestroyInfo chance and force exactly.
 */
public enum TurretEjectionPolicy {
    DEFAULT,
    KEEP_ATTACHED,
    FORCE_EJECT
}
