# Stable source releases

This is the production mod-source repository. Development takes place in a separate workspace.

1. Finish the chosen development changes and their focused validation.
2. Prepare a labeled source snapshot containing only BVP and the SBW fork.
3. Review its file inventory, source diff, resource contents, licensing, and build evidence.
4. Promote the approved snapshot into a clean checkout of this repository.
5. Review and commit the resulting changes. Tag the tested version and push that commit/tag.
6. Publish matching compiled mod artifacts separately, with their SHA-256 hashes.

`SOURCE_SNAPSHOT.json` inventories managed source files with SHA-256 hashes. It is an integrity
record, not a signature, authorship declaration, or proof of successful testing. Its status remains
`review` until release review; the stable release tag records the publication decision.

Do not merge a development workspace's unrelated directories or task history into this repository.
Do not force-push or rewrite an existing public history as part of ordinary promotion. Preserve
upstream source notices and credits. Direct changes here must also reach development so the next
snapshot incorporates them.
