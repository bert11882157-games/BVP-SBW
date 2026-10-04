# Ground SAMs guided by Fire From Above

`GroundSamLauncher` (SBW, `api/vehicle/weapon`) lets a ground vehicle's missile weapon launch Fire From Above (FFA)
interceptors. It uses the same FFA calls as the aircraft missiles: `AircraftMissileHooks.updateLock` and `launch`,
and `MobileRadarHooks` for the radar.

## Data

Each vehicle has one file, `data/<namespace>/sbw/ground_sams/<vehicle>.json`. The file id must equal the vehicle's
entity type id.

```json
{
  "Schema": 1,
  "Radar": {"Range": 1200},
  "Weapons": {
    "Missile": {
      "Name": "9M311",
      "WithoutLock": "NATIVE",
      "Guidance": {"Mode": "SEMI_ACTIVE_RADAR", "LockTicks": 0, "Range": 800, "ConeDegrees": 30,
                   "CountermeasureVulnerability": 0.6},
      "Flight": {"InitialSpeed": 1.0, "MaxSpeed": 5.0, "AccelerationPerTick": 0.25, "TurnDegreesPerSecond": 70,
                 "Damage": 50, "BlastRadius": 5, "TntEquivalentKg": 4.62}
    }
  }
}
```

- **`Radar`** is optional. When present, the vehicle is registered as an FFA mobile radar while it is alive:
  - it provides search and track coverage;
  - FFA's vehicle radar HUD shows contacts to the crew;
  - anti-radiation missiles can home on it.

  Radar modes (`ACTIVE_RADAR`, `SEMI_ACTIVE_RADAR`) need this coverage to lock.
- **`Guidance` and `Flight`** follow the aircraft store rules in `AircraftMissileLauncher.guidance`. `INFRARED` needs
  `LockTicks` ≥ 1.
- **`WithoutLock`** sets what happens when the weapon is fired without a lock:
  - `NATIVE` fires the weapon's own SBW projectile;
  - `REFUSE` rejects the shot with "no lock" on the action bar.

## Behaviour

1. **Locking.** While the gunner has the weapon selected, the launcher bore (the weapon's shoot vector) searches FFA's
   airborne contacts every tick. The action bar shows SEARCH, ACQUIRING n% or LOCK, and a lock tone plays.
2. **Launch.** Firing with a lock launches the FFA interceptor from the weapon's muzzle. It still goes through the
   normal SBW shot transaction, so ammo, reload, recoil and fire sounds are unchanged. SBW stamps the TNT charge on
   the interceptor (`ExternalMunitionBlasts`).
3. **Who can lock.** FFA admits only the vehicle's first passenger as the operator. If another crew member boarded
   first, the HUD says so.
4. **Without FFA.** Nothing changes, and the weapon fires its native projectile.

## Vehicles

| Vehicle | Weapon | Mode | Without lock |
|---|---|---|---|
| tunguska, 9k22_tunguska | 9M311 | SEMI_ACTIVE_RADAR proxy for radio command guidance; 1RL144 radar 1200 | NATIVE (optical command-guided missile) |
| gaz_3937_vodnik_aa | 9M336 | INFRARED, 40-tick dwell | REFUSE |
| lav_ad (SBW) | FIM-92 | INFRARED, 60-tick dwell (AIM-92 tuning) | REFUSE |
| lav_ad (BVP, `berts_vehicle_pack:lav_ad`) | FIM-92, 2 × 4 ready on the Blazer turret | INFRARED, 60-tick dwell, as SBW's | REFUSE |

The Vodnik and LAV-AD no longer carry SBW's native `SeekWeaponInfo`, so the two lock systems cannot disagree.

The interceptor uses FFA's default SAM model. FFA's aircraft missile model list has no 9M311, 9M336 or FIM-92.

## Test

`/bvp_sam <player> <weapon> <target type> <distance> <height>` is a diagnostic behind `bvp.diagnostics.scenarios`.

1. The player rides the SAM vehicle as its first occupant.
2. A target is summoned ahead of the vehicle, held aloft and drifting sideways.
3. The player's view, and so the turret, is turned onto it.
4. The weapon fires when locked.
5. The `[BVP sam]` log lines follow the lock, the interceptors and the target's health.
