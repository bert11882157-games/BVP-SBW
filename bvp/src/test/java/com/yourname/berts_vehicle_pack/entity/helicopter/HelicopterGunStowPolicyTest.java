package com.yourname.berts_vehicle_pack.entity.helicopter;

/** No-world checks of when a two-seat helicopter's movable gun is held at boresight. */
public final class HelicopterGunStowPolicyTest {
    public static void main(String[] args) {
        int checks = 0;
        // no gun or no controlling seat: never stowed
        check(!HelicopterGunStowPolicy.stowed(false, 1, true, true, false), "no gun");
        check(!HelicopterGunStowPolicy.stowed(true, -1, false, false, false), "no controller seat");
        checks += 2;
        // empty gunner seat: stowed, the pilot fires it straight ahead
        check(HelicopterGunStowPolicy.stowed(true, 1, false, false, false), "empty gunner seat");
        check(HelicopterGunStowPolicy.stowed(true, 1, false, true, false), "empty gunner seat, pilot firing");
        // a crewed gunner seat keeps the gun on the gunner's sight whatever either crew member has selected
        check(!HelicopterGunStowPolicy.stowed(true, 1, true, false, false), "gunner aboard, nobody firing");
        check(!HelicopterGunStowPolicy.stowed(true, 1, true, false, true), "gunner firing the gun");
        // the pilot firing the shared gun while the gunner is not: stowed, the pilot's burst goes forward
        check(HelicopterGunStowPolicy.stowed(true, 1, true, true, false), "pilot firing the shared gun");
        // both firing: the gunner keeps the gun
        check(!HelicopterGunStowPolicy.stowed(true, 1, true, true, true), "both firing");
        // Ka-50: the pilot is the controller; his own profile owns the neutral policy
        check(!HelicopterGunStowPolicy.stowed(true, 0, true, true, false), "single-seat pilot gun");
        check(!HelicopterGunStowPolicy.stowed(true, 0, true, false, false), "single-seat pilot idle");
        checks += 8;
        check(HelicopterGunStowPolicy.firesThroughTurret("Barrel"), "barrel");
        check(HelicopterGunStowPolicy.firesThroughTurret("Turret"), "turret");
        check(!HelicopterGunStowPolicy.firesThroughTurret("Vehicle"), "pod gun");
        check(HelicopterGunStowPolicy.firesThroughStation("WeaponStationBarrel"), "station");
        check(!HelicopterGunStowPolicy.firesThroughStation("Barrel"), "turret is not station");
        checks += 5;
        System.out.println("PASS helicopter gun stow policy: " + checks + " assertions");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
