# Coordinate-guided vehicle weapons

The shared SBW armament API supports future aircraft cruise missiles and ground-vehicle GPS
launchers. Its existing `Aircraft*` API names and `sbw/aircraft_*` resource directories also accept
ordinary `VehicleEntity` implementations; no fixed-wing vehicle class is required. Seat 0 owns
these weapons. Weapon loadouts and geometry remain explicitly authored pack resources.

## Author a store

Put a separate store JSON in `data/<namespace>/sbw/aircraft_stores/<missile>.json`:

```json
{
  "Schema": 1,
  "Name": "Example cruise missile",
  "Category": "CRUISE_MISSILE",
  "CoordinateProfile": "ballistics:basic",
  "Capacity": 1,
  "LaunchOffset": [0, -0.2, 0],
  "LaunchDirection": [0, 0, 1]
}
```

Use `CRUISE_MISSILE` for a profile explicitly registered as cruise in FFA. Use
`COORDINATE_MISSILE` for other coordinate-guided profiles, including future HIMARS/SCUD-like
ground launchers. Both categories require `CoordinateProfile`; neither falls back to an
unguided, laser-guided, or generic projectile when configuration is absent or invalid.

Existing optional `Model`, `Texture`, `Item`, `Scale` and `Capacity` fields remain available.
`LaunchOffset` is a vehicle-local displacement of at most eight blocks. `LaunchDirection` is
a nonzero finite vehicle-local vector with length at most 128 and is normalized at launch.
It defaults to forward `[0, 0, 1]`; an upward ground launcher can use `[0, 1, 0]` or an authored
angled direction. The existing profile boost phase uses that direction and inherited vehicle
velocity before returning to the profile's normal guidance. Stationary FFA launchers retain
their original behavior.

## Author separate weapon slots

Reference the store from the vehicle's normal armament definition under
`data/<namespace>/sbw/aircraft_armaments/<vehicle>.json`. For example, the `Singles` section can
define two independently targetable launchers:

```json
{
  "Singles": [
    {
      "Id": "launcher_left",
      "Name": "Left launcher",
      "Position": [-1, 1, 0],
      "AllowedStores": ["example:cruise"]
    },
    {
      "Id": "launcher_right",
      "Name": "Right launcher",
      "Position": [1, 1, 0],
      "AllowedStores": ["example:cruise"]
    }
  ]
}
```

This is a section to merge into a complete existing armament definition, not a complete vehicle
definition. Each mount `Id` is one selectable weapon and one coordinate assignment. A paired
mount alternates its left/right physical launch positions but shares one assignment. A capacity
greater than one also shares one assignment across its rounds. Give each tube/missile a separate
mount ID when it needs independent coordinates. Each accepted trigger releases one missile.

The vehicle terminal lists equipped coordinate weapon slots. Assign a target to a slot there,
then select that weapon through the ordinary vehicle weapon controls and fire normally. Terminal
actions never arm, select, or fire weapons. Assignments persist per vehicle, slot, store identity,
profile, and dimension; firing does not consume the assignment. Switching selected weapons does
not move targets. Refitting a different store/profile cannot use the old assignment. Clear or
replace the target explicitly when needed. The world origin is valid; missing targets are null.

## Flight profiles and launch admission

This requires the matching FFA coordinate-launch integration on both client and server. The
built-in cruise profile IDs are `ballistics:basic`, `ballistics:cluster`, and
`ballistics:hypersonic_cruise`. The `ballistics:kh55` test profile matches the basic cruise profile
with acceleration reduced from `0.025` to `0.0125` blocks/tick². It uses BVP's existing Kh-29
geometry temporarily when the pack renderer is available. Known ballistic profiles can be referenced by
`COORDINATE_MISSILE`; drones cannot use this path.

FFA addons can register individual `FlightProfiles.Profile` definitions with
`registerCruise(profile, rangeBlocks)` or `registerCoordinate(profile, rangeBlocks)` before use.
Custom horizontal range is bounded to 16–15,000 blocks. The existing one-argument registration
API remains available; native profile ranges continue to come from FFA configuration. Custom
flight and explosion tuning belongs in the registered FFA profile. A store references that
profile explicitly rather than guessing missile behavior from a model or name.

The server resolves the equipped selected slot, checks its registered profile, reads only that
slot's saved coordinates, rechecks the current dimension/build height/world border, and checks
horizontal launch range. It also validates the operating seat and loaded launch origin. Missing
targets, out-of-range targets, invalid profiles, unavailable FFA, and failed entity insertion
reject the shot before ammunition, cooldown, or equipment revision changes. Client packets do
not provide launch targets or profiles. These checks do not depend on keeping the terminal open.

Existing laser-guided missiles, air-to-air seekers and other weapons retain their admission rules.
Offline validation is covered by coordinate schema/transaction tests and FFA target-persistence,
range and boost checks. These do not replace in-game multiplayer acceptance.
