# The canopy, built new

`canopy.py` replaces each aircraft's modelled canopy with one that has real parts: the old canopy is cut out of the
fuselage pieces, frame members with a cross-section are built along every frame station of the panel glass, and the
glass gets a second sheet under the first so it reads as thick. It runs after `tools/canopy_panels/panels.py` (the
panes and their frame stations, from `spec.json`) and `tools/cockpit_models/fleet.py` (the modelled cockpit).

- **Old canopy.** Removed from the hull wreck pieces, inside the canopy's plan (the panel envelope's width plus
  0.30 m, from the hood's end to the windscreen base): faces that stand above the sill line between the arch and
  the hood's end (old panes, bows, painted humps, the glazing of the headrest fairing), faces lying along the new
  glass, faces standing off the nose skin along the windscreen (posts, bows, armour-glass plates), faces painted in
  the model's glass colour (a saturated cyan or blue that the texture uses almost only around the cockpit, so a
  blue camouflage is left alone) and the dark frame faces around those. The outer skin along the sills and the nose
  deck under the windscreen stay.
- **Sill line.** A hood's lower edge runs level, so along the hood the loft's rail is capped a little (`sill_rise`,
  0.08 m) above its lower part: where the model's own canopy was painted on as a solid hump merging into the
  fairing, or a windscreen block filled the opening, the rail rode over it and the glass floated.
- **Members.** Solid boxes along each chain of points, straddling the glass (outer face 14 mm proud of it, inner
  face 24 mm under): windscreen posts and the arch 55 mm wide, hood bows, the windscreen base and the rear hoop of
  a fixed rear glazing 45 mm, the sills 70 mm wide and 36 mm high with the seal on top. Sills run only where the
  opening is wide and its edge level (not up the headrest fairing or the spine). Outer faces and edges take the
  airframe paint beside the cockpit: the most common texel colour on the skin just outside the sills, seen from
  above on the original model, snapped to a uniform block of the texture. Inner faces are the cockpit's dark grey,
  sill tops the black of the canopy seal.
- **Glass.** The panel glass gets an inner sheet 12 mm under the outer one (both windings, in `CanopyGlass`).
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
