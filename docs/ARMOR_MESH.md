# Armor meshes (Blockbench)

Armor volumes can be triangle meshes instead of boxes. A mesh plate can have any shape and any slope, and
the game measures the impact angle against the real face of the triangle the shell entered.

This page is for authoring. It covers the file format, the Blockbench workflow, how the game uses the
file, and how to start from the existing box armor.

## How the game uses armor meshes

1. **The coarse SBW vehicle OBBs still decide whether a projectile hit the vehicle.** Nothing about that
   changes.
2. When the OBBs accept a contact, BVP traces the shell ray through the armor volumes. The first volume on
   the ray is the one that was hit. The penetration angle is `|dot(shot direction, face normal)|`, and the
   face normal comes from the entered triangle. Ricochet and fragment effects use the same normal.
3. **A gap in the armor still reads "Shot missed!"** on strict profiles (`"unboxed_hits_penetrate": false`).
   The shot does not penetrate and does no damage. Every miss also writes one line to the server log, even
   with diagnostics off (the lines are rate limited). The line shows where the gap is:

   ```
   [BVP Armor] Shot missed: no armor plate on the shell ray (strict profile 't72b', mesh armor_mesh/t72b.geo.json).
   vehicle=berts_vehicle_pack:t72b#412 projectile=superbwarfare:cannon_shell projectile_profile=... obb_part=TURRET
   hit_world=(...) armor_local=(...) local_dir=(...) nearest_plate=turret_cheek_03 [turret] gap=0.183 at_ray=4.021
   turret_yaw=12.5 hull_speed=0.120 b/t (2.4 m/s)
   ```

   `armor_local` is the contact in armor-profile coordinates (blocks). `gap` is how far the shell ray
   passed from the nearest plate. To close the gap, extend that plate, or add a plate at `armor_local`.

The game picks the source of a profile's volumes like this:

| Situation | Volumes used |
|---|---|
| `bvp/src/main/resources/data/berts_vehicle_pack/armor_mesh/<profile_id>.geo.json` exists | the mesh, **per category**: every category the file defines (plates, ERA, engines, ammo, modules, tracks, internals) replaces that category's boxes; a category the file leaves out keeps its boxes |
| no mesh file | the box lists in `armor/<profile_id>.json` |
| the profile JSON sets `"armor_mesh": false` | the box lists, even if a mesh file exists |
| the profile JSON sets `"armor_mesh": "other_id"` | `armor_mesh/other_id.geo.json` (share one mesh between variants) |
| the mesh file is broken or defines no volume | the box lists, with an error in the log |

Every profile-level setting stays in `armor/<profile_id>.json`: penetration defaults, `impact_tolerance`,
`unboxed_hits_penetrate`, `strict_armor_gate`, `atgm_tandem`, and so on. The mesh file only replaces the
volumes.

### ERA: cubes or meshes

ERA bricks can be authored either way in the same file. Ordinary Blockbench **cubes** are the quickest and are
what most bricks should be (rotate them freely); a Blockbench **Mesh** works too for odd shapes. Name the bone
`era__<type>[_ke<mm>][_ce<mm>]__<name>` and keep `<name>` equal to the brick's name in the visual model
(`bvpEraSpent_<name>`) so the spent-brick visuals still hide the right brick. If you leave ERA out of the mesh
file entirely, the vehicle keeps the ERA boxes from its JSON profile, so you can convert the plates first and the
ERA later.

When a profile loads a mesh, the log shows what the mesh contains and any problems it found:

```
[BVP Armor] Profile 't72b' uses armor mesh armor_mesh/t72b.geo.json: 321 volumes (102 plates, 219 ERA, 1 engines, 4 ammo, 0 modules, 0 tracks, 0 internals), 3852 triangles, 0 warning(s).
```

## File format

An armor file is a normal Blockbench **Bedrock Entity** model (`.geo.json`, format 1.12.0). It contains
bones, and inside them Blockbench **Mesh** elements (saved as `poly_mesh`) and/or ordinary cubes.

* **Units and frame are the same as the vehicle's visual model** (`custom_geo/<profile_id>.geo.json`): 16
  units = 1 block, same origin, same axes. If you load both into Blockbench, the armor sits on the model.
