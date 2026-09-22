# Engineering standards

These standards apply to maintained source, tests, build helpers, and public documentation.
Use them for new and edited code. Keep unrelated inherited formatting intact so each review
can distinguish a behavior change from a mechanical cleanup.

## Responsibility and boundaries

See [Module boundaries](MODULE_BOUNDARIES.md) for the current ownership and observation contracts.

- Keep shared vehicle mechanisms in SBW and vehicle-pack definitions and policies in BVP.
  Prefer an existing typed extension point to a new pack-specific branch in shared code.
- Give each mutable state transition one owner. Other components request a transition or
  consume a snapshot; they do not independently repeat its admission checks or side effects.
- Extract a component when it owns a coherent invariant and has narrower inputs than its caller.
  Moving methods into a helper that still mutates the whole vehicle is not sufficient isolation.
- Keep authoritative gameplay on the logical server. Rendering and audio consume accepted state
  and events. Presentation failure must not change ammunition, damage, movement, or admission.
- Keep authored inputs, derived compilation results, and generated runtime resources distinct.
  Correct generated resources through their maintained inputs and the supported generation path.
- Separate calculation, validation, mutation, and external effects when their ordering matters.
  Make the order visible at the transaction boundary and test it there.

## Naming and data

- Use names that identify the domain quantity or responsibility, not an implementation phase.
  Preserve established public names when compatibility requires them.
- Include units where they are not obvious: `speedBlocksPerTick`, `turnRateDegreesPerSecond`,
  or `penetrationMm`. State conversion boundaries explicitly; do not silently mix units.
- Identify coordinate frames in APIs and documentation: model, hull-local, turret-local, and
  world positions are not interchangeable. Distinguish points from directions and rotations.
- Keep persistent IDs, resource paths, NBT keys, network fields, enum values, and public method
  signatures stable during a naming cleanup. Their spelling may be an external contract.
- Represent missing, invalid, and valid-zero values deliberately. Avoid undocumented sentinel
  values and fallbacks that hide invalid input.
- Make immutable values the default. Scope mutable caches to their owner and document their
  invalidation identity, lifetime, bounds, and thread or logical-side requirements.

## Comments and documentation

Explain the current contract, a non-obvious reason, units, ordering, or compatibility constraint.
Do not narrate an editing session or repeat what a clear statement already says. Keep a historical
explanation when a supported compatibility path would otherwise appear unnecessary.

Prefer a precise comment such as:

```java
// Zero penetration is a valid curve sample; only missing samples use the scalar fallback.
```

Avoid status labels such as "stage complete", personal work assignments, and temporary debugging
stories in maintained source. Put durable operational instructions in the relevant documentation.
When a TODO is necessary, state the missing behavior and a concrete condition for resolving it.

Document public extension points with inputs, units or frames, side effects, failure behavior,
and side/thread constraints when applicable. Keep comments synchronized with implementation.
Preserve licenses, source attribution, asset credits, and required provenance.

## Layout and language conventions

- Follow `.editorconfig`: UTF-8, four spaces for Java/Kotlin and two for JavaScript/JSON,
  final newlines, and no trailing whitespace in code. Batch scripts use CRLF.
- Use the surrounding language's established style. Keep a soft line target of 100 characters;
  prioritize clarity for long identifiers, signatures, imports, literals, and resource paths.
- Do not reorder declarations, imports, annotations, or initialization for appearance alone.
  Separate an approved formatting-only change from a behavior change.
- Prefer descriptive local names, straightforward control flow, and small cohesive methods.
  Avoid single-use abstractions that obscure a simple operation or duplicate existing ownership.
- Do not edit compiler mapping names, reflection targets, mixin bindings, or addon-facing methods
  as ordinary naming cleanup. Such changes need a separate compatibility migration.
- Keep build output, downloaded dependencies, workstation paths, and local credentials out of source.

## Errors, resources, and performance

- Validate external data at its boundary, before mutating live state. Failure must leave a defined
  state and explain the rejected condition without exposing credentials or private payloads.
- Catch exceptions only where recovery or useful context is possible. Do not silently convert an
  unexpected exception into success, empty data, or a partially applied transaction.
- Use structured resource lifetime management. Restore temporary state in `finally` when needed.
- Bound queues, caches, retained events, and diagnostics. Give each an expiry or cleanup owner.
- In tick, firing, rendering, and particle paths, account for allocations and repeated lookups.
  Optimize a demonstrated cost and measure the changed work; do not claim a frame-rate improvement
  from code inspection or object counts alone.

## Tests and compatibility

- Exercise production behavior at the narrowest relevant boundary. Prefer deterministic inputs
  and assertions about results or state over tests that merely search source text.
- Cover the successful transition, rejected input, valid-zero cases, repeat/replay behavior, and
  reload or cleanup boundaries where those cases are meaningful.
- Use serialization round trips and malformed-input cases for persisted or transported data.
  Preserve field identities and version rules unless the change explicitly migrates them.
- For a non-semantic cleanup, compare executable source structure and compile the affected code.
  A comment scanner is not a compiler, behavior test, binary-compatibility gate, or gameplay test.
- Preserve addon-facing inheritance and overload contracts. Use the existing compatibility checks
  for changes that can affect runtime overrides, descriptors, or protocol boundaries.
- Run focused tests once their final inputs are frozen. Reuse unchanged passing checks and caches.
  Record exactly what ran and distinguish source verification from runtime acceptance.

## Review checklist

1. Does the change have one clear purpose and preserve unrelated behavior?
2. Is each invariant owned in one place, with explicit units, frames, and failure behavior?
3. Are comments useful, current, concise, and independent of the editing process?
4. Are public APIs, resource identifiers, persistence, and wire contracts preserved or migrated?
5. Do focused tests exercise the actual changed code, and did the affected compilation pass?
6. Are generated resources, dependencies, and release inputs accounted for when they changed?

A source cleanup does not authorize a release or establish in-game acceptance. Follow the
[build](BUILDING.md) and [release](RELEASING.md) contracts for the selected version.
