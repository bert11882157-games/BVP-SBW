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

## Tank rounds and missiles (`tools/ballistics/wt_tank_rounds.py`)

The same sources set, per round:
- penetration at 10 m (profile `Combat.PenetrationMm`; the entry's `ApDurability` follows it);
- TNT equivalent;
- muzzle velocity.

Existing values within WT's rounding are kept: TNT within 0.5 %, velocity within 1 m/s.

Values changed by the first run:
- Leopard 2A6 DM53: 500 → 653 mm (the L55 value).
- Mi-24V 9M114: 575 → 560 mm.
- M332: 1021 → 1249 m/s.
- M900, M830A1, M431 and 3BM25: small velocity corrections.

The table covers the 125/120/115/105/100/90 mm rounds in the pack plus 9M114 and 9M311. M829A1, JM33 and JM12A1 are
listed for the M1A1 and Type 90.

## Owner's belt standard (2026-09-28)

Every autocannon, aircraft guns included, has two belts:

- **Air Belt** (HE-dominant): 3× HE without tracer, then 1× AP with tracer.
- **Ground Belt** (AP-dominant): 3× the gun's best penetrator (APFSDS > APDS > HVAP > AP), then 1× HE with tracer.
  The BMP-2M's Ground Belt is WT's all-APFSDS belt (3UBR11).

Machine guns keep one belt: 3 rounds without tracer, then 1 tracer. Aircraft and helicopter guns with both HE and AP
rounds (including the "air game"/"ground game" pairs) get the Air/Ground pair.

Run order: `wt_belts.py` (ground autocannons, WT rounds), `belt_standard.py` (everything else), then
`tracer_nations.py` (colours: Eastern green, Western red). All three are idempotent and the first two apply the
nation colours themselves.

## Ground autocannons: WT belts, AP belt first (owner, 2026-10-02)

Ground-vehicle autocannons carry War Thunder's own belts again: the belt lists of the first WT rebuild
(cc37bc60), round for round in WT's order, starting at WT's first round. The default belt (the first `AmmoType`,
SBW's selected index 0) is the gun's best armour-piercing belt, so players don't have to switch:
`wt_belts.ap_first()` sorts by the best penetrator a belt carries (APFSDS > APDS > HVAP/APCR > AP), then by its
share of those shots; ties keep WT's order.

| Gun | Default belt |
|---|---|
| 2A42 / 2A72 (BMP-2, BMPT, BTR-80A, BMP-3M) | 30 mm APDS |
| 2A42 (BTR-90) | 30 mm APFSDS (3UBR11) |
| 2A42 (BMP-2M) | 30 mm APFSDS |
| 2A38 (Tunguska) | 30 mm AP-T |
| 2A14 (ZU-23-2, ZSU-23-4) | 23 mm API-T |
| M242 (Bradley) / M811 (VBCI) / M242 (LAV-25) | M791 / PMB090 / M919 |
| Rh202 (Marders) | DM63 |
| KDA (Gepard) | DM23 |
| Bofors L/70 (CV9040) | slpprj m/01 |
| ZPT-99 (QN-506, ZBD-09) | DTC10-30 |
| GAU-12/U (LAV-AD) | 2× PGU-32/U SAPHEI-T + 1× PGU-20/U HVAP (see below) |
| Bofors 57 mm L/70 (Begleitpanzer 57) | APDS |

The Begleitpanzer's 57 mm belts are not WT data: they are built in the same style from the gun's real rounds
(APDS ~1420 m/s; AP-T 1020 m/s; HE-T 1025 m/s, 0.45 kg; PFHE 1025 m/s, 0.38 kg, untraced): APDS 3:1 HE-T (default),
AP-T 3:1 HE-T, HE-T 3:1 AP-T, and all-PFHE for air targets.

A belt's selector identity (`Ammo` of its `AmmoType` entry) may be any round of the belt; SBW's belt phase is
independent of the selected entry, so the belt itself is never rotated. Helicopter 2A42s (Ka-50, Mi-28N) and all
aircraft guns keep the owner's Air/Ground standard above. The Marders' DM43 belt uses DM51A1 as its HE round.

## LAV-AD GAU-12/U (SBW vehicle, hand-authored)

The LAV-AD is an SBW vehicle (`sbw/src/main/resources/data/superbwarfare/sbw/vehicles/lav_ad.json`), so the tools
above do not touch it. Its cannon fires BVP profiles from `projectile_profiles/lav_ad/cannon/`, so it needs BVP
installed. It has one belt, War Thunder's default: 2× PGU-32/U SAPHEI-T (red tracer), then 1× PGU-20/U HVAP
(no tracer). The owner asked for this belt (2026-10-04), in place of the Air/Ground pair below.

| Round | m/s | `Velocity` | TNT kg | Penetration at 10/100/500/1000/1500/2000 m |
|---|---|---|---|---|
| PGU-32/U SAPHEI-T | 1100 | 55.0 | 0.0205 | body 31/29/20/13/9/6; HE aspect 17.5 flat |
| PGU-20/U HVAP | 1036 | 51.8 | none | 66/62/46/32/22/16 |

The wiki cannot be reached from cloud sessions, so the curves come from the datamine (the GAU-12 weapon and LAV-AD
unit files) through War Thunder's de Marre formula with drag:
`P = k·100·V^1.43·M^0.71 / (1900^1.43·(d/100)^1.07)`, with air density 1.225 and the round's drag coefficient.
This method reproduces the pack's 3UBR6, M791 and 3UBR8 curves exactly. The PGU-20 APCR fit is less certain
(effective mass = core + 0.77 × (total − core), Cx 0.36). Check it against the wiki unit page when possible.

## Dual-aspect rounds (`Combat.HeAspect`)

A SAPHEI-T profile's scalar `Combat` fields are its kinetic body: hull class APHE, AP damage, the kinetic curve.
`HeAspect {PenetrationMm, HullDamage, ModuleDamage, AmmoRackDamage}` is its HE charge. `tools/damage/balance.py`
fills it with the HE rules: the HE damage multiplier, and penetration 0.7 × calibre for autocannons below 57 mm.

BVP armor tries the charge first. If the charge penetrates, the hit deals HE damage. If only the body penetrates,
the hit deals the body's AP damage. Otherwise it is a non-penetration. A target without armor, or an exposed
module, takes the HE damage. Explosive reactive armor spends the charge, so only the body continues.
