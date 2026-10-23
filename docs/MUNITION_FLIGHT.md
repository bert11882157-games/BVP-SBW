# Munition flight on the client

Every SBW projectile is flown by the logical server. What differs is how a client draws it. Each munition uses
exactly one of these paths, chosen by `FastThrowableProjectile`:

| Path | Chosen by | Munitions | Client behaviour |
| --- | --- | --- | --- |
| Deterministic step | `smoothsBallisticFlight()` | bullets, shells, unguided rockets (`SmallRocketEntity`, `MediumRocketEntity`), dumb and legacy bombs | Flies the same air step as the server; step-aligned corrections only when the real flight leaves it (`BallisticSync`). |
| Flight stream | `interpolatesFlight()` | every `MissileProjectile` (native SBW missiles, wire/TV/laser/top-attack `WireGuideMissileEntity`, swarm drones), guided bombs (laser, GPS, TV), RPG rockets | Plays back the server's own end-of-tick states (`MunitionFlightPlayback`). |
| Vanilla tracker | neither | grenades, mines and other slow throwables | Unchanged. |

## Why the flight stream

A guided munition's flight depends on server-only inputs: seekers, designations, the TV operator's line of sight,
command guidance, motors with server-held state. A client copy cannot fly it, so the earlier paths both showed
jitter:

- Native SBW missiles took the vanilla tracker: the client snapped to each tracker position and then flew its own
  unguided step. Tracker packets arrive unevenly (none in one client tick, two in the next), so the drawn missile
  advanced in steps of 0, 2 and 3 ticks of travel.
- Wire-guided and TV missiles and guided bombs used the deterministic-step path with a predictor that could not
  include guidance, the motor or the corkscrew. Every tick deviated, every tick sent a correction, and each
  correction replaced the velocity and dragged the position.

The flight stream does what multiplayer games do for objects a client cannot simulate. The server publishes the
state once per server tick, after the whole tick has run (`MunitionFlightSync`, so subclass guidance, motors and
rotation are included). The client keeps up to 24 states and draws the path a little behind the newest one:
Hermite curves through the buffered positions, with Catmull-Rom tangents, and the server's own yaw and pitch.

The delay adapts to arrival jitter. It is one tick on an even link and grows to cover the latest-arriving state of
the last five seconds, capped at four ticks. The playback clock runs at one server tick per client tick and
corrects by at most 5%. When the stream stalls it extrapolates for two ticks and then holds, so it never jumps
backwards. A gap of more than eight ticks (a residency pause, a teleport) restarts playback at the new state.

`MunitionFlightPlaybackTest` replays a turning, corkscrewing missile over a link with 3 ticks of latency and 0-2
ticks of in-order jitter. Over 20 seeds, the played-back path is as smooth as the server path (RMS step change
about 0.23 blocks/tick, the turn's own curvature). The old predictor measured about 0.55 with single-tick errors
over 2 blocks. The tracker snap measured over 4 with errors of 12 blocks or more.

## Native missile turning

`MissileProjectile.turn` used separate yaw and pitch clamps. Near vertical the yaw is undefined, so the clamp could
whip the nose around its own axis by up to the turn limit each tick. Diving AGM-65/Kh-39 and top-attack Javelins
pass through that region. The turn now rotates the nose toward the target as one rotation about the axis between
the two directions (`MissileTurn`), by 95% of the remaining angle and at most the same degrees per tick. It keeps
the yaw when the nose points straight up or down.

## Contracts

- Packet ID 87 `MunitionFlightMessage`, protocol 48 (`packet-schema-manifest-v48.tsv`).
- `AerialBombEntity` spawn data gains one boolean (guided), after the drag value.
- `interpolatesFlight()` must evaluate the same on both sides from spawn data. It must not be combined with
  `smoothsBallisticFlight()`.
- The TV seeker's origin check allows for the playback delay (`AircraftTvGuidance.command`).
