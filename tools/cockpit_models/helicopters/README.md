# Helicopter cockpits

`../helicopters.py` replaces the interior baked into each helicopter's `hull` bone with a modelled cockpit built by
`../cockpit_lib.py`, and gives every crew station flight controls that the aircraft rig moves.

```
git checkout HEAD -- <geo> <asset> <data>      # always start from the committed originals
python3 tools/cockpit_models/helicopters.py --sheet /tmp/hc ah_64d
python3 tools/cockpit_models/helicopter_controls.py ah_64d /tmp/fwd.png --seat 0 --pose forward
python3 tools/cockpit_models/helicopter_side.py ah_64d /tmp/side.png
```

The three files are `custom_geo/<vid>.geo.json`, `assets/.../sbw/vehicles/<vid>.json` and
`data/.../sbw/vehicles/<vid>.json` under `bvp/src/generated/resources/`. The driver measures each station from the
old interior and the canopy glass, so running it twice over its own output gives a different result.

## Files

| File | What it is |
|---|---|
| `<vid>.json` | The spec: palette, sources, stations (era, controls, tub overrides, eye), remove/keep boxes, colour overrides, added glass. The format is in the driver's docstring. |
| `<vid>.py` | Optional `extra(ac, tubs, stations, mesh, c)`: details only that helicopter has (gunner sights, the TEDAC hood, the Ka-50 Shkval hood). `mi24v.py` holds the operator sight the other Mi-24s reuse. |

## Controls

| Bone | Moves with | Axis | Travel |
|---|---|---|---|
| `cyclic_roll_<seat>` / `cyclic_pitch_<seat>` | bank, pitch | fore-aft, then lateral | 8° (4° for a side-arm cyclic) |
| `pedal_left_<seat>` / `pedal_right_<seat>` | yaw | hinge above the pedal | 12° |
| `collective_<seat>` | `PRESENTED_COLLECTIVE` | aft hinge | 16° up |
| `throttle_<seat>` | `PRESENTED_THROTTLE` | quadrant pivot | 40° forward |

`AircraftRigAnimator` feeds the cyclic and pedals from the pilot's synced mouse and bank inputs
(`BvpHelicopterEntity.presentedCyclicForward/Right`, `presentedPedalRight`), and the levers from the flight
snapshot's collective and throttle, with a short lag. A helicopter rig that holds only cockpit controls
(`cockpitOnly`) leaves rotors, gear and the rest to the existing animators.

## Eras

Instrument sets for helicopter stations, added to `cockpit_lib.ERAS`: `HELO_STEAM`, `HELO_STEAM_RWR` (Cobra, Hind),
`HELO_GUNNER`, `HELO_GUNNER_SIGHT` (front-seat operator with the sight on the centreline), `HELO_APACHE`,
`HELO_GLASS`, `HELO_GLASS_LCD` (Tiger), `HELO_GLASS_RADAR_LCD` (Mi-28N), `HELO_GLASS_GUNNER` and `HELO_KA50`. Rotor
RPM and torque are live gauges.

## Moved eyes

Some seats' eye attachments sat inside the airframe or at the nose gun. A station with `"move_eye": true` moves the
eye to where the seat is, which changes the in-game camera for that seat: the AH-64D and AH-1F gunners, both AH-1G
seats, the AH-6J pilot, both Mi-26 pilots, the CH-46E co-pilot, the Mi-24V gunner, the Mi-24D and Mi-24A pilots, the
Mi-28N operator and the Tiger front seat.

## Known limits

* Colours come from blocks already in each helicopter's texture, so cockpits without a turquoise or grey-blue in
  their texture (Ka-50, Mi-28N) use the nearest colour there.
* Old seat pieces behind the crew are left where removing them would open a hole in the fuselage.
