# Stable source releases

This is the production mod-source repository. Development takes place in a separate workspace.

1. Finish the chosen development changes and their focused validation.
2. Freeze a labeled source/resource checkpoint containing BVP, the SBW fork, and build metadata.
3. Build the frozen inputs; retain the matching mod JARs, exact dependency inputs and build evidence
   privately. Review the source diff, resources, licensing, and test results.
4. After the tested version is approved, integrate that recorded checkpoint into a clean checkout
   of this repository. Do not substitute later development changes.
5. Review and commit the resulting changes. Tag the tested version and push that commit/tag.
6. Publish matching compiled mod artifacts separately, with their SHA-256 hashes.
7. Update [the download list](../DOWNLOADS.md) with the release page, exact versioned JAR and
   checksum links, matching companion builds and required dependencies. Keep previous entries.
   Label drafts and prereleases accurately, explain draft access, and update temporary draft
   links when publishing. Retain the prominent download link in the root README.

`SOURCE_SNAPSHOT.json` inventories managed source files with SHA-256 hashes. For a recorded release
candidate, it also contains the private build-record hash and matching artifact/dependency
identities. It contains no private evidence paths or logs. A source-only draft has status `review`;
a build-bound snapshot has status `release-candidate`. Neither status authorizes publication or
certifies gameplay. The reviewed stable release tag records the publication decision.

Do not merge a development workspace's unrelated directories or task history into this repository.
Do not force-push or rewrite an existing public history as part of ordinary promotion. Preserve
upstream source notices and credits. Direct changes here must also reach development so the next
snapshot incorporates them.

## Every build is pushed and merged

Every BVP/SBW build that is installed for testing is also pushed here: its sealed checkpoint tree is
copied over main on a sync/<version> branch (files that only this repository has, such as
tools/, are kept), merged into main through a pull request, and published as a prerelease with
the BVP, SBW and FFA JARs and their .sha256 files, listed in [DOWNLOADS.md](../DOWNLOADS.md).
main therefore always matches the newest build. The next development checkpoint starts from that
build's tree and takes DOWNLOADS.md and this file from main.

## Pinned compiler dependencies

The release build (`bvp/build-support/build-release.mjs --compiler-extras-manifest`) compiles against Komodo 1.2.3 and
Flywheel 1.0.5, pinned by size and SHA-256 in the development workspace's `work/claude_jobs/compiler-extras.json`.
The JARs it names must live at a permanent location, `work/claude_jobs/compiler-deps/`, never inside a checkpoint or
candidate folder under `work/production_repository_snapshots/`: those folders are cleaned up, and a manifest pointing
into a deleted one fails the build step with ENOENT (as it did for 0.11.122beta, before packaging, so no version was
spent). If a pin must change, copy the new JAR into `compiler-deps/` and update its size and hash together.
