# Tu-95MS coordinate-missile test frame

This testing configuration uses the existing Tu-95MS entity. It retains both tail guns and
adds eight independent belly pylon stations, a 500-block radar, rapid flares, chaff, and a
30% higher authored roll rate (12 to 15.6 degrees/second). The flight controller still applies
its normal speed, damage, and control-authority limits.

The flare burst releases 12 individual flares at 20 flares/second (10 left/right pairs per
second). Chaff and the radar-warning receiver are enabled; existing burst limits and cooldowns
remain in use.

## KH-55 and hardpoints

`berts_vehicle_pack:kh55` is a single-round `CRUISE_MISSILE` pylon store. Its
`CoordinateProfile` is `ballistics:kh55`, supplied by the matching Fire From Above build.
The profile uses FFA's base cruise-missile tuning with boost acceleration reduced from
0.025 to 0.0125 blocks/tick squared. Damage, range, speed, and other base-profile behavior
remain unchanged. This is game testing data, not a historical missile simulation.

Aircraft-launched cruise missiles hold their release Y while turning toward the assigned target,
then change elevation on final approach. They do not climb to FFA's standard launch altitude,
including when released from a climbing aircraft. Ground launchers retain their normal climb.

The store reuses the existing Kh-29L mesh and texture on the pylons. The optional BVP client
renderer reuses the Kh-29 flight mesh for launched KH-55s; FFA can draw its native fallback
while the optional mesh loads. No duplicate artwork or new meshloader dependency is introduced.

Each station holds one missile and owns its own coordinate assignment. Belly 1/2 are the
forward row, followed by 3/4, 5/6, and 7/8 aft. Their hull-local positions are:

| Stations | X | Y | Z |
| --- | --- | --- | --- |
| Belly 1 / 2 | -0.65 / +0.65 | 2.05 | 8.0 |
| Belly 3 / 4 | -0.65 / +0.65 | 2.05 | 3.5 |
| Belly 5 / 6 | -0.65 / +0.65 | 2.05 | -1.0 |
| Belly 7 / 8 | -0.65 / +0.65 | 2.05 | -5.5 |

These temporary points place the reused mesh beneath the authored fuselage bounds, with
clearance between adjacent missiles. They are independent pylon attachment points, rather
than wing pairs sharing a target. Alignment and clearance during maneuvers still require
in-game observation.

## Test procedure

Use matching BVP, SBW, and FFA builds on both server and client, plus the normal BVP runtime
dependencies. Existing Tu-95MS vehicles obtain the definition on resource reload/restart;
the loadout starts empty until fitted through the standard pylon editor.

For this test candidate, the maintainer supplied the meshloader 0.1.1 binary because its source
is unavailable. Its exact size and SHA-256 are recorded as an external build dependency.
BVP and SBW are built from the reviewed sources; this does not claim a source build or
source audit of that supplied meshloader binary.

1. Stop a Tu-95MS on the ground and occupy its pilot seat. Open Aircraft Loadout (default
   `I`), choose KH-55 for Belly 1 through Belly 8, and press Apply. Save a preset if desired.
2. Open the vehicle control terminal. Cycle through the eight weapon slots and assign each
   a different X/Y/Z target, using the coordinate screen or map. Verify all eight markers.
3. Close the terminal, select the desired Belly weapon using the normal vehicle weapon
   controls, and fire. Confirm that exactly its missile disappears and flies toward its own
   saved point. Check release-height retention during heading changes and the later final
   approach. Repeat while the plane climbs or descends. The terminal has no launch or arming control.
4. Clear a slot's assignment and attempt to fire it. It must retain its missile and refuse
   the shot. Repeat with an out-of-range point. Changing or clearing another slot must not
   redirect a missile already in flight.
5. Fire each loaded station once. A ninth launch must fail until a stopped ground refit.
   Confirm each assignment survives save/reload and that another dimension cannot reuse it.
6. Exercise the normal flare and chaff controls. Observe the rapid flare burst and chaff
   response to a radar-guided threat. Countermeasure burst limits and cooldowns still apply.
7. Check that the aircraft does not display itself on radar, that other vehicles use model
   names, and that Dominions own/allied/enemy contacts are blue/green/red respectively.

## Source ownership

The test configuration is maintained in `bvp/src/main/resources` overlays for the Tu-95MS
vehicle, flight reference, armament definition, and KH-55 store. The source-build entry point
packages these ahead of the frozen generated mirrors and verifies their bytes in the final JAR.
Carry these overlays into the separate development workspace before its next source promotion.
The shared weapon-slot and targeting mechanisms remain in SBW and FFA.
