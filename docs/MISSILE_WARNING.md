# Missile approach warning

Every vehicle a missile homes on warns its crew. The crew hears a soft beep about once a second and sees a compact
strip near the top of the screen: a gently pulsing MISSILE tag followed by the vehicle's decoy buttons with their
keys: FLARE [V] and CHAFF [B] on aircraft, or SMOKE [V] on a ground vehicle. Each button that would break the lock
has a steady red outline; nothing flashes. When the right decoy is not fitted, the strip says NO FLARES, NO CHAFF or
NO SMOKE. A missile no decoy can fool, such as a wire-guided one, shows DECOYS INEFFECTIVE.

| Missile | Counts while | Effective decoy |
|---|---|---|
| Native SBW missiles (Igla, 9M336, Javelin, AGM-65, Kh-39, swarm drones) | it names the vehicle (or a rider) as its target and is not lost or distracted | any decoy: flares, or smoke on ground vehicles |
| Native wire-guided missiles | the same | none |
| FFA missiles (aircraft pylons and ground SAMs) | it was launched with a lock on the vehicle and still flies within 60° of it, or is within 16 blocks | INFRARED and GROUND_INFRARED: flares; ACTIVE_RADAR, SEMI_ACTIVE_RADAR and ACTIVE_SURFACE_RADAR: chaff; ANTI_RADIATION: none |

How it works:

- `IncomingMissileWarning` (server) samples every level at the end of each tick.
  - FFA missiles are recorded at launch by `AircraftMissileLauncher.launch` and `launchAt`, with the seeker mode and
    the lock's `TargetUUID`.
  - FFA does not report its missiles' current target, so the warning follows each missile's heading. A missile a
    decoy pulls away stops the warning once it turns more than 60° from the vehicle.
  - An FFA missile launched without a lock is not tracked.
- `AircraftCountermeasures` publishes the flags in bits 27-30 of the countermeasure levels (`AircraftCountermeasureWire.incoming`)
  for every vehicle, ground vehicles included.
  - While a missile is tracked, the RWR's own missile chirp is muted, so the alarm does not double up.
- `IncomingMissileOverlay` and `IncomingMissileAlarm` (client) draw the cue and sound the alarm for every rider of
  that vehicle.
