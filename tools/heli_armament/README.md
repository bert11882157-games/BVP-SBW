# Helicopter suspended armament

Generates the pylons, launchers, pods and munitions of the ten armed helicopters from the War Thunder loadouts.
Every WT weapon slot becomes one Single mount on its pylon. Each mount carries exactly the presets the game allows
on that slot: the same launcher, the same round count and the same munition.

| Script | What it does |
| --- | --- |
| `extract_wt.py` | Pulls each unit's slots from the WT datamine and writes `wt_slots.json`, and every gun's belts and rounds to `wt_guns.json` (`--guns`: only that). Needs network access. |
| `belts.py` | Turns `wt_guns.json` into the guns' belts (AmmoType / ProjectileBeltAmmoType) and round profiles. |
| `catalog.py` | Procedural models of every launcher, pod, rail and munition (built on `meshlib.py`). |
| `helis/<vid>.py` | One file per helicopter: the WT unit, the pylon points, the stores of each slot and the baked stores to strip. |
| `build.py` | Writes the models, the store definitions, the profiles, the armaments and the vehicle channels. It also strips the baked stores out of the airframe models. |
| `preview.py` | Orthographic textured previews of an airframe with a loadout from `loadouts.json`. |

```
python3 tools/heli_armament/extract_wt.py              # only when the WT data changes
python3 tools/heli_armament/build.py --texture /tmp/heli_stores.png
python3 tools/heli_armament/preview.py ah_64d /tmp/ah64.png --loadout mixed --views front,quarter
```

`build.py` is idempotent. Running it again over its own output gives the same files.

`build.py --root DIR` writes into `DIR/{assets,data}/berts_vehicle_pack/...` instead of `bvp/src/generated/resources`,
so a scratch copy of the generated tree can be built and diffed. `build.py` only rewrites the seats' `Weapons`, the
keys in a spec's `gun` map and the armament documents; it leaves attachments, seat eyes and the cockpit rig alone.

## Sources

