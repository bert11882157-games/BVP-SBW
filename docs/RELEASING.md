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

`SOURCE_SNAPSHOT.json` inventories managed source files with SHA-256 hashes. For a recorded release
candidate, it also contains the private build-record hash and matching artifact/dependency
identities. It contains no private evidence paths or logs. A source-only draft has status `review`;
a build-bound snapshot has status `release-candidate`. Neither status authorizes publication or
certifies gameplay. The reviewed stable release tag records the publication decision.

Do not merge a development workspace's unrelated directories or task history into this repository.
Do not force-push or rewrite an existing public history as part of ordinary promotion. Preserve
upstream source notices and credits. Direct changes here must also reach development so the next
snapshot incorporates them.
