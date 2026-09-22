# Source build contract

Export `build-support/` unchanged beside BVP's build files. The production tree remains
exactly `bvp/` and `sbw/`, with `SOURCE_SNAPSHOT.json`. No source-layout text rewrite is
needed: BVP defaults to `src/main/java`; development can explicitly pass
`-PbvpSourceRoot=../../src/main/java`.

Java 17 and Node.js 20+ are required. The supported entry point is:

```
node bvp/build-support/build-release.mjs --tree . --meshloader-manifest /path/dependency.json
```

The external manifest declares one separately source-built `sbwmeshloader` dependency:
`schema: 1`, `id: "sbwmeshloader"`, `version: "0.1.1"`, `path`, exact `size`, and
`sha256`. A relative path resolves beside that manifest. Obtain this artifact from the
approved dependency release, retaining its source-build evidence privately. Neither
meshloader sources nor a private development path join the production export. Missing
or mismatched dependencies fail closed; there is no installed-game/library fallback.

Dependency maintainers can use `--meshloader-source <source-project>` instead of
`--meshloader-manifest`. This compiles the dependency separately, records its source
hashes, and writes a reusable `meshloader-dependency.json`. Its sources remain outside
the BVP/SBW tree.

`--offline` reuses already-resolved dependencies; `--gradle-home <directory>` selects
a writable Gradle cache. Gradle's documented `GRADLE_RO_DEP_CACHE` may point at an
existing cache parent containing `modules-2`. No machine path is embedded in the recipe.

The pipeline builds the matching SBW sources first, then consumes the exact named API,
resolved compiler dependencies and named-to-SRG mapping from that build. A checked-in
lexer translates only canonical BVP SRG identifier tokens into disposable build output.
Comments, literals and canonical files remain unchanged. The matching SBW ForgeGradle
remapper maps the external meshloader dependency to compiler names, and the compiled
BVP candidate back to runtime names. This avoids a second inconsistent Forge ABI.

Runtime remapping also receives the matching named SBW API and named meshloader JAR
as inheritance libraries. Their hashes are checked before and after remapping and
again inside the Gradle task. Reverse dependency remapping uses runtime SBW instead.
Compiler dependencies alone do not contain SBW's own superclass definitions.

Dependency identities come from the matching SBW source graph (currently Forge
1.20.1-47.2.0, Parchment 2023.08.13-1.20.1, GeckoLib 4.4.6, SimpleBedrockModel
2.3.3-forge-mc1.20.1, MAE 1.1.2 and SBW's other resolved dependencies). Actual
versions/file hashes, mapping inputs and tool identities are recorded in private proof.
The bridge additionally resolves BVP's existing client renderer APIs in a separate
compiler configuration: [Komodo 1.2.3 (CurseForge file 8545181)](https://www.curseforge.com/minecraft/mc-mods/sbw-komodo-rendering-accelerator/files/8545181)
and [Flywheel Forge API 1.0.5](https://maven.createmod.net/dev/engine-room/flywheel/flywheel-forge-api-1.20.1/1.0.5/).
These use the matching Forge mappings and join the recorded compiler/remapper inputs;
they do not change SBW's production dependencies or get bundled into BVP. Forge's
argument-file support is enabled for remapping to fit Windows process limits.
No legacy SuperbWarfare filename, patched historical base JAR, authoring tool or
generator is an input to this build.

Source-owned `src/main/resources` overlays generated resources, first-wins. Every
static file is byte-verified in the archive. `build/libs/*-mapped.jar` is an intermediate
development artifact, never a Forge runtime release. Final runtime output and schema-1
`bvp-sbw-source-build` proof are written below `build/source-build/`.

The static-resource and `bvp-runtime-abi` checks run on the final runtime JAR, after
remapping. The ABI gate checks the SBW superclass chain, sync/save overrides,
representative particle tick and renderer methods, exact descriptors and super-call
targets, while preserving genuine SBW API method names. It reads class files without
loading the game. Missing or stranded named overrides prevent successful build proof.

The bridge pins archive versions to the frozen source version, removes ambient
`Implementation-Timestamp` metadata, and disables preserved file times while enabling
reproducible entry order. Real authors, notices and source attribution are retained.
These settings improve repeatability; only an actual repeated-output comparison can
establish byte-for-byte identity. Build-proof timestamps record real execution times,
not invented provenance. No release is complete without `BUILD_PROOF.json`.
Compilation/package proof does not assert gameplay or equivalence to an older JAR.
