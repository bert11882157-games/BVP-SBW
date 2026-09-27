# War Thunder belts, tracers, velocities and HE filler

`tools/ballistics/wt_belts.py` rebuilds the autocannon and HMG belts of ground vehicles and helicopters from War
Thunder data. Run it with `--check` for a dry run. A second run reports `nothing to change`.

## Sources

- The War Thunder datamine (gszabi99/War-Thunder-Datamine):
  - `gamedata/weapons/groundmodels_weapons/*.blkx` gives the belt bullet order, `speed`, `explosiveMass`,
    `explosiveType` and `visual.tracer`.
  - `config/gameparams.blkx` `tracerColors` gives the tracer RGB. It is stored as BGRA.
- TNT equivalent = `explosiveMass` × strength equivalent, from `gamedata/damage_model/explosive.blkx`: A-IX-2 1.54,
  Hexal 1.70, Torpex 1.60, Octol 1.59, PETN 1.70, JHL-3 1.54.
- The wiki.warthunder.com unit pages give the belt names.

## What the tool writes

- **One `AmmoType` entry per WT belt.**
  - Its `Ammo` is the belt's selector identity: one of `superbwarfare:small_shell_ap/aa/gs/he`, distinct per belt.
  - Belts that share a starting round are rotated to start at another phase of their cycle. The order of the cycle
    is unchanged.
- **One `ProjectileBeltAmmoType` entry per round the belts use.**
  - A round the weapon lacks is copied from another weapon that has it, together with its profile file:
    `belt_ammo_wt_<round>.json`.
  - bmp2 and btr80a gain 3UBR6 and 3UOF8.
  - zbd_09 gains DTC10-30.
  - The Marders gain DM43 HVAP-T, built from DM63 with WT's APCR values: 57 mm penetration at 10 m.
- **Per round:**
  - `Velocity`, from the WT muzzle velocity.
  - `TntEquivalentKg`. Inert rounds have none.
  - `Tracer`, per shot in the belt. Non-tracer rounds are `NONE`.
  - Weapon-level `Velocity` follows the first belt's first round.
- **User rule:** 30 mm HE (3UOF8, 3UOR6, DTY02-30) flies at the gun's APDS velocity (3UBR8 / DTC04), not at WT's.

## Tracer colours (`ProjectileBeltTracer`)

| Enum | RGB | Used by |
|---|---|---|
| RED | 255,32,32 | 3UBR6 AP-T, 23 mm BZT, M792, DTC10, 12.7 mm BZT-44, 14.5 mm KPVT BZT, PKT/Type 86 API-T |
| BRIGHT_RED | 255,60,20 | 3UOR6 HEF-T, 40 mm slsgr m/90 |
| DARK_RED | 210,0,0 | 3UBR8 APDS, DTC04-30 APDS |
| LIGHT_RED | 255,144,132 | Rh202 (DM43, DM51A1, DM63), 35 mm KDA, 23 mm OFZT |
| WHITE | 213,237,255 | M791, M919, PMB090, 40 mm APFSDS |
| PINK | 255,65,154 | PKTM (BMP-2M coax) |
| GREEN | 72,255,96 | Soviet/Chinese aircraft cannons (not rebuilt by this tool) |

`BvpProjectilePolicies.tracerColor` maps the enum to RGB. RED and GREEN keep the pack's established tones.

## Weapons covered

- **2A42:** bmp2, bmp2m, bmpt (both guns), btr_90 and others in `WEAPONS`.
- **2A72:** btr80a. **BMP-3 belts:** bmp3m_elite. **2A38:** tunguska. **Ka-50 / Mi-28N:** helicopter 2A42 belts.
- **2A14:** zu23_2, zsu23_4.
- **M242:** m2_bradley, lav25 (+M919). **M811:** vbci.
- **Rh202:** marder_1a1/1a2/1a5.
- **KDA:** gepard. **Bofors L/70:** cv9040_no_net. **ZPT-99:** qn_506model, zbd_09.

The tracer-only fixes (12.7 mm, 14.5 mm and 7.62 mm families) edit the existing rounds in place.

## Data bugs fixed by the rebuild

- bmp2 and btr80a 3UOR6 velocity was 15.
- The cv9040 and zsu23_4 rounds had no velocity and fell back to 18 (360 m/s).
- Soviet 12.7 mm and 14.5 mm tracers were green.

## Notes

- `tools/tnt/apply_tnt.py` has a stale inventory (su_17 and saab_105 rows) and is not part of any pipeline.
  wt_belts.py is the source for belt-round TNT.
- The 14.5 mm KPVT BZT tracer colour is inferred from the 12.7 mm BZT-44 (same Soviet red composition). It was not
  checked against the KPVT's own datamine entry.