* **Frame roots.** Each volume belongs to the frame of its nearest parent bone named `hull`, `turret`
  or `barrel`. The names are matched case-insensitively. An `armor_` prefix is allowed, and so are digits
  that Blockbench appends to duplicate names. So `armor_turret`, `Turret` and `turret2` all select the
  turret frame. The visual models call their barrel bone `barell`, and that name is accepted too. A volume
  with no frame parent is a hull volume.
  * The turret and barrel frames are **authored at the rest pose**, exactly where the turret and gun sit
    in the model. The game rotates them with the live turret yaw and gun elevation.
  * Pivots and rotations on the frame bones are ignored, so you can rotate `armor_turret` in Blockbench
    to preview traverse. Still, save the file at the rest pose, because the preview rotation is not part
    of the armor.
* **Volumes.** A bone named `<kind>__<param>__<name>` (two underscores between parts) is one armor volume.
  Everything inside it is the volume: its meshes, its cubes, and any child bones that don't have a volume
  name. Several mesh parts in one bone are unioned, so a plate may be made of several pieces.
  * `<name>` becomes the volume name. It is shown when a plate is hit, it identifies ERA bricks for the
    spent-brick visuals (`bvpEraSpent_<name>` bones in the visual model), and it seeds ricochet rolls.
    Keep names unique within a vehicle.
  * Blockbench allows letters, digits, `_`, `.` and `-` in bone names. Any other character is removed.

| Kind | Param | Example | Meaning |
|---|---|---|---|
| `plate` (or `armor`) | thickness in mm, `mm` optional, decimals allowed | `plate__80mm__ufp`, `plate__12.7mm__skirt_l` | armor plate |
| `era` | ERA type, optional `ke<mm>` (vs. kinetic) and `ce<mm>` (vs. chemical) | `era__kontakt5__front_03`, `era__relict_ke200_ce600__cheek_l_01` | explosive reactive armor brick |
| `engine` | none | `engine____block` | engine module |
| `ammo` | optional, e.g. `10hp` (informational: rack HP is fixed in code) | `ammo__10hp__rack_00` | ammunition rack |
| `module` | module id, add `-split` for per-volume health | `module__weaponsystems__gun`, `module__mainrotor__rotor_01` | other damageable module |
| `track` | optional (informational; the side comes from the position) | `track__left__front` | track section |
| `internal` | none | `internal____fuel` | sensitive internal (critical-hit flag). Names starting with `engine_`/`motor_`/`powerpack_` count as engines |

ERA protection defaults when `ke`/`ce` are left out: `kontakt1` 25/400, `kontakt5` 120/450, `relict`
200/600, and any other type 25/400. The templates always spell them out.

A bone name that starts with an unknown kind (for example `wheel__L`) is not armor and is ignored. That
means you may keep the vehicle model's own bones in the same file. A known kind with a bad param (for
example `plate__thick__x`) is skipped and logged.

### Mesh rules

* **Close your volumes.** A volume should be a closed solid: every edge shared by exactly two faces. The
  game fixes inconsistent face winding, and turns inside-out parts outward. It warns about parts that are
  not closed and then treats them as a zero-thickness two-sided sheet with no inside.
* Plates are usually slabs: a thin closed box or prism, tilted and cut to shape. The angle that counts is
  the angle of the face the shell enters. For a slab, that is the big outer face.
* Convex pieces are best: plain slabs, prisms and wedges. The game resolves them exactly like a box,
  including the thin "skin" that stops shells from slipping through seams between plates. Concave pieces
  work too; for those the skin is the space within the same distance of the surface.
