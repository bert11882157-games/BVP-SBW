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
  - it matches a `dropRules` box (|x|, y, z bounds).
- **The rest of the airframe goes into the template's wreck sections.** Wings go by part and side, the fuselage by
  `fuselageCuts` (geo z), and the control surfaces into their template bones.
- **The template's gear, gear wheels, control stick and modeled store assemblies stay.** They are textured through
  an atlas: the template texture on top and the source texture below.
- **Crew:** extra seats and eye attachments come from the spec. `removeClientKeys` drops, for example, a template
  `CanopyGlass` that does not fit the new canopy.

Sound events stay the template's. Only exact ids and asset paths are renamed.

| id | source | template | notes |
|---|---|---|---|
| `f_16b` | F-16B.mtb | f_16c | Scale 0.8076, aligned by nose and gear. 345 baked store elements are dropped (texture rows < 200 outside the cockpit). Front eye at the F-16B front seat (z −41.5, 4 px behind the F-16C's); rear seat and eye added (z −18.5, 0.8 px higher). The template canopy glass is removed because the model has its own. Flight reference: base 9,300 kg, fuel 2,750 kg. |
| `f_15e` | F-15E.mtb | f_15c | Span and wing trailing edge match the template exactly (scale 0.7763). The baked stores are dropped: texture rows below 128 outside the cockpit and outer wing panels, plus the wing tanks and the centerline tank; the conformal tanks stay. The front eye matches the F-15C's; the WSO seat and eye are added at the model's rear seat. Flight reference: F-15E masses and F100-PW-229 thrust. |
| `a_10` (replacement) | A-10C.mtb | a_10 (originals in `replaced/a_10`) | Span and chord match the old model (scale 0.794). Stores on texture rows 1024–1151, the left ECM pod and the centerline pod are dropped. The template gear and turbine fans are kept. The template canopy glass is removed. |

Replacing an aircraft in place (`template` = `id`):
- The original files are copied to `tools/vehgen/replaced/<id>/` on the first run, and every later run reads the
  template from there.
- Registrations are left as they are.
