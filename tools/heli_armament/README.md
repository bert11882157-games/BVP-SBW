# Helicopter suspended armament

Generates the pylons, launchers, pods and munitions of the ten armed helicopters from the War Thunder loadouts.
Every WT weapon slot becomes one Single mount on its pylon. Each mount carries exactly the presets the game allows
on that slot: the same launcher, the same round count and the same munition.

| Script | What it does |
| --- | --- |
| `extract_wt.py` | Pulls each unit's slots from the WT datamine and writes `wt_slots.json`. Needs network access. |
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

Missiles leave the launcher one at a time, and each launcher shows its remaining rounds. Rocket and gun pods fire
from their own tube mouths or muzzles through one native channel per pod, so the left and right pods alternate.

## The texture (owner step)

All the generated models share one 64×64 palette texture. It is an LFS file, which this environment can't push.
To add it, run `build.py --texture PATH`, or take the copy in the project files at `heli-armament/heli_stores.png`,
and commit it through LFS at

```
bvp/src/generated/resources/assets/berts_vehicle_pack/textures/aircraft_stores/heli_stores.png
```

## Known simplifications

* The S-24 is drawn on its rail and stays on it after firing.
* Each helicopter has one weapon seat for guided stores, so on two-seat helicopters the gunner fires the AAMs too.
* WT's limits on weight imbalance between the left and right pylons are not enforced.
* The AH-1G's turret M195 is retired. Its 20 mm now comes from the XM35 store on slot 2, fired by the pilot.
* `tools/heli_stores` (the older pipeline) is superseded. Only `comps.py` is still used, to find the baked bones.