* Use as few triangles as the shape needs. Armor never needs the visual model's detail.
* Optional `"bvp_surface_normal": [x, y, z]` on a plate's volume bone names the vehicle face the plate lines
  (outward, in the bone's geo space). A shot that only passes through a mesh plate's skin, without touching
  the solid, is scored against this face rather than whichever skin face it crossed. The generator below
  writes it on every plate. Hand-made plates may leave it out.
* **Mesh plates are ranked by their solids.** When a shot's ray crosses several mesh plates, the first
  plate whose actual solid the ray meets wins, and the skin only decides shots that miss every solid
  (grazes). The angle is taken from the face the shot really enters. So neighbouring slabs may meet at any
  angle without a later plate's grown edge winning near the seam. Box volumes keep their old skin-first
  ranking.

### Rotations

Mesh elements are saved with their rotation already applied, so rotate meshes freely. Rotations on
volume bones and cubes are applied the way Blockbench displays them. Only frame bones ignore rotation, as
explained above.

## Blockbench workflow

Stock Blockbench cannot put Mesh elements into Bedrock models. Install the **Meshy** plugin once
(*File → Plugins → Available*, search "Meshy"). With it, Bedrock Entity models can hold Mesh elements and
read and write `poly_mesh`. The vehicle models in `custom_geo/` use the same format.

### Start from a template (recommended)

Every registered BVP vehicle (171: tanks, IFVs, trucks, helicopters, aircraft, tripods) has a file:

* `tools/armor_mesh/templates/<profile_id>.armor.geo.json`: armor only. For the 61 vehicles with box armor
  today it holds every box as a closed 6-face mesh with its original name. For the other 110 it holds
  **starter** volumes made from the vehicle's collision boxes, named `plate__10mm__starter_<part>_NN`.
  They are placeholders to reshape, split and re-thickness, not real armor values.
* `<profile_id>.armor_edit.geo.json` (in the `armor_edit` folder on your PC): the same armor **plus the
  vehicle's visual model** under a `reference_model` bone (its bones renamed `ref_*`), so one file opens with
  the vehicle and its armor already lined up. The game ignores the reference bones, so this file also works as
  the armor file; it is just bigger. Before saving for the game you can hide the reference from the export
  (step 3) to keep the file small.

The game never loads templates or edit files from where they are; only files copied into `armor_mesh/` count.

1. Open the edit file (or the template): *File → Open Model*.
2. (Template only.) To see the vehicle, open `bvp/src/generated/resources/assets/berts_vehicle_pack/custom_geo/<profile_id>.geo.json`
   in a second tab and save it once as a Blockbench project (*File → Save Project*, `.bbmodel`). Back in the
   armor tab, merge it in with *File → Import → Import Project*. The template uses the roots `armor_hull`,
   `armor_turret` and `armor_barrel`, so they don't clash with the model's `hull`, `turret` and `barell`.
3. Lock the imported vehicle groups, and turn off their **Export** toggle (outliner advanced toggles:
   the "More Options" button above the outliner). That keeps the vehicle out of the armor file. The game
   would ignore those bones, but they make the file large.
4. Edit the armor:
   * Reshape a plate by moving its mesh vertices. Blockbench's mesh tools (extrude, knife, merge
     vertices) all work.
   * Replace a group of stacked boxes with one mesh, and delete the old volume bones.
   * Add a plate: create a group under `armor_hull`/`armor_turret`/`armor_barrel`, name it
     `plate__<mm>mm__<name>`, and add a Mesh inside it.
5. Save: *File → Export → Export Bedrock Geometry* to
   `bvp/src/main/resources/data/berts_vehicle_pack/armor_mesh/<profile_id>.geo.json`.
6. Start the game and check the log line above for warnings. Turn on the armor X-ray (`F3+B`, or the
   xray command) to see the mesh volumes. They use the same colours as boxes: thickness from green to red,
   the last hit plate in red, and spent ERA in red.

To go back to boxes, delete the mesh file or set `"armor_mesh": false` in the profile JSON.

### Author inside the vehicle model instead

You can also add armor bones straight into a copy of the vehicle model: put `plate__…` groups under its
`hull`, `turret` and `barell` bones, and save the copy as the armor file. The loader reads only volume
bones and ignores the visual geometry. The file is much larger, though, so the template workflow is
better.

## Regenerating templates

```
python3 tools/armor_mesh/export_templates.py                  # every registered vehicle -> tools/armor_mesh/templates/
python3 tools/armor_mesh/export_templates.py t72b             # one vehicle
python3 tools/armor_mesh/export_templates.py --edit DIR       # also write armor + visual reference edit files to DIR
python3 tools/armor_mesh/export_templates.py --check          # re-read every exported box and compare corners
```

Coordinates: armor-profile `(x, y, z)` blocks become geo `(-16x, 16y, 16z)`, or `(16x, 16y, 16z)` for the
X-mirrored profiles `t72a` and `t72b`; starter volumes come from SBW vehicle-local OBBs as geo
`(16x, 16y, -16z)`. The loader applies the inverse, so a template loaded as a mesh reproduces the boxes
exactly. `ArmorMeshEquivalenceTest` fires 2,500 rays and 1,500 points per profile at the box and mesh
versions of all 61 box profiles and requires identical results.

## Generating a mesh from the vehicle model

`tools/armor_mesh/build_mesh.py <id>` builds an armor mesh from the visual model and a spec,
`tools/armor_mesh/specs/<id>.json`. The spec says which model parts are armored structure, how thick each
face region is, and where the modules go. The T-90A (`t90a`) is the first vehicle done this way.

