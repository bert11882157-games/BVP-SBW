# Canopy glass fitted to the model

`glass.py` builds an aircraft's `CanopyGlass` from the model as it is: the glass sits on the model's own sills and
frames, never cuts the skin and leaves no gap. Nothing in the model's geometry or texture changes.

    python3 glass.py VID... [--ref REV] [--out DIR] [--loft-ref REV] [--write]

`--ref` reads the model at a git revision (textures stored in Git LFS are taken from `CANOPY_FIT_TEX` when that
folder holds a copy with the same hash), `--out` saves the triangles as `<vid>.npz`, `--write` puts them into the
vehicle's asset file. `--loft-ref` is where the lofted hoods are read from (default `LOFT_REF`; `''` fits a hood
instead). Needs the `bpy` module (Blender as a Python module), numpy, scipy and Pillow.

- `model.py` loads the geo, texture and vehicle files and casts alpha-tested rays in the vehicle frame (blocks; x
  left, y up, z forward). Rotor and propeller bones are passed through: they spin, so they are no part of a frame.
- Each crew eye is mapped (`panes.EyeMap`): hit distance per direction on a 1-degree grid. When the crew see out over
  more than `SKY` of the view above 30 degrees, the cockpit is open and gets a hood.
- The hood is the lofted canopy `tools/canopy_loft` built (smooth sections from rail to rail through the frame
  tops), read from the glass the aircraft carried at `LOFT_REF`. The loft is cut where it meets frames and skin; its
  largest piece is the hood and the smaller pieces (shards of windscreen between frame bars) are dropped, their
  windows glazed with panes instead (below). `WHOLE_LOFT` keeps every piece, for a greenhouse cut between its frames.
  The loft stays one sheet, its faces turned away from the crew so the game shades it smoothly.
- Without a loft the hood is fitted (`fit.py`): a top-down height map around the crew gives the cockpit opening
  (`plan.opening`) and its sill line; superellipse sections are cut to the skin, made fuller and then taller (up to
  `SMAX`) wherever anything in the opening or the crew's head comes within clearance, and held at least `COVER` over
  the sills; `hull.py` lays the convex hull of the sections and of every frame around the opening and cuts it at the
  skin.
- Every other framed opening the crew look out through (`panes.window_panes`) gets one pane, unless glass built
  before it already covers it. In order: a pane flush in its frame, fitted from outside to the skin face around
  the opening (`flush_pane`); else a flat or gently curved pane on the frame itself (`pane`); else the membrane
  stretched across the frame, thinned. A window a broken loft reaches only part of gets its own pane, and the loft
  in front of that window is taken off.
- Last, where the glass was pieced together (a broken loft, a fitted hood, windows only), each eye is mapped again
  through it: any sliver it still sees out through with glass along at least `SEAL_EDGE` of its edge (between the
  glass and a frame or sill) gets a clean pane when one fits, so the glass leaves no gap. A whole loft already
  closes on its frames and sills.
- Each pane is given thickness toward the eye (`panes.solidify`).

Aircraft whose model paints the canopy over (no see-through opening) get no glass from this tool.
