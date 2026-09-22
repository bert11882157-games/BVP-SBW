# Module boundaries

These boundaries keep shared mechanisms independent of pack policy and make state transitions
testable without a running game. They complement the [engineering standards](ENGINEERING.md).

## Vehicle mechanisms

`VehicleEntity` remains the addon compatibility facade. Public overrides, synchronized fields,
save keys, and transport identities belong to that facade; new domain calculations should not
receive it merely for convenient access to unrelated state.

- `VehicleModuleStateService` privately owns generic module health, destruction/repair
  transitions, save/load, and snapshot invalidation. `VehicleModuleStateAccess` exposes only
  module operations. `VehicleEntityModuleStateAccess` adapts legacy synchronized health fields
  and preserves virtual addon definition/state/setter dispatch.
- `VehicleWeaponStateCache` owns the identity relationship between detached live weapon state,
  authoritative snapshots, and configuration. A failed reconstruction cannot partially replace
  a binding. Publication advances the snapshot identity without reloading live state.
- `GroundDriveCalculator` takes immutable, phase-specific values and returns values. It has no
  entity, world, mutable engine, effect-provider, or callback dependency.
- `VehicleGroundMotionService` samples the environment and commits calculated results.
  `GroundDrivePhases` preserves the observation barriers: controls commit before the energy
  hook; steering commits before longitudinal view sampling; yaw commits before final thrust.
  The energy hook can change state, so later phases capture fresh values. Virtual view-vector
  and target-speed methods remain authoritative; never substitute a synthesized direction.

World collision/resistance queries and particles remain in the ground adapter. Track and wheel
arithmetic have intentionally different steering/damage order. Do not merge them for appearance.

## Damage lifecycle

`VehicleDamageTransaction` owns rejection precedence and commit ordering. Its damage-only
`VehicleDamageAccess` cannot expose a vehicle, level, projectile, renderer, or network sender.
Source and destruction context remain opaque. Reads after callbacks stay live; eager snapshots
would change the observed health, maximum, or wreck state.

`VehicleDamageLifecycleService` is the entity/world adapter. It owns source classification,
native OBB-part policy, attribution and timestamps, and the existing hull/effect callbacks.
`VehicleEntity` retains its established damage facade and damage-modifier extension point.

- Resolved damage checks server authority, amount, destroyed state, and source admission before
  debug or commit effects. It never repeats the caller's armor or damage-modifier calculation.
- Lethal requests retain the current-health floor and maximum-health-plus-one cap. Applied damage
  is measured from the accepted hull state, not assumed to equal the requested amount.
- Destruction uses the supplied context, or obtains the default only when destruction is needed.
  A callback that already wrecked the vehicle is not followed by another destruction request.
- Legacy `hurt` keeps its separate admission, modifier, native commit, and vanilla callback order.
  Do not silently apply the resolved-damage admission rules to that compatibility route.
- Rejection is side-effect free at this boundary. An exception propagates and prevents later
  phases; already committed world effects are not rolled back. This is not a rollback protocol.

Legacy module identifiers are data values. Their namespace is the compile-time mod constant;
constructing them does not invoke the mod entry point. Their public fields and serialized IDs
remain compatibility contracts.

## Projectile policy

SBW resolves and serializes projectile snapshots. BVP registers common-side policies for its
round identities, tracer extensions, terrain rules, and cadence constraints.

`ProjectileProfilePolicy` receives immutable inputs, not a live projectile. Its
`ProjectilePresentationPatch` can change only extensions, trail mode, and render scale. Shared
code retains the original combat descriptor, collision, motion, luminance, and identity. An
explicit NONE/SUPPRESS belt policy remains the final suppress-all authority.

Missing addon policies leave native defaults unchanged. Presentation-provider exceptions are
isolated; they cannot reject an otherwise valid shot. Required gameplay metadata
is different: an absent combat profile remains a real admission failure.

`VehicleWeaponCadencePolicies` applies addon event-rate constraints after schedule selection.
It retains heat, sound, repeat/release, projectile multiplicity, and other schedule fields.
Existing round-query and projectile assignment methods remain compatibility adapters.

## Accepted impacts

The order is armor resolution, a compact impact-recipe publication, then optional presentation.

- Block-impact admission uses classification and a complete combat profile. Optional effect data
  is never required. A declared profile with invalid combat remains rejected, including when a
  legacy visual-only classifier returns a fallback; unprofiled native defaults remain intact.
- `BvpImpactFragmentPlanner` owns pure count, scale, direction, speed, and lifetime calculations.
  Each client supplies independent randomness and creates bounded visual fragments. The server
  never builds a fragment fan. Ordinary penetration and unverified collisions do not emit fans.
- `BvpImpactFragmentCommitter` captures accepted context and publishes one visual recipe to
  nearby clients. Its bounded replay admission is independent of client rendering. Optional
  delivery failure cannot alter the parent projectile's already resolved gameplay outcome.
- `BvpImpactPresentationProvider` owns particles, lights, audio, and replacement-visual admission.
  It does not call fragment planning or insertion.

Impact fragments are exclusively client-side visuals. They do not create projectile entities,
apply damage, report hits, or consume server collision work. Primary projectiles, blast damage,
and deliberately fired grapeshot retain their existing gameplay. Deprecated server fragment-spawn
entry points remain binary-compatible but return without insertion. Persisted fragment provenance
is retained solely to retire obsolete entities before their server gameplay lifecycle.

Replay admission retains the existing 0.0625-block Euclidean-distance threshold and 256-key bound.
The planner rejects pathological fans above 256 fragments before allocating them; maintained
profiles remain well within that limit.

## Verification and limits

Behavioral tests cover module transitions, persistence, addon dispatch, snapshot identity,
presentation failure isolation, policy defaults, and compatibility-sensitive shot/tick ordering.
Ground tests compare exact arithmetic with a frozen reference and exercise observation barriers.
Dependency tests prevent domain calculations from reaching whole vehicles or pack-specific schema
keys from returning to shared projectile mechanisms.

`ModuleBoundaryTest` checks compiled references for the damage transaction and result values,
module storage and its value contracts, ground calculation/phase values, shot ordering/receipts,
and weapon cache/snapshot/slot state. Nested generated classes are included. SBW's compiled Java
and Kotlin classes must not have typed dependencies on the BVP package.

These guards inspect descriptors, generic signatures, annotations, field/method/type instructions,
and dynamic-call references. They do not infer reflective dependencies from arbitrary strings,
prove provider purity, or replace behavior tests. New value types must satisfy the same boundary;
do not broaden an allowlist merely to admit a convenient service or singleton.

These checks do not replace multiplayer acceptance or performance measurement. Phase values add
short-lived objects; their runtime cost must be measured before claiming a performance improvement.
Keep new changes behind these boundaries and extend their tests instead of adding another owner
for the same state transition.