* **Hull.** The side profile (`hull.profile`, z/y points) is extruded to `hull.halfWidth`, then optionally cut
  by `hull.chamfers` planes (`mirrorX` for both sides). Each face is split by `hull.cuts` and assigned a
  thickness by the first `hull.regions` rule it matches. A rule can match on the normal (`nx`, `ny`, `nz`,
  `anx` = |nx|), the centre (`x`, `y`, `z`, `ax` = |x|) or both. It gives either `mm`, or
  `los: [dx, dy, dz, mm]`: the line-of-sight thickness along that direction, turned into nominal mm for
  the face's angle, with `minMm` as a floor.
* **Turret and mantlet.** `parts` select connected components of a model bone (`within` bounds plus
  `minVolume`). Fittings, ERA, sights and the barrel tube are left out. The selection's convex hull, or each
  component's own hull with `"each": true` for stepped or staggered pieces, is split and classified the
  same way.
* **Plates.** Every face becomes a *mitred slab*: the part of the solid within 1 px of that face and nearer
  to it than to any other face. Neighbouring slabs share their mitre planes exactly, so the shell has no
  seams and no rim of one plate is ever exposed. Every slab is written as a closed triangulated convex
  solid, and the build fails if any edge is open.
* **Modules, automatic.** `modules.engine` fills the hull behind the turret ring. `modules.carousel`
  places an autoloader ring under the turret ring. `modules.racks` adds extra ammo boxes.
  Tracks get no hitbox: a shot through the running gear goes on to the hull plates behind it.
  (`modules.tracks` still builds per-side track modules if a spec asks for one; no shipped spec does.)
* To make a region thicker, add a rule for it. For a weak spot, add a cut that isolates the patch, then a
  thinner rule for it (e.g. `ufp_driver_port`, `sight_notch`).

```
python3 tools/armor_mesh/build_mesh.py t90a            # write armor_mesh/t90a.geo.json
python3 tools/armor_mesh/build_mesh.py t90a --report   # list the volumes only
```

### Hit registration test

```
python3 tools/armor_mesh/hitreg_rays.py t90a rays.jsonl --step 2 --az 10 --el=-8,0,12,30,55,80
# ArmorMeshHitregHarness (bvp/src/test) resolves every ray with the game's own resolver at turret yaws:
java ... ArmorMeshHitregHarness t90a <armor.json> <mesh.geo.json> rays.jsonl out.csv <turretPos> <barrelPos> 0,45,90,180,270
python3 tools/armor_mesh/hitreg_report.py t90a rays.jsonl out.csv
```

`hitreg_rays.py` fires parallel ray grids from every direction at the visual model and labels the first
model face each ray meets: core structure, track, ERA, barrel or fitting. The harness runs those rays
through `ArmorHitResolver`, `ArmorModuleResolver` and the angle rule. The report fails on any of these:

* a core ray that finds no plate;
* a plate entry more than 2 px from the model surface, measured along the plate normal;
* an entered face that is not the plate's own face;
* a fitting, ERA or track ray with structure behind it that finds no plate;
* a turret result that changes when the turret turns, other than a rotated shot now meeting hull armor or
  a track first, or a skin graze now resolving to the solid behind it.

## Generated meshes (no spec)

`tools/armor_mesh/auto_mesh.py` builds the same kind of file straight from the visual model, with no
hand-written spec. It covers every armored ground vehicle except the ones with hand-authored box armor
the owner keeps (BMP-1/2/2M, T-90A, all Abrams, T-72B, M48, M1128, T-62A, BTR-80A, BTR-60, the Toyotas,
ZBD-09, Marder 1A2, CV9040C, M2 Bradley, BMPT, and since 2026-09-28 the T-55A, Leopard 2A6, LAV-25 and
BTR-90, whose generated meshes are in `tools/replaced/armor-mesh-restored-box-20260928/`) and the
emplacements: `AUTO_TARGETS`, 36 vehicles.