* **Slots and presets** come from the [War Thunder datamine](https://github.com/gszabi99/War-Thunder-Datamine),
  `aces.vromfs.bin_u/gamedata/flightmodels/<unit>.blkx`, and the weapon presets it references. Some mod vehicles
  have no exact WT twin, so they use the nearest unit: AH-1G uses `ah_1g`, AH-6J uses `ah_6m` and the Tiger uses
  `tiger_uht`. Every other vehicle has a unit of the same name.
* **Pylon points** were measured on each airframe model, at the bottom of the pylon fairing or the lug position of
  the stores that used to be baked into the model. The comment at the top of each `helis/<vid>.py` says which.
* **Launcher and munition dimensions** come from the public figures for each item: length, calibre, tube count and
  spacing, and fin span. The values are given in each builder's docstring in `catalog.py`.

## Guidance and firing

| Munitions | Guidance | Who fires |
| --- | --- | --- |
| AGM-114K/B Hellfire, APKWS | Laser, designated by the gunner's sight | Gunner |
| TOW / TOW-2, HOT 3, 9M114 Shturm, 9M120 Ataka, 9M17 Falanga, 9K121 Vikhr | SACLOS, flown to the gunner's crosshair | Gunner |
| FIM-92 Stinger (ATAS), 9K38 Igla, R-60 / R-60M, PARS 3 LR (Trigat) | Infrared, locked before launch | Gunner |
| Hydra, FFAR, S-5, S-8, S-13, S-24, gun pods, bombs | Unguided | Pilot |

The table gives the default. A spec's `crew` map overrides it per helicopter:
`{'unguided': [seats], 'guided': [seats], 'guns': {gun: [owner, *extra seats]}}`. The first guided seat is the
`WeaponSeat` (it must equal `weapon_seat`); any further guided seats are written as `WeaponSeats`. A shared gun is one
physical weapon: it keeps one magazine, the scheduler lets only one seat drive it at a time, and when the pilot fires
it while the gunner is not steering, the mount stows to its forward boresight.

| Helicopter | Unguided | Guided | Shared gun | Basis |
| --- | --- | --- | --- | --- |
| AH-64D | pilot, gunner | gunner, pilot | M230 | Either crew member can slave the gun to his helmet and fire any weapon (Wikipedia, Boeing AH-64 Apache) |
| AH-1F | pilot, gunner | gunner | M197 | The pilot fires the turret gun stowed forward; the gunner has the TOW sight |
| AH-1G | pilot, gunner | pilot | none | The pilot fires the wing stores; the gunner could also release them |
| Tiger UHT | pilot, gunner | gunner | none | Gunner-operated sight for HOT/PARS; either seat fires the rockets |
| Mi-24V | pilot | gunner | 9A624 / YakB | The pilot can fire the nose gun locked forward; the operator aims it (VSPU-24) |
| Mi-24A, Mi-24D | pilot | gunner | A-12.7 / YakB | As the Mi-24V |
| Mi-28N | pilot | gunner | 2A42 | The WSO aims the gun; the pilot fires it fixed forward |

Missiles leave the launcher one at a time, and each launcher shows its remaining rounds. Rocket and gun pods fire
from their own tube mouths or muzzles through one native channel per pod, so the left and right pods alternate.

## Gun belts

Every built-in gun and every gun pod carries the belts War Thunder gives that gun on that helicopter, in WT's order
(Default first), with WT's round sequence, round for round. Sources (datamine, `aces.vromfs.bin_u/`):
`gamedata/weapons/<gun>.blkx` (the top-level `bullet` list is the default belt, each `<gun>_<purpose>` block a named
belt), `gamedata/flightmodels/<unit>.blkx` `modifications` (which named belts the unit has),
`gamedata/damage_model/explosive.blkx` (TNT strength equivalents) and `lang.vromfs.bin_u/lang/units_modifications.csv`
/ `units_weaponry.csv` (belt and round names; a belt with no name of its own takes the generic purpose name).

| Gun (WT file) | Helicopters | Belts |
| --- | --- | --- |
| 2A42 (`cannon2a42`) | Ka-50, Mi-28N | Default: AP-T, HEF-I · Ground targets: APDS, AP-T, 2 HEF-I · Armored targets: APDS · Air targets: HEF-T, AP-T, 2 HEF-I |
| M230E-1 (`cannonm230e1`) | AH-64D | Default: HEDP (WT has no other belt and no APDS for it) |
| M197 (`cannonm197`) | AH-1F | Default: HEI-T, 2 HEF-I, AP-I · Universal: HEI-T, AP-I, HEF-I, AP-I · Air targets: HEI-T, 2 HEF-I, AP-I · Armored targets: HEI-T, 3 AP-I · Stealth: HEF-I, AP-I, HEF-I, AP-I |
| M195 (`cannonm195`, XM35) | AH-1G | Default: API-T, HEF-I, API-T · Air targets: 3 HEF-I, API-T · Armored targets: 3 API-T, HEF-I |
| GSh-23L (`cannongsh_23l_upk_23_250`) | Ka-50, Mi-28N (UPK-23) | Default: HEFI-T, AP-I, HEF-I · AP: HEFI-T, 3 AP-I · Air targets: HEFI-T, HEF-I, HEFI-T, HEF-I, AP-I · Stealth: AP-I, 2 HEF-I |
| GSh-23L (`cannongsh_23l`) | Mi-24A/D/V (UPK-23) | as above, but AP: HEF-I, 3 AP-I |
| YaK-B (`gunyak_b`) | Mi-24V, Mi-24D | Default: AP-I, API-T, 4 AP-I · Universal: 2 AP-I, 3 IAI, API-T · Ground targets: IAI, 3 AP, API-T, AP · Air targets: IAI, API-T, 4 IAI |
| A-12.7 (`guna_12_7`) | Mi-24A | Default: AP-I, API-T, AP-I · Universal: 2 AP-I, IAI, API-T · Ground targets: IAI, AP, API-T, AP · Air targets: IAI, API-T, 2 IAI |
| YaK-B (`gunyak_b_guv`) | Mi-24V (GUV-8700) | Default: 4 AP, API-T, 3 AP |
| FN M3P (`gunfn_m3p`, `_cwp`) | Tiger (HMP400, RMP-LC) | Default: M20 API-T, 2 M2 AP, M1 I · Universal: 2 M8 AP-I, M20 API-T, 2 M23 I · Ground targets: M20, M23, 2 M2, 2 M8 · Tracers: M20 · Stealth: M8, M23, M8, M23 |
| GAU-19 (`gungau_19`) | AH-6J | Default: SLAP, SLAP-T · Ground targets: SLAP-T, 3 SLAP |
| M134 (`gunm134_minigun`) | AH-1G (XM18), AH-6J | Default: 6 AP, T |

Per round: WT muzzle velocity (m/s / 16 on the guns with the fixed-wing physics, / 20 on the others), TNT equivalent
(20 mm and up), penetration at 10 to 2000 m (de Marre with drag, or WT's armour power table), and the damage numbers
of `tools/damage/balance.py`. Tracers: WT's traced rounds, coloured by the nation rule (`tools/ballistics/tracer_nations.py`).
30 mm HE flies at the APDS velocity where the helicopter has the APDS (owner's rule, `autocannon_he_velocity.py`).
The tools in `tools/ballistics` leave these guns alone (`wt_belts.HELI_ARMAMENT`). The GUV's two 7.62 mm GShG guns are
not a channel of their own; the pod fires its YaK-B.

## The texture

All the generated models share one 64×64 palette texture, an LFS file at

```
bvp/src/generated/resources/assets/berts_vehicle_pack/textures/aircraft_stores/heli_stores.png
```

`build.py --texture PATH` writes a fresh copy; commit it through LFS if the palette changes.

## Known simplifications

* The S-24 is drawn on its rail and stays on it after firing.
* Guided stores follow the spec's `crew` map; without one, the single `weapon_seat` fires them all, including AAMs.
* WT's limits on weight imbalance between the left and right pylons are not enforced.
* The AH-1G's turret M195 is retired. Its 20 mm now comes from the XM35 store on slot 2, fired by the pilot.
* `tools/heli_stores` (the older pipeline) is superseded. Only `comps.py` is still used, to find the baked bones.
