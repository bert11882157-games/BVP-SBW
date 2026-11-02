# Helmet-mounted sight lock

`AircraftHelmetSight` (SBW, `api/aircraft`) lets the pilot of an aircraft with a helmet sight lock an air-to-air
missile by looking at the target instead of pointing the nose at it. The seeker is still Fire From Above's: the helmet
only changes the line FFA searches along (`AircraftMissileHooks.updateLock`'s forward vector) and its cone.

## Data

Both sides must opt in. An aircraft without a sight, or a seeker that cannot be slaved to one, behaves exactly as before.

Aircraft armament definition (`sbw/aircraft_armaments/<aircraft>.json`):

```json
"HelmetSight": {"Name": "Shchel-3UM", "MaxOffBoresightDegrees": 60}
```

Store (`sbw/aircraft_stores/<store>.json`), inside `Guidance`, air-to-air stores only:

```json
"HelmetOffBoresightDegrees": 45
```

The usable angle is the smaller of the two. The loader rejects a sight outside 15-120° and a store limit outside
15-90°, or on anything but an `AIR_TO_AIR` store.

## Behaviour

1. **Cueing.** The pilot holds freelook (the free-camera key, default C) in first-person view, not the targeting pod or a
   TV seeker, and looks more than 2° away from the nose (the forward crosshair). A green circle labelled HMS appears
   where they look. The mouse-aim camera's lead during ordinary flying never cues the seeker. While the angle is inside the limit, the seeker searches a 4°
   cone around that line, so the aircraft under the circle is acquired and dwells to a lock as usual (`LockTicks`).
   Past the limit the circle turns grey ("HMS LIMIT") and the seeker returns to the nose.
2. **Tracking.** Once the seeker holds a target it follows that target, with the store's own cone, while the target
   stays inside the limit. The pilot can release freelook and keep the lock. Looking at a different aircraft (more
   than 8° from the held one) re-cues onto it.
3. **Launch.** A helmet-cued round leaves towards its target rather than along the nose, because FFA only admits a
   launch with the target inside the seeker cone of the launch direction. The aircraft's own velocity still dominates
   the initial flight, so the missile turns onto the target after release.
4. **Tooltip.** The loadout screen shows "Can helmet lock · ±N° off boresight" on capable stores, then the aircraft's
   sight, or "This aircraft has no helmet sight".

## Network and server cost

The client reports its line of sight in the aircraft's own frame as a `HELMET` armament request (`Direction`, a unit
vector), at most every 2 ticks and only while it is cueing (re-sent after 0.25° of movement, or every 6 ticks as a
keep-alive), plus one empty report when the pilot looks back. The server ignores a report older than 10 ticks, checks
both opt-ins and the angle itself, and drops stale-epoch reports silently. Per seeker tick the extra work is one map
lookup for aircraft without a sight, and a few vector operations plus one FFA state read for aircraft with one.

## Roster

Aircraft (sight, limit): MiG-29, Su-27, Su-30, J-11A (Shchel-3UM, 60°), Su-35S (Shchel-3UM-1, 60°), J-10A (60°),
J-15D and FC-31 (90°), F-15C, F-15E, F-15EX, F-16C, F/A-18C, F/A-18E (JHMCS, 90°), Eurofighter Typhoon (Striker, 90°),
JAS 39 Gripen (Cobra, 90°).

Left out on purpose: F-22A (no helmet display in service), F-14, F-16A/B, MiG-21, MiG-23, Su-25/Su-39, Rafale and
Su-57 (no cue-capable missile on their stations), Tornado IDS, A-10A. A-10C (Scorpion) and Yak-141 are left out until
their missile cueing is confirmed.

Seekers (limit): R-73 (45°), AIM-9X, IRIS-T, ASRAAM, PL-10 (90°), PL-8 (40°), AIM-9M (30°, JHMCS slaving within its
gimbal). Not cued: R-60/R-60M, R-27T/ET, AIM-9B to AIM-9P, PL-5/PL-7, Magic, radar missiles and MANPADS.

`AircraftHelmetSightTest` pins both rosters, so a change must be made there deliberately.
