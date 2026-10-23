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
python3 tools/heli_armament/camera.py                  # chase cameras and crew sights, after build.py
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
| AGM-114K/B Hellfire, APKWS | Laser, designated by the firing crew member's sight | Pilot and gunner |
| TOW / TOW-2, HOT 3, 9M114 Shturm, 9M120 Ataka, 9M17 Falanga, 9K121 Vikhr | SACLOS, flown to the firing crew member's crosshair | Pilot and gunner |
| FIM-92 Stinger (ATAS), 9K38 Igla, R-60 / R-60M, PARS 3 LR (Trigat) | Infrared, locked before launch (one lock per channel, shared by the crew) | Pilot and gunner |
| Hydra, FFAR, S-5, S-8, S-13, S-24, gun pods, bombs | Unguided | Pilot and gunner |
| Built-in and turret guns | Aimed by the gunner's sight, fixed forward for the pilot | Pilot and gunner |

Owner's rule (2026-10-20): on every armed two-seat helicopter both crew members may select and fire every munition,
and both may select the same weapon at once. Without a `crew` map, `build.py` gives the unguided stores to
the seats `[0, weapon_seat]` and the guided stores to `[weapon_seat, 0]` (a single-seat helicopter keeps its one seat). A spec's `crew` map can still override it per helicopter:
`{'unguided': [seats], 'guided': [seats], 'guns': {gun: [owner, *extra seats]}}`. The first guided seat is the
`WeaponSeat` (it must equal `weapon_seat`); any further guided seats are written as `WeaponSeats`.

A weapon both seats can fire is still one physical weapon, and its fire rate holds whoever fires it:

* The cadence (fire-rate credits), heat and overheat are kept per weapon, not per seat
  (`VehicleWeaponScheduler`). Two seats holding the trigger on one gun fire it at its own rate, not twice it, and
  handing it from one seat to the other keeps its cooldown and heat. Each seat sees the gun firing in its own HUD
  only while it holds the trigger itself.
* Ammunition and reload are the gun's own (`gunDataMap`), so both seats draw on one magazine.
* Store release (missiles, rockets, bombs) keeps its per-mount release interval and its per-vehicle pod order and
  cursor, so the second seat cannot release a store early. A semi-automatic weapon held by both seats fires again only
  after both release the trigger.
* An IR or radar seeker locks once per channel. The lowest occupied seat that has the channel selected drives the lock;
  the other seat sees and fires on the same lock. A seat leaving drops only the locks no one else is using.
* Selecting a weapon the other seat also has selected does not withdraw it from that seat.
* The movable mount follows the gunner's sight whatever either crew member has selected, like a ground vehicle
  turret. When both fire the turret gun the gunner's aim wins. It stows to its forward boresight only while the gunner
  seat is empty, or while the pilot is firing the gun and the gunner is not (the pilot's burst goes forward, and the
  gun goes back to the gunner's sight when he lets go).

Every guided seat has the fixed-wing store keys (I loadout view, L fire store, ] cycle, middle mouse designate); only
the pilot edits the loadout. Gun pods appear as one selectable group on the pilot's seat and on any other seat that
lists all of their channels (now every gunner seat). A built-in gun bank fires as one weapon from the pilot seat only
when every gun in it is hull-fixed (`Vehicle` / `WeaponStationBarrel`); a turret gun (`Barrel`) fires on its own
channel from either seat. Weapon selection is the same as on aircraft and ground vehicles:
number key N selects the Nth weapon the seat can currently fire (empty pylon channels are skipped), on every seat, and
the mouse wheel cycles the same list. `build.py` also drops `WeaponPairs` entries (HUD primary/secondary metadata)
that name a weapon the vehicle or that seat no longer has.

