package com.yourname.berts_vehicle_pack.entity.helicopter;

/** No-world checks of when a two-seat helicopter's movable gun is held at boresight. */
public final class HelicopterGunStowPolicyTest {
    public static void main(String[] args) {
        int checks = 0;
        // no gun or no controlling seat: never stowed
        check(!HelicopterGunStowPolicy.stowed(false, 1, true, true, true, false, false), "no gun");
        check(!HelicopterGunStowPolicy.stowed(true, -1, false, false, false, false, false), "no controller seat");
        checks += 2;
        // empty gunner seat: stowed, the pilot fires it straight ahead
        check(HelicopterGunStowPolicy.stowed(true, 1, false, false, false, false, false), "empty gunner seat");
        // AI gunner keeps native auto-aim
        check(!HelicopterGunStowPolicy.stowed(true, 1, true, false, false, true, true), "mob gunner");
        // gunner on another weapon is not steering the gun
        check(HelicopterGunStowPolicy.stowed(true, 1, true, true, false, false, false), "gunner on missiles");
        // AH-64: gunner steering; pilot firing the gun follows it
        check(!HelicopterGunStowPolicy.stowed(true, 1, true, true, true, false, true), "AH-64 shared gun");
        // AH-1F / Mi-24: the pilot selecting the gun stows it even while the gunner aims
        check(HelicopterGunStowPolicy.stowed(true, 1, true, true, true, true, true), "pilot took the gun");
        check(!HelicopterGunStowPolicy.stowed(true, 1, true, true, true, true, false), "pilot on rockets");
        // Ka-50: the pilot is the controller; his own profile owns the neutral policy
        check(!HelicopterGunStowPolicy.stowed(true, 0, true, true, true, true, true), "single-seat pilot gun");
        checks += 7;
        check(HelicopterGunStowPolicy.firesThroughTurret("Barrel"), "barrel");
        check(!HelicopterGunStowPolicy.firesThroughTurret("Vehicle"), "pod gun");
        check(HelicopterGunStowPolicy.firesThroughStation("WeaponStationBarrel"), "station");
        check(!HelicopterGunStowPolicy.firesThroughStation("Barrel"), "turret is not station");
        checks += 4;
        check(AuthoredHelicopter.pilotFiresTurretStowed("ah_1f"), "AH-1F pilot fires the turret stowed");
        check(AuthoredHelicopter.pilotFiresTurretStowed("mi_24a"), "Mi-24A");
        check(AuthoredHelicopter.pilotFiresTurretStowed("mi_24d"), "Mi-24D");
        check(!AuthoredHelicopter.pilotFiresTurretStowed("ah_64d"), "AH-64D pilot fires where the sight points");
        check(!AuthoredHelicopter.pilotFiresTurretStowed("ch_46e"), "unarmed");
        checks += 5;
        System.out.println("PASS helicopter gun stow policy: " + checks + " assertions");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
