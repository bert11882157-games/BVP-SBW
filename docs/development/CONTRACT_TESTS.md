# Contract lock-in tests

`sbw/src/test/kotlin/com/atsuishio/superbwarfare/architecture/ContractSnapshotTest.kt` scans the SBW and BVP main
sources and compares what worlds, servers and the BVP addon depend on against golden files in
`sbw/src/test/resources/architecture/` (`sbw-contracts.golden.txt`, `bvp-contracts.golden.txt`):

| Section | What it locks |
| --- | --- |
| `registry` | every `register*("id")` / `mod*Tag("id")` call: registry IDs and the helper that registers them |
| `packets` | the SBW packet table (class, index, delivery, limits, rate) and the BVP `registerMessage` table |
| `network-constants` | protocol versions and packet ID constants |
| `synched-data` | `SynchedEntityData.defineId` fields per class, in declaration order |
| `keys` | string keys used with `put*` / `get*` / `contains*` / `remove` (NBT, ForgeData, JSON) |
| `config` | Forge config keys (`define*("key")`) |
| `properties` | system properties and environment variables read by name |

Entries are keyed by the declaring file's package and name, not its path, so moving a file between
`src/main/java` and `src/main/kotlin` does not change the snapshot.

## Modes

- **Default** (`gradlew test`): additions pass; anything removed or changed fails. That covers a registry ID that
  disappears or changes its helper, a packet whose index or schema changes, a class whose existing synched-data fields
  no longer lead its list in the same order, and an NBT, config or property key that disappears. Set-like sections
  are compared by value regardless of the declaring class, so code may move between classes.
- **Strict** (`gradlew test -Pcontract.strict=true`): the snapshot must equal the golden files exactly, additions
  included. Use it for behaviour-neutral refactors such as the Kotlin source-root move, together with
  `tools/jar_compare/jar_compare.py` (JAR entries byte-identical to the baseline build).

## Regenerating the golden files

After a reviewed, intended contract change (a new or retired registry ID, packet, synched field or key):

```
cd sbw
gradlew test --tests "com.atsuishio.superbwarfare.architecture.ContractSnapshotTest" "-Pcontract.update=true"
```

(`CONTRACTS_UPDATE=1` in the environment does the same.) Then review the golden-file diff in the commit: every removed
or changed line is a compatibility decision. In PowerShell, quote `-P` arguments that contain a dot, as above.

On a failure the test writes the current snapshot to `sbw/build/contracts/<name>-contracts.actual.txt` and lists the
offending entries.

`DebugFeaturePolicyTest.java` imports BVP classes and does not compile in an SBW-only Gradle run. Move it aside for the
run and put it back, or run the suite from the release build.
