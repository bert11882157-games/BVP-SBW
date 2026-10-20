# Canopy glass fitted to the model

`glass.py` builds an aircraft's `CanopyGlass` from the model as it is: the glass sits on the model's own sills and
frames, never cuts the skin and leaves no gap. Nothing in the model's geometry or texture changes.

    python3 glass.py VID... [--ref REV] [--out DIR] [--write]

`--ref` reads the model at a git revision (textures stored in Git LFS are taken from `CANOPY_FIT_TEX` when that
folder holds a copy with the same hash), `--out` saves the triangles as `<vid>.npz`, `--write` puts them into the
vehicle's asset file. Needs the `bpy` module (Blender as a Python module), numpy, scipy and Pillow.

- `model.py` loads the geo, texture and vehicle files and casts alpha-tested rays in the vehicle frame (blocks; x
  left, y up, z forward). Rotor and propeller bones are passed through: they spin, so they are no part of a frame.
- Each crew eye is mapped (`panes.EyeMap`): hit distance per direction on a 1-degree grid. When the crew see out over
  more than `SKY` of the view above 30 degrees, the cockpit is open and gets a hood.
- The hood (`fit.py`): a top-down height map around the crew gives the cockpit opening (`plan.opening`, grown from
  the lowest point near the eye to the highest level that stays enclosed; closed along z over seat bars and across
  thin walls such as seat rails when that gives the larger opening) and its sill line. Superellipse sections are
  cut to the skin, made fuller and then taller (up to `SMAX`) wherever anything in the opening or the crew's head
  comes within clearance, and held at least `COVER` over the sills. `hull.py` lays the convex hull of the sections
  and of every frame, arch and post around the opening (each raised by its clearance), drops its underside, cuts
  it where it meets the skin and carries any edge still above the skin straight down onto it.
- Every other framed opening the crew look out through (`panes.window_panes`) gets one pane, unless glass built
  before it already covers it. In order: a pane flush in its frame, fitted from outside to the skin face around
  the opening (`flush_pane`); else a flat or gently curved pane on the frame itself (`pane`); else the membrane
  stretched across the frame, thinned. A window the hood lies well inside of (a windscreen ahead of the hood) is
  glazed only when a clean pane fits it.
- Each sheet is given thickness toward the eye (`panes.solidify`).

Aircraft whose model paints the canopy over (no see-through opening) get no glass from this tool.
