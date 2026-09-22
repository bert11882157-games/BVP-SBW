# BVP dependency boundary

No dependency JARs are committed here, and the build does not search this directory for a
historical Superb Warfare filename.

The sibling `sbw/` source build supplies its matching compiler API, Minecraft/Forge mappings,
GeckoLib, Simple Bedrock Model, MAE, and other resolved compiler dependencies. Their actual
versions and file hashes are retained with the source-build proof.

The separately source-built SBW Mesh Loader is an explicit external input. Obtain the compatible
dependency artifact and its manifest from the release maintainer, subject to its distribution
permissions. The manifest declares schema `1`, id `sbwmeshloader`, version, local JAR path, exact
file size, and SHA-256. Relative paths resolve beside the manifest. There is no installed-game or
legacy-library fallback.

See [the build contract](../build-support/README.md) and [build instructions](../../docs/BUILDING.md).
Release checkpoints retain exact dependency files privately; production records only their
public identities. Meshloader source and dependency binaries remain outside this repository.
