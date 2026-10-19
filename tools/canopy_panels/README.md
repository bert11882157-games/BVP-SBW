# Canopy glass by panel

`panels.py` builds each aircraft's `CanopyGlass` from the real canopy's panels instead of one blown sheet, so the
glass has polygons where the real canopy has panes and nothing where it has frame or skin.

- The canopy envelope (plan width, sill line, spine, side fullness, windscreen and rear stations) is still measured
  on the model by `tools/canopy_loft` (`Loft` on the rails, frames and painted plan). `panels.py` keeps the lower
  edge on the sill: open stations sit just below the measured crest, closed stations (ahead of and behind the
  cockpit opening) are narrowed to where the skin falls away, so no glass hangs under a sill, and `lip_below_eye`
  stops a greenhouse lip from dropping too far under the pilot's eye.
- `spec.json` says, per aircraft, how the real canopy is made (from type photos and drawings; see
  the research notes kept with the PR):
  - windscreen: `curved` (one lofted sheet: F-15, Su-27), `flat3` (flat armour-glass centre between two posts plus a
    flat quarter panel each side: Su-25, A-10, MiG-23, F-104, Ju 87), `flat1` (one flat plate between the posts,
    lofted sides: MiG-15/17/19, F-86, MiG-21), `integrated` (frameless with the hood: F-16); `rake` is the
    windscreen angle, `centre_frac` the centre panel's share of the arch width, `base` the windscreen's foot (z)
    where the loft cannot find it.
  - hood: `round`, `flatside` (flat sides to a shoulder, round top) or `flattop` (greenhouse), cut at each bow
    (`bows`, as fractions from the arch to the hood's end).
  - rear: `fairing` (solid behind the last bow), `glazed` (runs down the spine: F-16, F-15, Su-27) or `taper`
    (sliding bubble hoods: P-51, Spitfire, Sabre, F8F, Yaks).
  - `loft`: overrides passed to the loft envelope; `skip: true` leaves hand-built glass alone (MiG-29);
    `sill_rise` and `remove_boxes` are read by `tools/canopy_build/canopy.py`.
- Every flat panel is one quad and every curved section a regular grid between two frame stations, so edges are
  shared and nothing is clipped or jagged.
- `frames/<id>.json` lists the frame bars (posts, arch, bows) as segments; where it exists
  `tools/cockpit_models/fleet.py` keeps the panel glass whole (no sight-glass or inside-glass dropping). The frame
  members themselves, the sills and the glass's thickness are built by `tools/canopy_build/canopy.py` from the
  same `Builder` (its `frames`, `edges` and `sills()`), which also cuts the model's old canopy out.

Rebuild (on the committed originals, since `fleet.py` measures the old interior): `python3 tools/canopy_build/run.py
--rev=57093ef <id>` runs the chain (`panels.py --write`, `fleet.py`, `canopy.py`); see `tools/canopy_build/README.md`.

`--out=dir` writes `glass.json` per aircraft for review instead; `tools/canopy_loft/review.py` renders the sheets.
