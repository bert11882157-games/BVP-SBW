# Cockpit authoring models (Blockbench)

One file per aircraft, with the cockpit section cut out of the game model, so you can rework the cockpit geometry
and place the flight displays and gauges. The files are sorted into `jets/`, `props/` and `helis/`.

Each aircraft has three files:

| File | What it is |
|---|---|
| `<id>.cockpit.geo.json` | The Blockbench model: a Bedrock Entity model, format 1.12.0, made of Meshes. |
| `<id>.png` | The aircraft texture. Drag it onto the model once it is open. |
| `<id>.cockpit.manifest.json` | Bookkeeping for the import. Keep it next to the model and don't edit it. |

## Opening

This uses the same setup as the armor meshes. Install the **Meshy** plugin once: *File → Plugins → Available*,
search "Meshy". Then open the file with *File → Open Model* and drag `<id>.png` onto it.

The cockpit sits in the same model-pixel frame as the full model. If you open the full model
(`custom_geo/<id>.geo.json`) in another tab, the two line up exactly.

## What's in the file

| Bones | What it is |
|---|---|
| `hull`, `wreck_fuselage_1__hull`, … (the model's own bone names) | **The cockpit geometry.** These are the faces cut from the game model, still in the bones they came from. Edit them freely: move vertices, extrude, knife, delete or add faces. You can also add new bones under them. |
| `ref_context` | The airframe around the cockpit, for orientation. **Never imported.** Lock it (padlock) so you don't select it by accident. |
| `displays` → `display__s<seat>__<KIND>__<n>` | One flight display each. A square screen facing the crew, with a small triangle on its **top edge**. `s0` is the pilot seat, `s1` the second crew seat. `KIND` is the page it shows: `PFD`, `RADAR`, `STORES`, … |
| `gauges` → `gauge__s<seat>__<KIND>__<n>` | One round gauge each, e.g. `ATTITUDE`, `ALTIMETER`, `HEADING`, `THROTTLE`. The square is as wide as the dial. |
| `unused_display__s<seat>__PFD` | A spare display parked outside the cockpit's left side. It does nothing until you rename it to `display__s0__PFD__1` (or `__RADAR__2` …) and move it into place. Every aircraft has one per crew seat, including the analog cockpits that have no displays today. |

### Kinds the game draws

| | Kinds |
|---|---|
| Displays | `PFD`, `RADAR`, `STORES`. Any other name is drawn as a PFD. |
| Live gauges | `ATTITUDE`, `ALTIMETER`, `HEADING`, `THROTTLE` |
| Static filler dials | `FILLER_FUEL`, `FILLER_OIL`, `FILLER_RPM`, `FILLER_CLOCK`, `FILLER_TEMP`, `FILLER_VOLTS`, `FILLER_HYDRAULIC`, `FILLER_OXYGEN` |

A new display takes the screen style of the aircraft's other displays: green CRT on older glass cockpits,
colour LCD otherwise. With no other displays, it is LCD.

### Placing a display or gauge

* **Move** it by selecting the Mesh inside its bone and dragging it.
* **Turn** it by rotating the Mesh, *not the bone*. A rotated bone is ignored on import, and the import prints a
  warning.
* **Resize** it by scaling the Mesh. Screens stay square: the width is the mean of the four edge lengths.
* **Face the crew:** the flat side with the triangle should face the pilot, and the triangle marks the top of the
  screen.
* **Add a display:** duplicate a display bone and bump the number: `display__s0__PFD__2`.
* **Remove a display:** delete its bone.

## Saving

Use *File → Export → Export Bedrock Geometry*, overwriting `<id>.cockpit.geo.json` in the same folder.

## Back into the game

Send me the saved file, or run this from the repository:

```
python3 tools/cockpit_authoring/cockpit.py import <folder>/<id>.cockpit.geo.json [--dry-run]
```

* The faces in the file replace the faces that were cut from `custom_geo/<id>.geo.json`. This includes faces you
  added or deleted, and new bones.
* The `display__` / `gauge__` bones become the aircraft's `FlightDisplays` / `CockpitGauges`. Seat, kind and
  count come from the bone names; depth and screen style carry over from the display they replace.
* After one import, **re-export before editing again**, because the face bookkeeping changes.

## Notes

* The **A-10 and F/A-18E** are included even though those models will be replaced.
* On the **AH-1W** and **Mi-24 (Hind A)**, the crew eye positions sit behind the cockpit, near the stub wings.
  Their cockpits were cut from a hand-set box instead. The seats themselves are worth checking.
* Moving parts are never in the cockpit cut, even where they pass through it: rotors, sensor turrets, guns,
  control surfaces and gear. The control stick is included.
