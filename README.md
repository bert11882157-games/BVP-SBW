# Bert's Vehicle Pack and Superb Warfare fork — Production

Mod source code and runtime resources for Minecraft 1.20.1 / Forge.

| Directory | Contents |
| --- | --- |
| `bvp/` | Bert's Vehicle Pack Java code, committed runtime resources, and project metadata |
| `sbw/` | The customized Superb Warfare fork, including Java/Kotlin sources and resources |
| `docs/` | Build, release, and licensing notes |

This repository contains the mods, not the separate asset-authoring and vehicle-generation
workspace. BVP's generated runtime resources are committed here alongside its handwritten Java
sources. No external workspace path is needed to locate those sources.

## Current snapshot

See `SOURCE_SNAPSHOT.json` for the snapshot label and SHA-256 inventory. A review snapshot is
not automatically a stable release. Stable versions are identified by reviewed release tags.
There is no claim that creating this repository ran compilation or gameplay tests.

## Building

Java 17 is required. Both projects retain their Gradle wrappers. See [build notes](docs/BUILDING.md)
before attempting a build: SBW uses its Gradle project; BVP's existing runtime/SRG source mapping
and release-classpath dependency requirements have not yet been converted into a standalone build.

Runtime dependencies remain required. In particular, BVP requires the SBW fork and
`sbwmeshloader`; the mesh-loader source and unrelated content packs are not part of this repository.
Dependency JARs and compiled mod releases are not committed into this source tree.

Textures and audio use Git LFS. After cloning, run `git lfs install` and `git lfs pull`.

## Contributions and releases

Read [CONTRIBUTING.md](CONTRIBUTING.md) and [release notes](docs/RELEASING.md).
Preserve mod behavior, resource identifiers, and client/server compatibility when editing.

## Licensing and credits

There is no single license applied to everything in this repository. Original notices and
credits are retained. Read [licensing notes](docs/LICENSING.md), `sbw/LICENSE`, and the notices
in `bvp/` before redistributing code or assets.