| Helicopter | Unguided | Guided | Shared gun | Basis |
| --- | --- | --- | --- | --- |
| AH-64D | pilot, gunner | gunner, pilot | M230 | Either crew member can slave the gun to his helmet and fire any weapon (Wikipedia, Boeing AH-64 Apache) |
| AH-1F | pilot, gunner | gunner, pilot | M197 | The pilot fires the turret gun stowed forward; the gunner has the TOW sight |
| AH-1G | pilot, gunner | pilot, gunner | M134 + M129 (M28 turret) | The gunner aims the M28 with the pantograph sight; the pilot fires it stowed forward and fires the wing stores |
| Tiger UHT | pilot, gunner | gunner, pilot | none | Gunner-operated sight for HOT/PARS; either seat fires the rockets |
| Mi-24V | pilot, gunner | gunner, pilot | 9A624 / YakB | The pilot can fire the nose gun locked forward; the operator aims it (VSPU-24) |
| Mi-24A, Mi-24D | pilot, gunner | gunner, pilot | A-12.7 / YakB | As the Mi-24V |
| Mi-28N | pilot, gunner | gunner, pilot | 2A42 | The WSO aims the gun; the pilot fires it fixed forward |

Ka-50 and AH-6J have one weapon seat (the AH-6J's second seat is a passenger seat), so the pilot fires everything.

Missiles leave the launcher one at a time, and each launcher shows its remaining rounds. Rocket and gun pods fire
from their own tube mouths or muzzles through one native channel per pod, so the left and right pods alternate.

## Cameras

`camera.py [--root DIR] [vid ...]` runs last (after `tools/cockpit_models/helicopters.py` and `build.py`) and is
idempotent. It writes the third-person chase cameras (`AircraftCameraPos` of the aircraft-camera seats,
`ThirdPersonCameraPos`) and the crew sights. `--root` works as in `build.py`.

A sight is the view the crew member who aims the guided weapons gets while holding the vehicle zoom: the camera
moves from his eye in the cockpit (the head view, `EyeAttachment`, left free to look all round) to the real sensor,
and the zoomed look is held inside the sensor's field of regard. Per helicopter `SIGHTS` adds a `Vehicle`-parented
attachment at the sensor window (about 0.1 ahead of its glass, measured on `custom_geo/<vid>.geo.json`), points the
seat's `ZoomEyeAttachment` / `ZoomDirectionAttachment` at it and sets `CameraPos.ZoomYawRange` / `ZoomPitchRange`
([min, max] degrees from the airframe's forward axis, yaw positive right, pitch positive up; `VehicleEntity` applies
them while the zoom is held). A helicopter seat whose zoom eye differs from its eye gets the `EMPLACEMENT_OPTIC` aim
role (`ArmoredVehicleEntity`), so the zoomed camera really moves there; the hull weapon-station gunners already had a
seat-authored zoom. The station's `CameraAttachment` (`gunner_optic`) stays on the gun, as the station requires, and
the guns keep their own traverse limits.

| Helicopter | Seat | Sight | Position (x, y, z) | Yaw | Pitch | Basis |
| --- | --- | --- | --- | --- | --- | --- |
| AH-64D | CPG | TADS nose turret | 0, 1.67, 5.15 | ±120 | -60/+30 | published TADS field of regard |
| AH-1F | gunner | M65 TSU in the nose tip | 0, 1.75, 7.2 | ±110 | -60/+30 | commonly given TSU figures (approx.) |
| AH-1G | gunner | pantograph reflex sight, 0.3 ahead of his eye | 0, 3.17, 4.55 | ±107 | -50/+17.5 | M28 turret coverage (approx.) |
| Mi-24V | operator | Raduga-Sh, port chin (model has no fairing) | 0.3, 0.92, 6.42 | ±60 | -60/+20 | USPU-24 coverage (judgement) |
| Mi-24D | operator | Raduga-F pod, starboard chin | -0.62, 0.78, 8.1 | ±60 | -60/+20 | USPU-24 coverage (judgement) |
| Mi-24A | operator | KPS-53AV sight head on his panel | 0.15, 3.08, 7.85 | ±60 | -60/+20 | A-12.7 mount coverage (judgement) |
| Mi-28N | operator | sight ball under the nose drum, above the gun | 0, 2.75, 6.76 | ±110 | -60/+20 | gun turret azimuth (judgement) |
| Tiger UHT | gunner | Osiris mast-mounted sight | 0, 6.88, 0.55 | ±120 | -45/+30 | judgement (over the rotor disc) |
| Ka-50 | pilot | Shkval nose window | 0, 1.85, 7.5 | ±35 | -80/+15 | published Shkval field of regard |

AH-6J, CH-46E and Mi-26 have no weapon-operating crew seat apart from the pilot and get no sight.

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
| GShG-7.62 (`gungshg_guv`) | Mi-24V (GUV-8700, two guns) | Default: AP, API-T |
| FN M3P (`gunfn_m3p`, `_cwp`) | Tiger (HMP400, RMP-LC) | Default: M20 API-T, 2 M2 AP, M1 I · Universal: 2 M8 AP-I, M20 API-T, 2 M23 I · Ground targets: M20, M23, 2 M2, 2 M8 · Tracers: M20 · Stealth: M8, M23, M8, M23 |
| GAU-19 (`gungau_19`) | AH-6J | Default: SLAP, SLAP-T · Ground targets: SLAP-T, 3 SLAP |
| M134 (`gunm134_minigun`) | AH-1G (M28 turret, XM18), AH-6J | Default: 6 AP, T |
| M129 40 mm (no WT gun file) | AH-1G (M28 turret) | M384 HEDP, from the BMP-2M grenade launcher profile, 7.91 velocity |

Per round: WT muzzle velocity (m/s / 16 on the guns with the fixed-wing physics, / 20 on the others), TNT equivalent
(20 mm and up), penetration at 10 to 2000 m (de Marre with drag, or WT's armour power table), and the damage numbers
of `tools/damage/balance.py`. Tracers: WT's traced rounds, coloured by the nation rule (`tools/ballistics/tracer_nations.py`).
30 mm HE flies at the APDS velocity where the helicopter has the APDS (owner's rule, `autocannon_he_velocity.py`).
The tools in `tools/ballistics` leave these guns alone (`wt_belts.HELI_ARMAMENT`). Each GUV-8700 pod has three
channels: its YaK-B from the first muzzle and its two GShG-7.62 guns (1800 rounds, 6000 rpm) from the other two.
Gun pods use WT's rate of fire and the store's own magazine: XM18 1500 rds / 4000 rpm, M134D 4000 rpm, GAU-19
2000 rpm, FN M3P / RMP 1025 rpm, XM35 M195 850 rpm, GSh-23L 3400 rpm, YaK-B 4500 rpm.

## Generated models are marked "(AI)"

Every store whose model (or launcher) is one of the `heli_` models generated here has " (AI)" at the end of its
in-game name, and so does every weapon that fires from one: the guided rounds flown on a `heli_` flight model and
the native rocket and gun channels of the `heli_` pods. That is bert's rule (2026-10-23), so generated models can be
told apart from human-made ones. Stores built on the pack's own fixed-wing models (AGM-114, Falanga, R-60,
PARS 3 rounds) keep their names. A lang-key name is resolved to its English text before the mark is added.
`build.py` refuses to finish if any store showing a `heli_` model lacks the mark.

## The texture

All the generated models share one 64×64 palette texture, an LFS file at

```
bvp/src/generated/resources/assets/berts_vehicle_pack/textures/aircraft_stores/heli_stores.png
```

`build.py --texture PATH` writes a fresh copy; commit it through LFS if the palette changes.

## Known simplifications

* The S-24 is drawn on its rail and stays on it after firing.
* Guided stores follow the spec's `crew` map; without one, the weapon seat and the pilot fire them all, including AAMs.
* WT's limits on weight imbalance between the left and right pylons are not enforced.
* The AH-1G's 20 mm comes from the XM35 store on slot 2. Its M28 chin turret (M134 + M129, `turret_guns` and
  `turret_model` in the spec) is drawn by `catalog.m28_turret` / `m28_guns` into the airframe's `turret` / `barell`
  bones. Its traverse (±107°, -50/+21°) and the M129's 40 mm round (one HEDP belt) are estimates; WT has no M129.
* `tools/heli_stores` (the older pipeline) is superseded. Only `comps.py` is still used, to find the baked bones.
