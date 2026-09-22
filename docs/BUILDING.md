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

Current BVP Java uses runtime/SRG identifiers. The retained MDK requests official mappings and
local dependency JARs. Therefore `gradlew build` is **not yet a supported standalone BVP release
recipe**. Production compilation currently uses the maintained runtime classpath/release process.
Making that process independently reproducible is a follow-up build task, not a reason to publish
the vehicle generator or authoring workspace.

`bvp/libs/README.md` lists the inherited dependency expectations. Dependency JARs are intentionally
not copied here. A release must use the matching SBW fork and dependency versions; an old upstream
SBW binary is not a substitute for the changed fork APIs.

## Validation before tagging

Run focused source tests, compile/package through the applicable supported build process, and
record matching artifact hashes and required runtime checks. Do not label an untested source
snapshot stable solely because its files copied successfully.
