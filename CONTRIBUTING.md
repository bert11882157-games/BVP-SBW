# Contributing

- Keep changes focused and explain observable behavior and compatibility.
- Write comments about behavior, invariants, and maintenance. Keep development-session notes,
  personal tooling details, and temporary investigation narratives outside production mod code.
- Preserve source attribution, license notices, and asset credits.
- Report the tests actually run, including any build or runtime limitations.
- Do not commit credentials, local game installations, dependencies, or compiled JARs.
- BVP runtime resources in `src/generated/resources` are release inputs. Coordinate resource
  changes with the maintained development source before the next promoted snapshot.

Changes are normally developed in the development workspace and promoted here as reviewed source
snapshots. A direct fix in this repository must be carried back to development before the next
promotion. Publication does not silently overwrite modifications made here.

See [build notes](docs/BUILDING.md) for the current build boundaries. A source snapshot or a
passing publication inventory check is not evidence that a mod build or in-game test passed.
