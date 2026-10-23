package com.yourname.berts_vehicle_pack.entity.helicopter;

/**
 * When a two-seat helicopter's movable gun is held stowed at boresight (straight ahead) instead of following the
 * gunner's sight. The gun behaves like a ground vehicle turret: it follows whoever sits at the gunner's sight, player
 * or AI, whatever weapon either crew member has selected. The pilot can fire the gunner's gun too, so it stows only:
 * <ul>
 *     <li>while no one sits at the gunner's sight (the pilot fires it fixed forward);</li>
 *     <li>while the pilot is firing the shared gun and the gunner is not (the pilot's burst goes forward; the gun
 *     returns to the gunner's sight when the pilot lets go, or as soon as the gunner fires it himself).</li>
 * </ul>
 * A helicopter whose pilot is the gun's own controller (Ka-50) owns its neutral policy through its aim profile and is
 * never stowed while it is crewed.
 */
public final class HelicopterGunStowPolicy {
    private HelicopterGunStowPolicy() {
    }

    public static boolean stowed(boolean channelPresent, int controllerSeat, boolean controllerPresent,
                                 boolean pilotFiresChannelGun, boolean controllerFiresChannelGun) {
        if (!channelPresent || controllerSeat < 0) return false;
        if (!controllerPresent) return true;
        if (controllerSeat == 0) return false;
        return pilotFiresChannelGun && !controllerFiresChannelGun;
    }

    /** Whether a weapon firing through {@code shootTransform} is carried by the given aim channel's gun. */
    public static boolean firesThroughTurret(String shootTransform) {
        return "Barrel".equals(shootTransform) || "Turret".equals(shootTransform);
    }

    public static boolean firesThroughStation(String shootTransform) {
        return "WeaponStationBarrel".equals(shootTransform) || "WeaponStation".equals(shootTransform);
    }
}
