# Tu-95MS coordinate-missile test frame

This testing configuration uses the existing Tu-95MS entity. It retains both tail guns and
adds eight independent bomb-bay stations through the pylon system, a 500-block radar, rapid flares, chaff, and a
30% higher authored roll rate (12 to 15.6 degrees/second). The flight controller still applies
its normal speed, damage, and control-authority limits.

The flare magazine holds 128 individual flares, released at 20 flares/second (10 left/right
pairs per second), followed by the existing 20-second automatic reload. Chaff and the
radar-warning receiver are enabled.

## KH-55 and hardpoints

`berts_vehicle_pack:kh55` is a single-round `CRUISE_MISSILE` pylon store. Its
`CoordinateProfile` is `ballistics:kh55`, supplied by the matching Fire From Above build.
The profile uses FFA's base cruise-missile tuning with boost acceleration reduced from
0.025 to 0.0125 blocks/tick squared, with a dedicated maximum horizontal launch range of
1,500 blocks. Damage, speed, and other base-profile behavior remain unchanged. This is game
testing data, not a historical missile simulation.

Aircraft-launched cruise missiles hold their release Y while turning toward the assigned target,
then change elevation on final approach. They do not climb to FFA's standard launch altitude,
including when released from a climbing aircraft. Ground launchers retain their normal climb.

The store and optional BVP flight renderer use the supplied KH-55 model and texture, converted
from the maintained Blockbench source. FFA can draw its native fallback while the optional
mesh loads. No new meshloader dependency is introduced.

Bay 1 through Bay 8 each hold one missile and own a separate coordinate assignment. Their
existing persistent IDs, `belly_1` through `belly_8`, are retained. The eight loaded missiles
appear as one selectable KH-55 weapon with a combined ammunition count. Successful shots
advance through loaded bays in authored order, with a shared maximum of two launches per second.

The temporary rack is inside the central bomb bay. Stored missiles are concealed; launch
offsets release the full model below the fuselage with hull clearance. The existing Tu-95
mesh has no animated bay doors. The maintained armament and store JSONs contain the exact
mount and release geometry. Alignment during maneuvers still requires in-game observation.

## Test procedure

Use matching BVP, SBW, and FFA builds on both server and client, plus the normal BVP runtime
dependencies. Existing Tu-95MS vehicles obtain the definition on resource reload/restart;
the loadout starts empty until fitted through the standard pylon editor.

For this test candidate, the maintainer supplied the meshloader 0.1.1 binary because its source
is unavailable. Its exact size and SHA-256 are recorded as an external build dependency.
BVP and SBW are built from the reviewed sources; this does not claim a source build or
source audit of that supplied meshloader binary.

1. Stop a Tu-95MS on the ground and occupy its pilot seat. Open Aircraft Loadout (default
   `I`), choose KH-55 for Bay 1 through Bay 8, and press Apply. Save a preset if desired.
2. Open the vehicle control terminal. Cycle through the eight weapon slots and assign each
   a different X/Y/Z target, using the coordinate screen or map. Verify all eight markers.
3. Close the target map, which returns directly to the flight view for aircraft. Select KH-55
   in the primary or secondary weapon slot and use that slot's normal fire control.
   Cruise missiles do not use the legacy `L`
   pylon-release shortcut. Confirm that exactly its missile disappears and flies toward its
   captured point while that bay's saved coordinates clear. Check release-height
   retention during heading changes and the later final
   approach. Repeat while the plane climbs or descends. The terminal has no launch or arming control.
4. Clear the next loaded bay's assignment and attempt to fire it. It must retain its missile and refuse
   the shot. Repeat with an out-of-range point. Changing or clearing another slot must not
   redirect a missile already in flight.
5. Keep KH-55 selected and fire all eight missiles. Each shot must use its own bay's coordinates;
   successful shots reduce the combined ammunition count and advance to the next loaded bay.
   Rapid primary/secondary inputs must not exceed two launches per second. A ninth launch
   must fail until a stopped ground refit and new target assignment.
   Confirm unfired assignments survive save/reload and another dimension cannot reuse them.
6. Exercise the normal flare and chaff controls. Check the 128-flare magazine, rapid release
   and automatic reload, plus the chaff response to a radar-guided threat.
7. Check that the aircraft does not display itself on radar, that other vehicles use model
   names, and that Dominions own/allied/enemy contacts are blue/green/red respectively.
8. On other aircraft, fit multiple copies of the same supported missile, bomb or pod. Check
   that the selector shows one entry per munition type with the combined ammunition count,
   that different types remain separate, and that native reload, seeker and firing behavior
   remain available after the first mount runs out.
9. Compare frame times in the same position/view with empty pylons and eight loaded missiles,
   then repeat after firing, refitting, and a resource reload. The bottom-right HUD must show
   one KH-55 entry throughout, with ammunition falling from 8 to 0. Check external stores on
   another aircraft as well. The TU-95's concealed bay stores should add no store mesh draws.

## Source ownership

The test configuration is maintained in `bvp/src/main/resources` overlays for the Tu-95MS
vehicle, flight reference, armament definition, and KH-55 store. The source-build entry point
packages these ahead of the frozen generated mirrors and verifies their bytes in the final JAR.
Carry these overlays into the separate development workspace before its next source promotion.
The shared weapon-slot and targeting mechanisms remain in SBW and FFA.
