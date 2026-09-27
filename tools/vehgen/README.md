# vehgen: lean vehicle generator

Replaces the 17k-line `build_berts_vehicle_pack.mjs` for new vehicles. It never runs the old generator. It writes
the repo outputs directly, so later repo passes stay untouched: `aircraft_unmirror`, `vehicle_scale`,
`aircraft_attach`, canopy, cockpit and texture passes. It also never touches `work/armor_hitbox_authoring`.

```
python3 tools/vehgen/vehgen.py tools/vehgen/specs/<id>.json [--sources DIR] [--write]
```

Without `--write` it only prints the seats, attachments and files it would write. Re-running it is idempotent.

## Frames

| frame | axes | units |
|---|---|---|
| toolbox (.mtb) | +x forward, +y down, +z vehicle left | toolbox units |
| geo (custom_geo) | +X vehicle left, +Y up, nose −Z | px, 16 per block |
| data (sbw/vehicles, seats, OBB) | +X vehicle left, +Y up, nose +Z | blocks |
| armor plates (`frame: hull`) | geo axes | blocks |

- **toolbox → geo:** `[z·s, −y·s + yOff, −x·s] + zShift`.
  - `yOff` puts the lowest source point (tyre bottoms) on the ground, Y = 0.
  - `zShift` puts `anchor.parts` (the rear axle) on the template's geo z. The pack's wheeled vehicles pivot about
    the rear axle, near the entity origin.
- **geo → data:** `[x/16, y/16, −z/16]`.
- The toolbox → geo map is a reflection. Faces are re-wound counter-clockwise, seen from outside their element,
  rather than trusting the source winding.
- Sources modelled nose −x need `yaw180` in the Frame.
- Seat/Gun records in .mtb files use a per-file frame, so they are only hints. Every functional point in the spec
  is measured on the converted geometry.

## Spec

