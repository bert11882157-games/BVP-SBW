# Build status and dependencies

## Superb Warfare fork

The `sbw/` directory retains its Java/Kotlin sources, generated resources, Gradle configuration,
wrapper JAR, upstream README files, and license. With Java 17 installed, its normal build entry is:

```powershell
cd sbw
.\gradlew.bat build
```

On Unix, use `sh gradlew build`. The repositories and dependencies declared by the project may
need network access. No clean build of this new checkout is claimed by the initial source import.

## Bert's Vehicle Pack

The source layout is local to `bvp/`:

- `src/main/java/`: canonical mod Java code.
- `src/main/resources/`: source-owned runtime resource overlay.
- `src/generated/resources/`: committed generated runtime resources.
- `gradle/`, wrapper scripts, and Gradle project files: retained build metadata.

The source-owned resource overlay must be packaged together with generated resources. In
particular, do not drop the vehicle item model, static icons, or reticle by packaging only the
generated directory.

The source-build entry point requires Java 17 and Node.js 20 or newer:

```powershell
node bvp/build-support/build-release.mjs --tree . --meshloader-manifest PATH_TO_DEPENDENCY_MANIFEST.json
```

See [the build contract](../bvp/build-support/README.md) for dependency input details. The
meshloader is an external, separately source-built dependency, identified by version, exact size,
and SHA-256. Its source and compiled JAR are not committed to this repository. Other dependencies
come from the matching SBW project's resolved build graph, plus the bridge's isolated BVP
renderer API configuration for published Komodo 1.2.3 and Flywheel 1.0.5. Dependency resolution may need network
access, or a populated Gradle dependency cache.

The entry point builds the sibling SBW source project and consumes its compiler API and mapping
outputs. Canonical BVP Java keeps its runtime/SRG names; only disposable compilation sources are
translated to the matching compiler names, and the output JAR is remapped for Forge runtime use.
The vehicle generator, an installed game, and old patched mod JARs are not build inputs.

BVP defaults to project-local `src/main/java`. Development can explicitly choose another canonical
source root with `-PbvpSourceRoot=...`; publication does not rewrite its build file. Both resource
directories are packaged, with the source-owned overlay taking precedence.

Do not install a `*-mapped.jar`: it is an intermediate compiler artifact. Use only the final runtime
artifact identified by the build proof. Direct BVP `gradlew build` is not the end-to-end runtime
release entry point. The source-build command and its evidence, rather than a copied snapshot
alone, establish which sources and dependencies produced each candidate.

Runtime override validation is part of the release build. The remapper must receive the matching
SBW and meshloader inheritance libraries; compilation alone cannot prove that vehicle save/sync,
tick, and rendering overrides retain their required runtime names and descriptors.

## Validation before tagging

Run focused source tests, compile/package through the applicable supported build process, and
record matching artifact hashes and required runtime checks. Do not label an untested source
snapshot stable solely because its files copied successfully.
