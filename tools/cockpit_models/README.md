# Modeled cockpits

Generators that replace a vehicle's placeholder cockpit with a modeled one: panel, glareshield, HUD, consoles,
seat and stick, plus the `CockpitGauges` / `FlightDisplays` entries the game draws live.

## MiG-29 (9-12 / 9-13)

```
python3 tools/cockpit_models/mig_29.py            # writes the four files below
python3 tools/cockpit_models/mig_29.py --dry-run  # builds and reports, writes nothing
python3 tools/cockpit_models/preview_mig_29.py OUT_DIR   # pilot view, numbered layout, side cutaway, outside view
```

It rewrites:

- `custom_geo/mig_29.geo.json`: removes the old cockpit faces from `wreck_fuselage_1__hull` (the tub below the
  sill, the old HUD shelf and glareshield wings, and the small loose seat/panel parts; the outer skin along the sill
  and the deck under the windscreen base are kept), adds the bone `wreck_fuselage_1__cockpit` (so it breaks away with
  the forward fuselage) and rebuilds the poly mesh of `wreck_fuselage_1__control_stick_pitch`.
- `sbw/vehicles/mig_29.json`: `CockpitGauges`, `FlightDisplays`, and the HUD combiner triangles in `CanopyGlass`
  (old combiner triangles near the centreline are dropped, the new quad is added in both windings).
- `textures/entity/mig_29.png` and `textures/entity_dead/mig_29.png`: the instrument faces and cockpit surfaces are
  painted into the free areas (1152,0,896,640) and (0,0,1152,190); the wreck copy is darkened.

The run is repeatable: a second run removes nothing more and writes the same files. After changing any of them,
refresh their rows in `SOURCE_SNAPSHOT.json`. If the exterior canopy is re-exported later (for example by
`tools/cockpit_authoring`), rerun this so the combiner glass comes back.

`eyeview.py` is the small textured rasterizer the previews use (`eyeview.py mig_29 out.png --eye x,y,z --look
yaw,pitch --fov deg`); its dials and screens are stand-ins for what the game draws.

### Layout

The panel is three facets: a centre panel leaning back 12 degrees and two wings angled 28 degrees towards the
pilot, under a black glareshield coaming that runs forward to the windscreen base. Positions are vehicle-local
blocks (1 block is about 1 m on this model; x is the pilot's left). Entries marked *game* are drawn live by the
game; the rest are painted faces.

| Facet | Instrument | Drawn as |
|---|---|---|
| centre, top row | UAP-14 angle of attack / g-meter, master caution lamp, countermeasure counter | painted |
| centre, row 1 | KPP attitude director (ADI) | game `ATTITUDE` |
| | VAR vertical velocity, radio altimeter | painted |
| | ITE-2T twin tachometer | game `THROTTLE` |
| centre, row 2 | PNP-72 HSI | game `HEADING` |
| | KUS true airspeed / Mach, ITG gas temperature left and right | painted |
| centre, row 3 | SAU autopilot push-lights, air intake ramp positions | painted |
| | AChS-1 clock | game `FILLER_CLOCK` |
| left wing | weapon control panel, KUS indicated airspeed, IP-52 gear/flaps indicator, signal lights | painted |
| | VD-20 barometric altimeter | game `ALTIMETER` |
| right wing | IT-23M head-down display (with hood) | game display `RADAR`, CRT |
| | fuel quantity, IKG hydraulics, oxygen, voltmeter | game `FILLER_*` |
| | Ekran warning display, SPO-15 radar warning | painted |

Above the coaming sits the ILS-31 HUD: a body under the glareshield with its control strip (ФИЛЬТР, ЯРК, ДН/НЧ,
КОНТР) facing the pilot, and a combiner frame leaning back 22 degrees with the glass in `CanopyGlass`. Below the
panel are the consoles (throttle on the left), the floor, rudder pedals, the centre stick and a K-36DM seat.

### References

- DCS: MiG-29 (Flaming Cliffs 3) flight manual, Figure 15 "Central panel of the MiG-29" and its legend; the head-down
  display "positioned in the right upper corner of the instrument panel".
- DCS: MiG-29A Fulcrum early access manual: instrument designations, the right-side group (radio altimeter, SPO-15LM,
  Ekran, hydraulics, oxygen, voltmeter) and the HUD field of view (13 x 18 degrees at 600 mm).
- FlightGear MiG-29 9-13 by Gary Neely (github.com/FGMEMBERS/MiG-29), `Mig-29_Instruments.xml`: instrument offsets
  in metres from the eye (panel about 0.85 m ahead; ADI left of the centreline; the airspeed/altimeter column
  angled about 10 degrees) and dial sizes (80 and 56 mm).
