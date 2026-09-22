# TaP / Flans exhaust provenance

This is an API port of the supplied LabJac/Flans particle implementations EntityAfterburn, EntityFMFlame and EntityFMSmoke. Original source authorship and existing licenses remain applicable. The source tree is Mr-Monorisu-Brazila-master, com/flansmod/client/particle; emitter behavior comes from common/driveables/EntityDriveable and DriveableType.

Unmodified textures from the supplied Flan's Mod Khrisna Mk5.jar:
- ValkEx.png -> textures/particle/tap_afterburner.png; SHA256 A474BDED70BA5325DBA37B46F57C422B628C988B44C47F2CE8D1EC72837BE9C7.
- FMFlame.png -> textures/particle/tap_exhaust_flame.png; SHA256 3B5B8516C0CDCEC293246B3372747C43DA85E2B428815788324225A75A8F526F.
- FMSmoke.png -> textures/particle/tap_exhaust_smoke.png; SHA256 6EECC3DDA4C791AA3A1199959DC455E326B3FD25EE6C1F159C4785507D50465F.

Preserved rendering: source RGB/alpha/size curves, six-tick flames, 16-tick rising smoke, full-bright flames, ordinary lit smoke, constant flame velocity, ground collision expiry, blended depth-tested quads without depth writes, and alpha > 0.001. FMFlame creates one FMSmoke companion. Source particleAfterBurn entries each emit once per tick (the source ignores their emitRate); high-throttle AddEmitter entries retain their interval, repeats, extents and velocity.

Aircraft-specific TaP configurations are pinned in the development authoring catalog. Matching flame patterns are available for MiG-29, Eurofighter, F-16C and F-22A. The F-22 retains its two banks and extended aft pattern. Where no matching flame configuration exists, the real FMFlame/FMSmoke implementation is used as an explicitly qualified generic effect at the authored aircraft nozzles; this is not a claim of exact TaP settings for those aircraft.

Adaptations for Minecraft 1.20.1: atlas sprites and a dedicated shader replace immediate OpenGL drawing; the vanilla particle vertex shader provides current lightmap/fog integration. Patterns are rebased and spatially fitted to the BVP model's authored nozzles, retaining source relative spacing. First queued interpolation is aligned with the aircraft, then source world velocity applies without persistent aircraft-motion inheritance. Emission is gated by accepted BVP afterburner state and capped at 64 total flame/smoke particles per client tick across 64 observed aircraft; no server particle packets are added. Existing ATGM/rocket effects are separate.