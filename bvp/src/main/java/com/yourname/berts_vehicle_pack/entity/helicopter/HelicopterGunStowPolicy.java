package com.yourname.berts_vehicle_pack.entity.helicopter;

/**
 * When a two-seat helicopter's movable gun is held stowed at boresight (straight ahead) instead of following the
 * gunner's sight. The pilot can fire the gunner's gun on every two-seat helicopter, so the gun must point somewhere
 * defined whenever the gunner is not steering it:
 * <ul>
 *     <li>no one at the gunner's sight: stowed, the pilot fires it fixed forward;</li>
 *     <li>the gunner has another weapon selected (a missile, the rockets): not steering, stowed;</li>
 *     <li>a pilot-stow helicopter (AH-1, Mi-24, Mi-28: the pilot fires the turret locked forward) whose pilot has the
 *     gun selected: the pilot has taken the gun, stowed;</li>
 *     <li>otherwise the gunner steers it and the pilot's rounds go where it points (AH-64: the gun follows the
 *     sight that is slaved to it).</li>
 * </ul>
 * An AI gunner keeps the native auto-aim. A helicopter whose pilot is the gun's own controller (Ka-50) owns its
 * neutral policy through its aim profile and is never stowed while it is crewed.
 */
public final class HelicopterGunStowPolicy {
    private HelicopterGunStowPolicy() {
    }

    public static boolean stowed(boolean channelPresent, int controllerSeat, boolean controllerPresent,
                                 boolean controllerIsPlayer, boolean controllerSteersChannel,
                                 boolean pilotStowsForPilotFire, boolean pilotHoldsChannelGun) {
        if (!channelPresent || controllerSeat < 0) return false;
        if (!controllerPresent) return true;
        if (!controllerIsPlayer) return false;
        if (!controllerSteersChannel) return true;
        return controllerSeat != 0 && pilotStowsForPilotFire && pilotHoldsChannelGun;
    }

    /** Whether a weapon firing through {@code shootTransform} is carried by the given aim channel's gun. */
    public static boolean firesThroughTurret(String shootTransform) {
        return "Barrel".equals(shootTransform) || "Turret".equals(shootTransform);
    }

    public static boolean firesThroughStation(String shootTransform) {
        return "WeaponStationBarrel".equals(shootTransform) || "WeaponStation".equals(shootTransform);
    }
}
