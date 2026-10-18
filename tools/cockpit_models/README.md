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
| centre, top row | UAP-14 angle of attack and g-meter | game `AOA`, `G_METER` |
| | master caution lamp, countermeasure counter | painted |
| centre, row 1 | KPP attitude director (ADI) | game `ATTITUDE` |
| | VAR vertical velocity | game `VVI` |
| | radio altimeter | painted |
| | ITE-2T twin tachometer | game `THROTTLE` |
| centre, row 2 | PNP-72 HSI | game `HEADING` |
| | KUS true airspeed / Mach | game `MACH` |
| | ITG gas temperature left and right | painted |
| centre, row 3 | SAU autopilot push-lights, air intake ramp positions | painted |
| | AChS-1 clock | game `FILLER_CLOCK` |
| left wing | KUS indicated airspeed | game `AIRSPEED` |
| | VD-20 barometric altimeter | game `ALTIMETER` |
| | weapon control panel, IP-52 gear/flaps indicator, signal lights | painted |
| right wing | IT-23M head-down display (with hood) | game display `RADAR`, CRT |
| | SPO-15 radar warning | game `RWR` |
| | fuel quantity, IKG hydraulics, oxygen, voltmeter | game `FILLER_*` |
| | Ekran warning display | painted |

Above the coaming sits the ILS-31 HUD: a body under the glareshield with its control strip (ФИЛЬТР, ЯРК, ДН/НЧ,
КОНТР) facing the pilot, and a combiner frame leaning back 22 degrees with the glass in `CanopyGlass`. The game
projects the HUD picture onto that glass (`HeadUpDisplay` in the asset). Below the
panel are the consoles (throttle on the left), the floor, rudder pedals, the centre stick and a K-36DM seat.

### References

- DCS: MiG-29 (Flaming Cliffs 3) flight manual, Figure 15 "Central panel of the MiG-29" and its legend; the head-down
  display "positioned in the right upper corner of the instrument panel".
- DCS: MiG-29A Fulcrum early access manual: instrument designations, the right-side group (radio altimeter, SPO-15LM,
  Ekran, hydraulics, oxygen, voltmeter) and the HUD field of view (13 x 18 degrees at 600 mm).
- FlightGear MiG-29 9-13 by Gary Neely (github.com/FGMEMBERS/MiG-29), `Mig-29_Instruments.xml`: instrument offsets
  in metres from the eye (panel about 0.85 m ahead; ADI left of the centreline; the airspeed/altimeter column
  angled about 10 degrees) and dial sizes (80 and 56 mm).

## The fleet

```
BVP_TEX_DIR=DIR python3 tools/cockpit_models/fleet.py [--dry-run] [id...]   # every aircraft in fleet.json by default
```

`fleet.py` gives 65 more aircraft (jets and piston fighters) a modelled cockpit with `cockpit_lib.py`, the
MiG-29's generator turned into a fitted, parametric one:

- **Fit.** From the pilot's eye and the canopy glass (`CanopyGlass`) it measures the tub: half width and sill height
  beside the eye, the windscreen base ahead, and a scale from the width. The canopy width clears the old interior; the
  new tub is narrowed to the fuselage skin beside the pilot (and again until the panel wings fit), so nothing pokes
  through a narrow nose. The panel stands about 0.64 m ahead of the eye (scaled), its top kept under the glass;
  consoles, floor and seat follow from the eye height. The coaming is pulled in where it would leave the glass or the
  skin, and the side walls run forward only as far as they stay inside.
- **Old interior.** Removed from the hull wreck pieces: the tub below the sill, small loose parts inside the canopy
  (old seat, panel, gunsight), and everything in front of the pilot below eye level up to the windscreen base (old
  panels and glareshields). Kept: the outer skin (a face that is the outermost one along its sideways line, or whose
  outward normal ray leaves the aircraft), the canopy and windscreen frames (faces lying along the glass), and the deck
  under the windscreen, which is cut at the windscreen base. Thin upright members (the legs of an armour-glass frame)
  are cut at the coaming instead of removed. Old gunsight/HUD glass and glass inside the tub go from `CanopyGlass`.
- **Cockpit.** Three-facet panel, coaming, side walls, consoles with switch blocks, sill caps, floor, rear bulkhead,
  footwell, rudder pedals, throttle, an ejection seat (jets) or armoured bucket seat (piston fighters), and a HUD
  (body, control strip, combiner and its glass, plus `HeadUpDisplay`) on HUD-equipped jets or a reflector gunsight
  on the others. New faces go into `wreck_<piece>__cockpit` of the piece that held most of the old interior.
- **Stick.** A centre stick (or a side stick on the right console for the F-16 and Rafale) in the existing animated
  bone `<piece>__control_stick_pitch`; the stick bones' pivots and the `AircraftRig` stick surfaces move to the new
  stick's foot, so it pivots where it stands. Aircraft without a stick rig get a fixed stick.
- **Instruments** by era (`cockpit_lib.ERAS`, the era from `tools/cockpit_layout/presets.json` unless `fleet.json`
  says otherwise), packed in rows on each facet; dials the game knows are live (airspeed, Mach, AoA, VVI, g, RWR,
  attitude, heading, altimeter, throttle). Steam eras get the basic six plus engine dials, radar eras a radar scope
  with a hood and an RWR, the glass eras three displays (stores, PFD, radar), an up-front control panel and standby
  dials. A stores page gets the airframe planform for its diagram.
- **Colours** come from uniform texel blocks already in each aircraft's own texture (`Swatches`), nearest to the
  palette in `fleet.json` (NATO grey, Soviet turquoise, WWII interior green, RLM 66 and so on), so no texture is
  changed. `BVP_TEX_DIR` points at a folder with the real PNGs when the repository holds Git LFS pointers.

Run it on the committed originals (`git checkout` the aircraft's geo and asset files first): the tub is measured
from the old interior, so a second run on top of the first fits slightly differently and removes a few more faces. `tools/cockpit_layout/layout.py`
skips these aircraft and the MiG-29, whose instruments their cockpit tools place.
