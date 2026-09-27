# Cockpit authoring models (Blockbench)

One file per aircraft, with the cockpit section cut out of the game model, so you can rework the cockpit geometry
and place the flight displays and gauges. The files are sorted into `jets/`, `props/` and `helis/`.

Each aircraft has two files:

| File | What it is |
|---|---|
| `<id>.cockpit.bbmodel` | The Blockbench project: a Generic Model made of Meshes, with the aircraft texture built in. |
| `<id>.cockpit.manifest.json` | Bookkeeping for the import. Keep it next to the model and don't edit it. |

## Opening

Double-click the `.bbmodel`, or use *File → Open Model* in Blockbench. No plugin is needed, and the texture is
already applied. Faces render from both sides, so the cockpit interior is visible from any angle.

The cockpit sits in the same model-pixel frame as the full model, and Blockbench shows it the way it shows the
game's Bedrock models (x mirrored). If you open the full model (`custom_geo/<id>.geo.json`) in another tab, the two
line up.

## What's in the file

| Groups | What it is |
|---|---|
| `hull`, `wreck_fuselage_1__hull`, … (the model's own bone names, as groups) | **The cockpit geometry.** These are the faces cut from the game model, still in the groups they came from (one Mesh called `faces` per group). Edit them freely: move vertices, extrude, knife, delete or add faces. You can add new Meshes to a group, or new groups under them. A Mesh outside every group goes into `hull`. |
| `ref_context` | The airframe around the cockpit, for orientation. **Never imported.** It opens locked (padlock) so you don't select it by accident; hide it with the eye icon to see inside. |
| `displays` → `display__s<seat>__<KIND>__<n>` | One flight display each: a Mesh called `screen`. A square screen facing the crew, with a small triangle on its **top edge**. `s0` is the pilot seat, `s1` the second crew seat. `KIND` is the page it shows: `PFD`, `RADAR`, `STORES`, … |
| `gauges` → `gauge__s<seat>__<KIND>__<n>` | One round gauge each (a Mesh called `dial`), e.g. `ATTITUDE`, `ALTIMETER`, `HEADING`, `THROTTLE`. The square is as wide as the dial. |
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

* **Move** it by selecting the Mesh inside its group and dragging it.
* **Turn** it by rotating the Mesh, *not the group*. A rotated group is ignored on import, and the import prints a
  warning.
* **Resize** it by scaling the Mesh. Screens stay square: the width is the mean of the four edge lengths.
* **Face the crew:** the flat side with the triangle should face the pilot, and the triangle marks the top of the
  screen.
* **Add a display:** duplicate a display group and bump the number: `display__s0__PFD__2`.
* **Remove a display:** delete its group.

## Saving

*File → Save Project* (Ctrl+S), keeping the name `<id>.cockpit.bbmodel` in the same folder as its manifest.

## Back into the game

Send me the saved file, or run this from the repository:

```
python3 tools/cockpit_authoring/cockpit.py import <folder>/<id>.cockpit.bbmodel [--dry-run]
```

* The faces in the file replace the faces that were cut from `custom_geo/<id>.geo.json`. This includes faces you
  added or deleted, and new groups (they become bones). Faces you did not touch keep their original normals; new
  or moved faces get flat normals from their winding.
* Older `.cockpit.geo.json` files still import the same way.
* The `display__` / `gauge__` groups become the aircraft's `FlightDisplays` / `CockpitGauges`. Seat, kind and
  count come from the group names; depth and screen style carry over from the display they replace.
* After one import, **re-export before editing again**, because the face bookkeeping changes.

## Notes

* The **A-10 and F/A-18E** are included even though those models will be replaced.
* On the **AH-1W** and **Mi-24 (Hind A)**, the crew eye positions sit behind the cockpit, near the stub wings.
  Their cockpits were cut from a hand-set box instead. The seats themselves are worth checking.
* Moving parts are never in the cockpit cut, even where they pass through it: rotors, sensor turrets, guns,
  control surfaces and gear. The control stick is included.
* The old `.cockpit.geo.json` + `.png` pairs from the first delivery were moved to `replaced/geojson-20260927/`.