* **Structure (simplified, 2026-09-28).** Model bones go to the hull, turret or barrel frame by their parent
  chain. Running gear, ERA, secondary mounts, rods (antennas, rails), specks, tubes and small roof fittings
  are left out. Every solid is bounded by at most 26 planes: the planes of the convex hull's largest faces
  (most area first, none within 14 degrees of another) plus the box axes where nothing is close, each pushed
  out to the farthest point. The planes lie on the model's real big faces, so a glacis or a turret cheek is one
  face and the solid is only 1-10% larger than the model's hull: 80-150 plates per vehicle instead of 350-470. The hull is two lengthwise halves, each a core
  between the running gear plus a sponson above it where the hull is wider; the turret solid is carried
  down to the hull roof (no separate collar); the mantlet is the barrel-frame parts around the trunnion.
  The 9P149 launcher has no armor at all: it is the `launcherreload` weapon module (see below).
* **Thickness.** Per frame and aspect, the line-of-sight thickness of the vehicle's own box plates
  (`templates/<id>.armor.geo.json`): a plate counts for an aspect when it faces within ~70 degrees of it,
  with its mm divided by that cosine. The front takes the largest value, so a shot at composite cheeks or
  a glacis meets the full protection; the other aspects take the median weighted by projected area. Each
  face then gets that value times the cosine of its own slope, so a horizontal shot meets the same line of
  sight the box armor had. Vehicles with no box plates use the nominal per-aspect values in `DEFAULTS`.
  ERA, engines, ammo and modules stay on the boxes.
* **Rifle-calibre floor.** `min_armor_mm` in an armor profile (`tools/armor_mesh/armor_floor.py` sets 16 on
  every vehicle of SBW Type Tank or APC) is a floor under every plate, box or mesh, and under gaps in box
  armor: rifle-calibre rounds (at most 13 mm) cannot kill an IFV with the coax; 12.7 mm and up still can.
* **Tracks** get no hitbox.

```
python3 tools/armor_mesh/auto_mesh.py --targets             # write all 36 into armor_mesh/
python3 tools/armor_mesh/auto_mesh.py leo2a6 --report       # plate table and thickness sources only
python3 tools/armor_mesh/auto_hitreg.py leo2a6 rays.jsonl   # ground-truth rays from the generator's own solids
java ... ArmorMeshHitregHarness leo2a6 <armor.json> <mesh.geo.json> rays.jsonl out.csv <turretPos> <barrelPos> 0,90
python3 tools/armor_mesh/auto_hitreg_report.py leo2a6 out.csv
java ... ArmorMeshAutoCheck <armorDir> <meshDir> <id>...     # loads each file through the game's loader
```

The generated-mesh gates are looser on placement than the hand-built T-90A: every shot at armored
structure must find a plate (≥ 99.5%), shots through the tracks must reach the hull behind (≥ 97%), the
armor entry must lie within 8 px of the model surface for ≥ 80% of shots, and turret results must stay the
same when the turret turns (≥ 95%). Convex solids bridge concave outlines, so a generated mesh sits a few
px proud of the model in places (under a turret bustle, around stowage). To tighten a vehicle, give it a
spec and use `build_mesh.py`.

## For developers

* `ArmorVolume` is the geometry contract. `ArmorBoxVolume` is the unchanged box math. `ArmorMeshVolume` is
  the mesh: welding, winding repair, a closed/manifold report, one BVH per part, plane clipping for convex
  closed parts, and a surface skin plus generalized winding numbers for the other parts.
* `ArmorProfiles.ArmorBox` keeps its name. It is now any authored volume, with its geometry in `volume`.
  All consumers go through `rayHitDistance`, `distanceOutside`, `normalAt`, `closestApproach`,
  `centroid()`, `volume.bounds()` and `volume.vertex(...)`. `center`, `halfSize` and `rotationDeg` only
  describe boxes; for a mesh they are its bounds.
* `ArmorHit.frameNormal()` is the normal every armor consumer uses. A mesh ray hit returns the entered
  triangle's normal. A box hit keeps the old face-ratio rule, so box profiles behave exactly as before.
* `ArmorMeshLoader` depends only on Gson and plain Java, so it is safe on the dedicated server.
  `ArmorProfiles.prefetch` parses a profile on a background thread when a vehicle is created.

## Launcher weapon module (`launcherreload`)

A module box with `"module": "launcherreload"` (9P149: the tube in the barrel frame, the arm in the turret
frame) stands in for armor on an exposed launcher. A hit damages it like any generic module
(`VehicleModuleHealth.GENERIC_MODULE_HP`); when it is destroyed the loaded missile is lost - seat 0 weapon 0
is emptied and the vehicle's auto-reload runs as if the missile had been fired, without a launch. The module
is whole again once that reload is over (`VehicleModuleDamageSystem.tickLauncherModule`). Weapons are not
disabled.
