# The canopy, built new

`canopy.py` replaces each aircraft's modelled canopy with one that has real parts: the old canopy is cut out of the
fuselage pieces, frame members with a cross-section are built along every frame station of the panel glass, and the
glass gets a second sheet under the first so it reads as thick. It runs after `tools/canopy_panels/panels.py` (the
panes and their frame stations, from `spec.json`) and `tools/cockpit_models/fleet.py` (the modelled cockpit).

- **Old canopy.** Only faces lying wholly inside an explicit region go, so a face is never taken for touching a
  removed one or for where its middle sits, and the skin and the deck under the windscreen stay:
  - *hood*: between the hood's end and the arch, inside the glass's plan (+0.15 m), every corner on or above the
    sill line (lip - 0.03 m): old panes, bows, painted humps, the headrest fairing's glazing;
  - *windscreen*: between the arch and the base, inside the arch's width (+0.15 m), every corner off the nose skin
    (+0.015 m): posts, bows, armour-glass plates;
  - *painted*: a face in the model's glass colour (a saturated cyan or blue the texture uses almost only around
    the cockpit, so a blue camouflage is left alone) inside the plan, down to 0.6 m under the sill: a pane painted
    onto the fuselage side;
  - *cut*: boxes listed per aircraft in `tools/canopy_panels/spec.json` `"cut"`, relative to the pilot's eye
    (x left, y up, z forward), for leftovers the rules cannot tell from skin (the F-86K's armour-glass windscreen
    drawn as a black wedge on the nose).
  The report counts each rule's take (`hood 47, windscreen 0, painted 0, cut 0`).
- **Checking the cut.** `review.py --orig=DIR OUT id...` renders each aircraft (side, front quarter, front, seat)
  and the side view again with the removed faces drawn over it: red for the canopy cut (computed on the original
  model), orange for the old interior `fleet.py` replaced. Nothing in red or orange should be fuselage skin or the
  nose deck; when it is, the region is wrong for that aircraft and `spec.json` is adjusted and the aircraft rerun.
- **Leftovers.** `windscreen.base` in `spec.json` gives the windscreen's foot outright where the loft could not
  find it.
- **Sill line.** A hood's lower edge runs level, so along the hood the loft's rail is capped a little (`sill_rise`,
  0.08 m) above its lower part: where the model's own canopy was painted on as a solid hump merging into the
  fairing, or a windscreen block filled the opening, the rail rode over it and the glass floated.
- **Members.** One member is swept round the glass's whole edge (the windscreen base, both sills and the rear
  edge: the last bow of a fairing-backed hood, or the end of the rear glazing), and one over the glass at every
  frame station (windscreen posts, the arch, the bows, and the hinge frame at the hood's end where glazing runs on
  behind it). Each is a rectangular section set at every point of its line, turned to the line's direction there
  (so corners are mitred) and joined into one continuous solid, straddling the glass: the rail 90 mm wide, 22 mm
  proud and 45 mm under, moved 25 mm outboard so it sits on the coaming; the rings 80 mm wide (posts 70 mm),
  20 mm proud and 40 mm under. `spec.json` `"frame"` overrides per aircraft (`rail_w`, `bow_w`, `arch_w`,
  `post_w`, `rail_shift`), sized from references: the F-16's hinge frame is a 140 mm band. Outer and side faces
  take the airframe paint beside the cockpit: the most common texel colour on the skin just outside the sills, seen
  from above on the original model, snapped to a uniform block of the texture. The face under the glass is the
  cockpit's dark grey.
- **Glass.** The panel glass is a solid in `CanopyGlass`: the outer sheet, an inner sheet 20 mm under it, and the
  edge between them round the perimeter. The game draws it as a tint that thickens at grazing angles, so the two
  sheets read as a pane and the edge as its thickness.
- **References.** Proportions (length, width, height over the sill, bow positions, frame widths) are checked
  against open 3D models of the real aircraft, chiefly FlightGear's GPL aircraft (an AC3D reader is enough to
  measure them), and the per-aircraft findings go into `spec.json` as `_ref` notes next to the values they set.
- New faces go into `wreck_<piece>__canopy` (parent `hull`) of the piece that holds the cockpit, so the canopy
  breaks away with the forward fuselage. A second run removes its own canopy bone and inner sheet first.

`run.py` runs the whole chain for an aircraft from its committed originals, since every step measures the model
(`fleet.py` its interior, `panels.py` and `canopy.py` the exterior) and the frames must land on the glass:

```
export BVP_TEX_DIR=/path/to/real/textures     # when the repository's PNGs are Git LFS pointers
python3 tools/canopy_build/run.py --rev=57093ef <id>...      # no ids: every aircraft in spec.json
```

It restores the geo and asset files from `--rev` (default: the merge base with `origin/main`), keeps a copy of the
original geo (`--orig=DIR`, or a temporary folder) that `canopy.py --orig=DIR` measures its envelope on, and runs
`panels.py --write`, `fleet.py` and `canopy.py` in turn. The MiG-29 (`skip` in `spec.json`) keeps its hand-built
glass and only gets its old canopy cut out and the members. Afterwards refresh the changed rows in
`SOURCE_SNAPSHOT.json`.
