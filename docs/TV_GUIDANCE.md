# TV-guided munitions and the laser designator

## TV seeker view (AircraftTvGuidance / AircraftTvClient)

A store with `"Category": "COMMAND_GUIDED", "CommandGuidance": {"Mode": "TV"}` is an SBW guided missile
(`WireGuideMissileEntity`, launched by `AircraftLaserLauncher`). A bomb with `"Bomb": {"Mode": "TV"}` is an
`AerialBombEntity`. Both work the same way after release:

1. The pilot's view switches to the munition's seeker: a world-stabilised camera a little ahead of its nose.
2. The mouse moves the crosshair (line of sight), within a 70° gimbal around the flight path. Scroll zooms (1-8x).
3. Every 2 ticks the client sends the line of sight and the seeker position it drew. The server checks the sender
   is the munition's operator, that the position is the munition's and that the line is inside the gimbal. It then
   lases along the line through loaded terrain and vehicles. The hit is the aim point. When the hit is a vehicle,
   the seeker tracks that vehicle.
4. The munition flies at the aim point: a missile with proportional steering inside its gimbal, a bomb with its fins.
5. The pod key leaves the view. The seeker stays locked on the last point, or on the tracked vehicle, so a TV
   munition works as man-in-the-loop (GBU-15, KD-88) or lock-and-leave (AGM-65B, Kh-25MT, KAB-500Kr).
6. The view ends when the munition is gone for 10 ticks, the pilot leaves the aircraft, or the level changes.

A TV bomb no longer needs an FFA lock to release. When it has one, the seeker starts on the locked target. The
first aim point of a TV missile is the pilot's laser designation, when there is one.

Stores: AGM-65B (A-10, F-16B/C, F/A-18E, F-111F), Kh-25MT (Su-17, Su-24, Su-25), KD-88 (J-11A, J-15D) and the
bombs GBU-15 and KAB-500Kr-OD. The data is written by `tools/munition_audit/guidance_fix.py`, which is idempotent.

## Laser designator and rangefinder

- **Aircraft, no pod.** The laser follows the pilot's crosshair: the centre of the first-person view on the frame
  the key was pressed. The origin is checked against the aircraft; the hull nose is only a fallback.
- **Aircraft, pod.** Unchanged: the pod's own direction, checked against the gimbal.
- **Painted vehicles.** A spot on a vehicle is stored in the vehicle's hull frame. Missiles and laser bombs read
  the live spot every tick. The marker and the pod follow it every 5 ticks, unless the pilot has slewed the pod away.
- **Ground FCS.** The laser runs along the gunner's sight line, which is the ray the range is applied to, not along
  the barrel. A super-elevated gun no longer lases over the target.
- **Far terrain (Voxy distances).** Saved chunks along the ray are read from disk.
  - A chunk that was never saved, or cannot be read, is skipped. It no longer fails the whole ray.
  - Chunks saved from "surface" on are accepted.
  - Chunks from older game versions are upgraded first.
  - Twelve reads stay in flight.