| field | meaning |
|---|---|
| `template` | An existing vehicle of the same kind. Its data, client model definition, turret-wreck geo and bone hierarchy (names kept, e.g. `barell`) are cloned. |
| `bones` | Template bone ← toolbox part numbers. Template bones not listed in `bones`, `pivots` or `keepBones` are dropped. |
| `pivots` | Bone pivots, as a point or `{"part": n, "at": "rotation"}` (the part's element rotation point). |
| `seats` | Where the rider entity's feet go, in model px (geo before the z shift). A seated player's hips are 12 px (0.75 block) above that and the eye 25.9 px (1.62 blocks) above it. `"frame": "WeaponStation"` stores it relative to the station pivot. |
| `attachments` | Camera, muzzle, grips and scope. `VehicleCustomPitch` is absolute; `WeaponStationBarrel` is relative to the pitch pivot. |
| `obbSections` | Hull collision boxes: `[z0, z1, yMin]` slices of the hull mesh. Station, barrel and wheel boxes are automatic. |

## Driver cameras (ground vehicles)

The driver's eye is placed where a seated driver's eye actually is: the seat point + 1.62 blocks, above the
hips and behind the steering wheel. It may move at most 1–2 px forward or up for visibility. It must look through
the windshield opening, under the top frame and over the wheel and hood.

Check it with the first-person render in `scratchpad/fpview.py`: straight ahead, 15° down, and 40° left/right.

## Vehicles

| id | source | template | notes |
|---|---|---|---|
| `uaz_469_spg9` | UAZ-469_SPG-9.mtb | toyota_jihad_spg9 | Scale 1.15 px/unit: width equals the Toyota's, and its length/real-length ratio matches (1.43). Left-hand drive; the driver's eye is at 37.4 px, under the windshield bar at 41.2. Four crew seats: driver, commander, gunner standing left of the SPG-9 sight, loader on the right ammo bench. |
| `lav25` (replacement) | ModelLAV25.mtb | lav25 (itself) | Scale 0.8856, which keeps the old in-game length of 7.64 blocks. The wheel midpoint is now at the origin; the old model sat 1.1 blocks ahead of its collision boxes and terrain probes. The engine box is front-right, where the armor already placed the engine. Scouts sit in the rear compartment. The gunner/controller camera sits just over the left periscope head. |

Replacements use the vehicle itself as the template:
- Re-running is idempotent. Every derived field is recomputed.
- Armor is mapped hull box → hull box and turret box → turret box.
- Old versions stay in git history.

## Aircraft variants (`aircraft_variant.py`)

A new aircraft of an existing family takes the template's complete data (flight reference, handling, armor,
armaments, modeled stores, OBBs, surface modules, rig, afterburner outlets). The provided model is **aligned** to
the template, so that data stays valid:
- **Scale:** the `lengthParts` extent is matched to the `templateLengthBones` extent.
- **Longitudinal:** the nose tips coincide.
- **Vertical:** the ground (`groundParts`, the source's own gear), or with `alignY: templateBottom` the fuselage
  bottom when the template gear is kept and is shorter.

Then:
- **Baked stores are dropped.** An element goes when either rule holds:
  - its texture row is below `storeTextureV` and it is outside the `keepBoxes` (the cockpit);
  - it matches a `dropRules` box (|x|, y, z bounds; `parts` limits a box to some source parts).
- **The rest of the airframe goes into the template's wreck sections.** Wings go by part and side, the fuselage by
  `fuselageCuts` (geo z), and the control surfaces into their template bones.
- **The template's gear, gear wheels, control stick and modeled store assemblies stay.** They are textured through
  an atlas: the template texture on top and the source texture below.
- **Part rules** (`parts`): a bone name, `wing`, `fuselage`, `side:<left bone>,<right bone>`, `drop`, `{bone, if, else}`, or an ordered list of `{bone, if}` where the first match wins (the fallback is `fuselage`). `if` takes `zMin/zMax/yMin/yMax/absXMin/absXMax/xMin/xMax` on the element centre in geo px.
- **Client overrides:** `removeClientKeys` drops keys; `clientSet` sets dotted paths (e.g. `AfterburnerPresentation.Outlets` for a single-engine type on a twin-engine template). `armamentOverrides` replaces armament keys (a different pylon layout or store set).
- **Crew:** extra seats and eye attachments come from the spec. `removeClientKeys` drops, for example, a template
  `CanopyGlass` that does not fit the new canopy.

- **Swing wings** (`sweeps`, also `sweeps.py <id> '<json>'` for aircraft that are not regenerated): the sweep bone
  pivot (with its wreck child) and the client `AircraftRig.Sweeps` entry change together, because the rig animator
  rejects a mismatch. A sweep turns about +Y (`[0, 1, 0]`); `AngleSign` −1 on the left wing sweeps the tip aft.

- **Flight reference:** after `flightReference` overrides, `flightref.derive` recomputes the full-fuel mass, the wing
  area (from the full-fuel wing loading), the installed thrust and the afterburner ratio. `flightref.py` mirrors the
  entity-side validation: a flight reference that fails it makes the aircraft impossible to spawn.
- **After generating a new aircraft**, run `tools/aircraft_attach/attach.py apply <id>` so the pylon stations sit
  on the new wing. A partial run leaves shared rack adapters alone; `--shared` rewrites them from the listed carriers
  only.

Sound events stay the template's. Only exact ids and asset paths are renamed.

| id | source | template | notes |
|---|---|---|---|
| `f_16b` | F-16B.mtb | f_16c | Scale 0.8076, aligned by nose and gear. 345 baked store elements are dropped (texture rows < 200 outside the cockpit). Front eye at the F-16B front seat (z −41.5, 4 px behind the F-16C's); rear seat and eye added (z −18.5, 0.8 px higher). The template canopy glass is removed because the model has its own. Flight reference: base 9,300 kg, fuel 2,750 kg. |
| `f_15e` | F-15E.mtb | f_15c | Span and wing trailing edge match the template exactly (scale 0.7763). The baked stores are dropped: texture rows below 128 outside the cockpit and outer wing panels, plus the wing tanks and the centerline tank; the conformal tanks stay. The front eye matches the F-15C's; the WSO seat and eye are added at the model's rear seat. Flight reference: F-15E masses and F100-PW-229 thrust. |
| `a_10` (replacement) | A-10C.mtb | a_10 (originals in `replaced/a_10`) | Span and chord match the old model (scale 0.794). Stores on texture rows 1024–1151, the left ECM pod and the centerline pod are dropped. The template gear and turbine fans are kept. The template canopy glass is removed. |
| `su_57` (replacement) | Su-57 source model | su_57 (originals in `replaced/su_57`) | Scale 0.7731, aligned by length (nose to tail) and the source gear. The template gear, gear wheels, stick and suspended stores are kept. The tail parts split into the rudder and elevator bones; the wing parts are split by `zMin`. The template canopy glass is removed. The pilot eye has moved to the new cockpit: 2.4 px over the glare shield and 2 px under the canopy, looking through the HUD frame. The seat is at the eye minus 25.9 px. |
| `f_14a` (replacement) | F-14A.mtb | f_14a (originals in `replaced/f_14a`) | Scale 0.7602, aligned by length and the source gear; the stick, gear and seats land within about 1 px of the old model. The extended wings (parts 20/22) go into the `sweep_left/right` bones, and their roots sit on the template sweep pivots, so the swing-wing rig is unchanged. The swept copies (21/23) are dropped. The tail part is split by position into the fins (rudder bones) and the stabilators. Baked stores (texture rows 1088–1215) are dropped outside the cockpit. The RIO eye is raised over the RIO instrument panel, which the old eye looked straight into. |
| `b_1b` (replacement) | B-1B Lancer.mtb | b_1b (originals in `replaced/b_1b`) | Scale 0.7798, aligned by length and the source gear; the template gear lands within 3 px. The extended wings go into the sweep bones. The model's swept pose is the extended one turned 42° about the wing root leading edge (fit residual 0), so that point is the new pivot; range 52° (real 15–67.5°). The fin's aft strip is the rudder, and the all-moving tailplanes go into `elevator_8/9`. The pilot and copilot eyes had sat inside the seat backs; they are now 4 px in front of the headrests, 2 px over the panel. |
| `su_30` | Su-30.mtb | su_27 | Scaled nose to tail onto the Su-27 fuselage (0.802); the span comes out 5% wider. The source's own gear is in the template gear bones (the rig only shows or hides gear, so pivots do not matter) and stands on the ground. Baked stores are dropped by three boxes: the wing stations, the centreline tandem pair and the under-intake pair. Part 7 holds the rudders and the stick; parts 8/9 hold the tailplanes, a leading-edge piece and the nozzles. The pilot eye is in the front cockpit and a WSO seat is added in the stepped rear cockpit. Flight reference: Su-30 masses on the Su-27's AL-31F. The pylons were re-seated with `attach.py apply su_30` (21/31 clean). Twin rails sink 0.4 px on legacy spacing until a shared-adapter run; the under-intake fins dip up to 2.5 px into the intakes. |
| `su_24` | SU-24 Fencer.mtb | f_111f (rig) | Scale 0.792, nose to tail. The source has one wing pose, extended and baked into part 0. The single wing element per side goes into the F-111F sweep bones; the pivot is at the glove's outer edge (x 43), at 30% of the root chord there, with a 53° range (real 16–69°). The fixed glove is in the wing sections, and the source's own gear is in the gear bones. The glove pylon and its store are dropped. Side-by-side eyes are 3.5 px over the panel (the template's were level with it). Flight reference: Su-24M masses, 2× AL-21F-3, 55.2 m². It has its own pylon layout (`armamentOverrides`): glove pylons on the model's own glove pylon (13/13 clean), swivel wing pylons that follow the sweep, and forward and aft belly pairs, with Soviet stores. Without modeled pylons, the belly and wing stations clip 0.5–2 px. |
| `j_10a` | J-10A.mtb | eurofighter_typhoon | Closest in size: 16.0 m against the J-10A's 16.9 m. Scale 0.698, nose to tail, with the source's own gear. The rig surface pivots move to the J-10's hinges (`sweeps` handles any rig part): elevons on their hinge line, all-moving canards on a spanwise spindle, the rudder on its swept hinge and the stick base. There is one afterburner outlet at the J-10 nozzle (`clientSet`). The baked centreline tank and wing stores are dropped and the model's pylons are kept. The J-10A has its own pylon layout: PL-8/PL-12 outboard, PL-12 or light bombs mid, bombs inboard. The eye was above the canopy; it now sits in front of the headrest. Flight reference: one AL-31FN, 33.1 m². |
| `q_5` | Q-5 Fantan.mtb | mig_21bis | The MiG-19S, the Q-5's ancestor, is 25% shorter; the MiG-21bis matches its 15.65 m nose to tail (scale 0.753, span 17% wider). It has its own gear. Wing and belly stores are dropped; the model's pylons, fences and belly racks are kept. The stabilators go into the elevator bone, and the ailerons, which the source keeps in its tail part, go into the aileron bones. The fin has no separate rudder. Two afterburner outlets. The eye was on the spine behind the canopy; it now looks through the gunsight. It has its own pylons (bombs, rocket pods, R-13/R-60) and WP-6 flight figures. |
| `su_17` | Su-17.mtb | mig_23mld (rig) | Scale 0.706 onto the MiG-23 fuselage (12% under the real 19 m). The outer panels sweep about the pivot fitted from the model's own swept pose (39°, residual 0.03), with a 34° range (real 30–63°). The tailerons are all-moving spindles and the stick base is moved. It has its own gear and one outlet at its nozzle. The eye was inside the seat back. It has its own pylons under the fixed inner wing (Kh-25/Kh-29, KAB, FAB, pods, R-60), and the MiG-23's belly station and US-bomb bay are removed. Flight reference: Su-17M4, AL-21F-3. |
| `j_15d` | J-15D.mtb | su_30 | Two-seat carrier EW Flanker on the Su-30; same modeller and proportions (scale 0.774, since the J-15D's tail cone is longer). The baked stores (part 3 and the outer-wing pods) are dropped; the wingtip EW pods stay. Outer panels are spread; the folded copies are dropped. Both eyes are moved 3 px forward of the Su-30's to clear the headrests. It uses the Su-30 pylons without the wingtip station, with Chinese stores (PL-8/PL-12, YJ-91, KD-88). |

Replacing an aircraft in place (`template` = `id`):
- The original files are copied to `tools/vehgen/replaced/<id>/` on the first run, and every later run reads the
  template from there.
- Registrations are left as they are.
