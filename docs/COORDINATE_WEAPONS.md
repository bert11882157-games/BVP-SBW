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
angled direction. Ground launches use that direction and inherited vehicle velocity during
the existing boost phase before returning to the profile's normal guidance. Stationary FFA
launchers retain their original behavior.

Registered cruise profiles launched from aircraft instead hold their actual release Y until
final approach. They inherit horizontal carrier motion, suppress launch climb/descent, and
turn toward the assigned target without climbing to a standard altitude. The common aircraft
guidance owns heading, altitude hold and final approach, using the profile's speed, acceleration
and turn limits; custom profile guidance callbacks remain in use for ground/stationary launches.
The aircraft mode and release altitude persist across save/reload.

## Author independently targeted mounts

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
definition. Each mount `Id` owns one coordinate assignment. Identical equipped stores share
one selectable weapon type and a combined ammunition count. A paired
mount alternates its left/right physical launch positions but shares one assignment. A capacity
greater than one also uses one assignment at a time. Give each tube/missile a separate
mount ID when it needs independent coordinates. The selected type releases the next loaded
mount in authored order; each accepted trigger releases one missile. A rejected shot does not
skip its mount or consume ammunition. Different store IDs remain separate weapon types.

The vehicle terminal lists equipped coordinate weapon slots. Assign a target to a slot there,
then select the munition type through the ordinary primary/secondary weapon controls and fire.
The terminal continues to list each mount separately so every missile can have its own target. Terminal
actions never arm, select, or fire weapons. Assignments persist per vehicle, slot, store identity,
profile, and dimension until a successful launch clears the fired slot's assignment. The missile
keeps its own captured coordinates and continues to that point. Failed launches retain the target,
and other slots keep their assignments. Assign a new target for each later shot from the same slot.
Switching selected weapons does not move targets. Refitting a different store/profile cannot use
the old assignment. The world origin is valid; missing targets are null.

## Flight profiles and launch admission

This requires the matching FFA coordinate-launch integration on both client and server. The
built-in cruise profile IDs are `ballistics:basic`, `ballistics:cluster`, and
`ballistics:hypersonic_cruise`. The `ballistics:kh55` test profile matches the basic cruise profile
with acceleration reduced from `0.025` to `0.0125` blocks/tick² and a dedicated 2,000-block
horizontal launch range. BVP supplies the maintained KH-55 model and texture converted from
the provided Blockbench asset. Known ballistic profiles can be referenced by
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
Cruise launches share a ten-tick cooldown per vehicle, limiting successful releases to two per
second across both weapon controls and all cruise-missile types. Failed shots do not start it.
Offline validation is covered by coordinate schema/transaction tests and FFA target-persistence,
range and boost checks. These do not replace in-game multiplayer acceptance.
