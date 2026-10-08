# Downloads

Bert's Vehicle Pack and the matching Superb Warfare fork target Minecraft 1.20.1 / Forge.
Download the mod JARs from the release assets. GitHub's source-code ZIP and TAR archives are
source snapshots, not installable mods.

This repository is private: sign in to a GitHub account with repository access before opening
the links. Draft releases additionally require repository write access.

## Stable releases

No stable release is currently published in this repository. See [all releases](https://github.com/bert11882157-games/BVP-SBW/releases)
for the complete release history.
The `0.9.19beta` source tag currently has no GitHub Release or compiled download assets.

## Latest published testing build: 0.11.121beta

[Helicopter overhaul: pylon loadouts for every armed helicopter, half of real top speed and climb, gunner-fired guided missiles](https://github.com/bert11882157-games/BVP-SBW/releases/tag/v0.11.121beta).
Install all three matching JARs on clients and servers, replacing older active copies:

| Component | Mod JAR | Checksum |
| --- | --- | --- |
| BVP 0.11.121beta | [JAR](https://github.com/bert11882157-games/BVP-SBW/releases/download/v0.11.121beta/bvp-main-v0.11.121beta.jar) | [SHA-256](https://github.com/bert11882157-games/BVP-SBW/releases/download/v0.11.121beta/bvp-main-v0.11.121beta.jar.sha256) |
| SBW 0.11.121beta | [JAR](https://github.com/bert11882157-games/BVP-SBW/releases/download/v0.11.121beta/bvp_superbwarfare-v0.11.121beta.jar) | [SHA-256](https://github.com/bert11882157-games/BVP-SBW/releases/download/v0.11.121beta/bvp_superbwarfare-v0.11.121beta.jar.sha256) |
| FFA 1.0.6-bvp.21 | [JAR](https://github.com/bert11882157-games/BVP-SBW/releases/download/v0.11.121beta/fire-from-above-1.0.6-bvp.21.jar) | [SHA-256](https://github.com/bert11882157-games/BVP-SBW/releases/download/v0.11.121beta/fire-from-above-1.0.6-bvp.21.jar.sha256) |

The source build and the aircraft unit tests passed (two failures in the projectile-damage integration test predate this build). In the
isolated test runtime every helicopter spawned and loaded its data, and fitted pylon stores appeared on the models; flying to the new top
speeds, firing from the pylons and gunner-operated laser and command-guided stores have not been tested in game yet.

## Earlier published testing build: 0.11.120beta

[Unguided rocket pods fire 600 per minute, alternating sides, full-auto; missile warning shows unlimited countermeasures correctly](https://github.com/bert11882157-games/BVP-SBW/releases/tag/v0.11.120beta).
Install all three matching JARs on clients and servers, replacing older active copies:

| Component | Mod JAR | Checksum |
| --- | --- | --- |
| BVP 0.11.120beta | [JAR](https://github.com/bert11882157-games/BVP-SBW/releases/download/v0.11.120beta/bvp-main-v0.11.120beta.jar) | [SHA-256](https://github.com/bert11882157-games/BVP-SBW/releases/download/v0.11.120beta/bvp-main-v0.11.120beta.jar.sha256) |
| SBW 0.11.120beta | [JAR](https://github.com/bert11882157-games/BVP-SBW/releases/download/v0.11.120beta/bvp_superbwarfare-v0.11.120beta.jar) | [SHA-256](https://github.com/bert11882157-games/BVP-SBW/releases/download/v0.11.120beta/bvp_superbwarfare-v0.11.120beta.jar.sha256) |
| FFA 1.0.6-bvp.21 | [JAR](https://github.com/bert11882157-games/BVP-SBW/releases/download/v0.11.120beta/fire-from-above-1.0.6-bvp.21.jar) | [SHA-256](https://github.com/bert11882157-games/BVP-SBW/releases/download/v0.11.120beta/fire-from-above-1.0.6-bvp.21.jar.sha256) |

All 17 unguided rocket weapons on aircraft and helicopters now fire one rocket every 0.1 s while the trigger is held,
alternating left and right; damage, ammunition and reload are unchanged. The source build, the rocket-pod order and
countermeasure unit tests passed, and the rocket aircraft spawned on the isolated dedicated server; holding fire in
game has not been tested yet.

## Earlier published testing build: 0.11.119beta

[Cockpit glass reworked on every aircraft, flat-pane windows for helicopters and transports, faster flares, longer-range air-to-air missiles and radar, lighter missile damage, smaller missile warning, blue friendly markers, afterburners visible to other players](https://github.com/bert11882157-games/BVP-SBW/releases/tag/v0.11.119beta).
Install all three matching JARs on clients and servers, replacing older active copies:

| Component | Mod JAR | Checksum |
| --- | --- | --- |
| BVP 0.11.119beta | [JAR](https://github.com/bert11882157-games/BVP-SBW/releases/download/v0.11.119beta/bvp-main-v0.11.119beta.jar) | [SHA-256](https://github.com/bert11882157-games/BVP-SBW/releases/download/v0.11.119beta/bvp-main-v0.11.119beta.jar.sha256) |
| SBW 0.11.119beta | [JAR](https://github.com/bert11882157-games/BVP-SBW/releases/download/v0.11.119beta/bvp_superbwarfare-v0.11.119beta.jar) | [SHA-256](https://github.com/bert11882157-games/BVP-SBW/releases/download/v0.11.119beta/bvp_superbwarfare-v0.11.119beta.jar.sha256) |
| FFA 1.0.6-bvp.21 | [JAR](https://github.com/bert11882157-games/BVP-SBW/releases/download/v0.11.119beta/fire-from-above-1.0.6-bvp.21.jar) | [SHA-256](https://github.com/bert11882157-games/BVP-SBW/releases/download/v0.11.119beta/fire-from-above-1.0.6-bvp.21.jar.sha256) |

FFA 1.0.6-bvp.21 is required: it carries the doubled air-to-air missile lifetime. The source build passed all build
checks and the countermeasure unit tests. In the isolated two-player test runtime the game loaded, the new cockpit
glass rendered, the flare cycle measured 12 flares in about 1.25 s followed by the 10 s cooldown, the compact missile
warning appeared while a missile homed and cleared afterwards, and a teammate's jet showed the blue marker. The
afterburner seen by another player was not confirmed in that run; the rest is not yet tested in game. Known issue: with
unlimited countermeasures (creative mode or a creative ammo box) the missile warning labels flares and chaff EMPTY.

## Earlier published testing build: 0.11.113beta

[Jet stores and cockpits, countermeasures, Strv 103 hull laying, LAV-AD belt, Music & Sounds crash fix, smooth AAM seeker circle on servers](https://github.com/bert11882157-games/BVP-SBW/releases/tag/v0.11.113beta).
Install all three matching JARs on clients and servers, replacing older active copies:

| Component | Mod JAR | Checksum |
| --- | --- | --- |
| BVP 0.11.113beta | [JAR](https://github.com/bert11882157-games/BVP-SBW/releases/download/v0.11.113beta/bvp-main-v0.11.113beta.jar) | [SHA-256](https://github.com/bert11882157-games/BVP-SBW/releases/download/v0.11.113beta/bvp-main-v0.11.113beta.jar.sha256) |
| SBW 0.11.113beta | [JAR](https://github.com/bert11882157-games/BVP-SBW/releases/download/v0.11.113beta/bvp_superbwarfare-v0.11.113beta.jar) | [SHA-256](https://github.com/bert11882157-games/BVP-SBW/releases/download/v0.11.113beta/bvp_superbwarfare-v0.11.113beta.jar.sha256) |
| FFA 1.0.6-bvp.20 | [JAR](https://github.com/bert11882157-games/BVP-SBW/releases/download/v0.11.113beta/fire-from-above-1.0.6-bvp.20.jar) | [SHA-256](https://github.com/bert11882157-games/BVP-SBW/releases/download/v0.11.113beta/fire-from-above-1.0.6-bvp.20.jar.sha256) |

The source build passed all build checks. Flare and chaff release, item use and the creative ammo box were checked in game in the isolated test runtime; the rest is not yet tested in game.

## Earlier published testing build: 0.11.21beta

[Aircraft and ground expansion, fitted stores and cockpit corrections](https://github.com/bert11882157-games/BVP-SBW/releases/tag/v0.11.21beta).
Install all three matching JARs on clients and servers, replacing older active copies:

| Component | Mod JAR | Checksum |
| --- | --- | --- |
| BVP 0.11.21beta | [JAR](https://github.com/bert11882157-games/BVP-SBW/releases/download/v0.11.21beta/bvp-main-v0.11.21beta.jar) | [SHA-256](https://github.com/bert11882157-games/BVP-SBW/releases/download/v0.11.21beta/bvp-main-v0.11.21beta.jar.sha256) |
| SBW 0.11.21beta | [JAR](https://github.com/bert11882157-games/BVP-SBW/releases/download/v0.11.21beta/bvp_superbwarfare-v0.11.21beta.jar) | [SHA-256](https://github.com/bert11882157-games/BVP-SBW/releases/download/v0.11.21beta/bvp_superbwarfare-v0.11.21beta.jar.sha256) |
| FFA 1.0.6-bvp.12 | [JAR](https://github.com/bert11882157-games/BVP-SBW/releases/download/v0.11.21beta/fire-from-above-1.0.6-bvp.12.jar) | [SHA-256](https://github.com/bert11882157-games/BVP-SBW/releases/download/v0.11.21beta/fire-from-above-1.0.6-bvp.12.jar.sha256) |

The [FFA source and separate release](https://github.com/xenoperk/Fire-From-Above/releases/tag/v1.0.6-bvp.12)
carry the identical companion JAR. Keep Dominions, meshloader and other required pack dependencies.
The frozen source build and focused cockpit/loadout gameplay checks passed. Sustained firing,
live command/TV guidance, moving F-111F sweep/release and moving radar range remain unverified.
9P148/Gepard armor plate authoring is pending. See the release notes for the full test scope.

## Earlier published testing build: 0.11.9beta

[ATGM flight, physical wires and distant smoke release](https://github.com/bert11882157-games/BVP-SBW/releases/tag/v0.11.9beta).
Full development roster. Install all three matching JARs on clients and servers:

| Component | Mod JAR | Checksum |
| --- | --- | --- |
| BVP 0.11.9beta | [JAR](https://github.com/bert11882157-games/BVP-SBW/releases/download/v0.11.9beta/bvp-main-v0.11.9beta.jar) | [SHA-256](https://github.com/bert11882157-games/BVP-SBW/releases/download/v0.11.9beta/bvp-main-v0.11.9beta.jar.sha256) |
| SBW 0.11.9beta | [JAR](https://github.com/bert11882157-games/BVP-SBW/releases/download/v0.11.9beta/bvp_superbwarfare-v0.11.9beta.jar) | [SHA-256](https://github.com/bert11882157-games/BVP-SBW/releases/download/v0.11.9beta/bvp_superbwarfare-v0.11.9beta.jar.sha256) |
| FFA 1.0.6-bvp.7 | [JAR](https://github.com/bert11882157-games/BVP-SBW/releases/download/v0.11.9beta/fire-from-above-1.0.6-bvp.7.jar) | [SHA-256](https://github.com/bert11882157-games/BVP-SBW/releases/download/v0.11.9beta/fire-from-above-1.0.6-bvp.7.jar.sha256) |

ATGMs have bounded bob/corkscrew that settles with speed. Physical cable appears only on confirmed
wire-guided ammunition and sags with paid-out distance. Distant smoke preserves lighting when terrain
is unavailable. Keep existing Dominions, meshloader and other dependencies; remove superseded copies
of these three mods before installation. Builds and focused source/package checks passed; gameplay
testing remains pending. Uploaded JAR/checksum sizes and SHA-256 hashes were verified.

The release tag records the exact sealed runtime source. Later download-documentation updates do
not alter the binaries. See [all releases](https://github.com/bert11882157-games/BVP-SBW/releases)
for intermediate versions not listed below.

## Earlier published testing build: 0.10.2beta

[Aircraft ground support and breakup release](https://github.com/bert11882157-games/BVP-SBW/releases/tag/v0.10.2beta).
Full development roster. Install both matching JARs:

| Component | Mod JAR | Checksum |
| --- | --- | --- |
| BVP 0.10.2beta | [JAR](https://github.com/bert11882157-games/BVP-SBW/releases/download/v0.10.2beta/bvp-main-v0.10.2beta.jar) | [SHA-256](https://github.com/bert11882157-games/BVP-SBW/releases/download/v0.10.2beta/bvp-main-v0.10.2beta.jar.sha256) |
| SBW 0.10.2beta | [JAR](https://github.com/bert11882157-games/BVP-SBW/releases/download/v0.10.2beta/bvp_superbwarfare-v0.10.2beta.jar) | [SHA-256](https://github.com/bert11882157-games/BVP-SBW/releases/download/v0.10.2beta/bvp_superbwarfare-v0.10.2beta.jar.sha256) |

Keep [Fire From Above 1.0.6-bvp.2](https://github.com/xenoperk/Fire-From-Above/releases/tag/v1.0.6-bvp.2),
Dominions, meshloader and other pack dependencies. SBW protocol 39 requires matching client/server JARs.
This release fixes ground pitch and gear settling, wing breakup, F/A-18E spawning and pylon mass limits,
and adds four-section fuselage breakup on terrain impact. Source/build/package checks passed;
gameplay testing is pending. Restart Minecraft after installation.
The version tag identifies the exact sealed source; later download-documentation changes do not alter the JARs.

## Earlier published testing build: 0.10.1beta

[Aircraft breakup, radar and guidance release](https://github.com/bert11882157-games/BVP-SBW/releases/tag/v0.10.1beta).
This is the full development roster, including the registered F/A-18E. Install the matching set:

| Component | Mod JAR | Checksum |
| --- | --- | --- |
| BVP 0.10.1beta | [JAR](https://github.com/bert11882157-games/BVP-SBW/releases/download/v0.10.1beta/bvp-main-v0.10.1beta.jar) | [SHA-256](https://github.com/bert11882157-games/BVP-SBW/releases/download/v0.10.1beta/bvp-main-v0.10.1beta.jar.sha256) |
| SBW 0.10.1beta | [JAR](https://github.com/bert11882157-games/BVP-SBW/releases/download/v0.10.1beta/bvp_superbwarfare-v0.10.1beta.jar) | [SHA-256](https://github.com/bert11882157-games/BVP-SBW/releases/download/v0.10.1beta/bvp_superbwarfare-v0.10.1beta.jar.sha256) |
| FFA 1.0.6-bvp.2 | [JAR](https://github.com/xenoperk/Fire-From-Above/releases/download/v1.0.6-bvp.2/fire-from-above-1.0.6-bvp.2.jar) | [SHA-256](https://github.com/xenoperk/Fire-From-Above/releases/download/v1.0.6-bvp.2/fire-from-above-1.0.6-bvp.2.jar.sha256) |

Keep existing Dominions, meshloader and other pack dependencies. SBW protocol is 38 and FFA protocol is 19;
clients and server need matching versions. Restart the game after replacing the old active JARs.
Source compilation, focused checks, sealed packaging and installation passed. Gameplay testing is pending.
The version tag identifies exact sealed sources; later download-documentation commits do not alter the JARs.

## Earlier published testing build: 0.10.0beta

[F/A-18E, ammunition and effects release](https://github.com/bert11882157-games/BVP-SBW/releases/tag/v0.10.0beta).
Install this matched set on clients and the server:

| Component | Mod JAR | Checksum |
| --- | --- | --- |
| BVP 0.10.0beta | [JAR](https://github.com/bert11882157-games/BVP-SBW/releases/download/v0.10.0beta/bvp-main-v0.10.0beta.jar) | [SHA-256](https://github.com/bert11882157-games/BVP-SBW/releases/download/v0.10.0beta/bvp-main-v0.10.0beta.jar.sha256) |
| SBW 0.10.0beta | [JAR](https://github.com/bert11882157-games/BVP-SBW/releases/download/v0.10.0beta/bvp_superbwarfare-v0.10.0beta.jar) | [SHA-256](https://github.com/bert11882157-games/BVP-SBW/releases/download/v0.10.0beta/bvp_superbwarfare-v0.10.0beta.jar.sha256) |
| FFA 1.0.5-bvp.6 | [JAR](https://github.com/xenoperk/Fire-From-Above/releases/download/v1.0.5-bvp.6/fire-from-above-1.0.5-bvp.6.jar) | [SHA-256](https://github.com/xenoperk/Fire-From-Above/releases/download/v1.0.5-bvp.6/fire-from-above-1.0.5-bvp.6.jar.sha256) |

Keep Dominions, meshloader and the other pack dependencies. FFA protocol18 requires the matching update.
Build, source checks, package checks and installation passed; gameplay testing is pending.
The release tag identifies the sealed source. Later download-documentation commits do not change the JARs.

## Earlier published testing build: Tu-95 KH-55 test 3

[BVP / SBW — consolidated HUD and pylon performance](https://github.com/bert11882157-games/BVP-SBW/releases/tag/v0.1.1-kh55-test.3)
is a published testing prerelease, not a stable release.

| Component | Version | Mod JAR | Checksum |
| --- | --- | --- | --- |
| Bert's Vehicle Pack | `0.1.1-kh55-test.3` | [JAR](https://github.com/bert11882157-games/BVP-SBW/releases/download/v0.1.1-kh55-test.3/berts_vehicle_pack-0.1.1-kh55-test.3.jar) | [SHA-256](https://github.com/bert11882157-games/BVP-SBW/releases/download/v0.1.1-kh55-test.3/berts_vehicle_pack-0.1.1-kh55-test.3.jar.sha256) |
| Superb Warfare fork | `0.8.9-bvp.4` | [JAR](https://github.com/bert11882157-games/BVP-SBW/releases/download/v0.1.1-kh55-test.3/superbwarfare-0.8.9-bvp.4-mc1.20.1-6effe43-all.jar) | [SHA-256](https://github.com/bert11882157-games/BVP-SBW/releases/download/v0.1.1-kh55-test.3/superbwarfare-0.8.9-bvp.4-mc1.20.1-6effe43-all.jar.sha256) |

Use with **Fire From Above `1.0.5-bvp.4`** on clients and the server. This build removes
duplicate missile HUD entries, caches unchanged fitted-pylon weapon data, and reduces KH-55
range to **1,500 blocks**. It retains the earlier bomb-bay, per-target and vehicle-jitter changes.
See the release notes for measured code-path costs and remaining in-game performance checks.

Keep meshloader 0.1.1, Dominions 1.0.11+, and the usual pack dependencies.
[FFA downloads](https://github.com/xenoperk/Fire-From-Above/blob/main/DOWNLOADS.md) ·
[Dominions downloads](https://github.com/xenoperk/Dominions/blob/main/DOWNLOADS.md).

## Earlier published testing build: Tu-95 KH-55 test 2

[BVP / SBW — Tu-95 KH-55 and jitter testing build 2](https://github.com/bert11882157-games/BVP-SBW/releases/tag/v0.1.1-kh55-test.2)
was published as a testing prerelease on 2026-09-22 (UTC). It has not
been promoted to stable.

| Component | Version | Mod JAR | Checksum |
| --- | --- | --- | --- |
| Bert's Vehicle Pack | `0.1.1-kh55-test.2` | [BVP JAR](https://github.com/bert11882157-games/BVP-SBW/releases/download/v0.1.1-kh55-test.2/berts_vehicle_pack-0.1.1-kh55-test.2.jar) | [SHA-256](https://github.com/bert11882157-games/BVP-SBW/releases/download/v0.1.1-kh55-test.2/berts_vehicle_pack-0.1.1-kh55-test.2.jar.sha256) |
| Superb Warfare fork | `0.8.9-bvp.3` | [SBW JAR](https://github.com/bert11882157-games/BVP-SBW/releases/download/v0.1.1-kh55-test.2/superbwarfare-0.8.9-bvp.3-mc1.20.1-6effe43-all.jar) | [SHA-256](https://github.com/bert11882157-games/BVP-SBW/releases/download/v0.1.1-kh55-test.2/superbwarfare-0.8.9-bvp.3-mc1.20.1-6effe43-all.jar.sha256) |

Use these two builds with **Fire From Above `1.0.5-bvp.3`** on clients and the server.
This update includes grouped munition selection, individual bomb-bay targets, the KH-55 model,
the ground-vehicle jitter fix, a maximum of two cruise launches per second per vehicle, and
128 Tu-95 flares before its existing automatic reload. See the release notes for validation
results and remaining in-game checks.

Keep **`sbwmeshloader` `0.1.1`** and the required **Dominions `1.0.11` or newer**, plus
the pack's other required dependencies. Meshloader is supplied separately by the pack maintainer.
See the [Fire From Above download list](https://github.com/xenoperk/Fire-From-Above/blob/main/DOWNLOADS.md)
and [Dominions download list](https://github.com/xenoperk/Dominions/blob/main/DOWNLOADS.md).

## Earlier Tu-95 KH-55 testing build — draft

[BVP / SBW — Tu-95 KH-55 testing build](https://github.com/bert11882157-games/BVP-SBW/releases/tag/untagged-de887257b447b534452b)
is an unpublished draft for `v0.1.1-kh55-test.1`. Draft releases require repository write access;
sign in with an eligible account and open the draft's **Assets** section. The links below are
the current draft asset links and are not public downloads.

| Component | Version | Mod JAR | Checksum |
| --- | --- | --- | --- |
| Bert's Vehicle Pack | `0.1.1-kh55-test.1` | [BVP JAR](https://github.com/bert11882157-games/BVP-SBW/releases/download/untagged-de887257b447b534452b/berts_vehicle_pack-0.1.1-kh55-test.1.jar) | [SHA-256](https://github.com/bert11882157-games/BVP-SBW/releases/download/untagged-de887257b447b534452b/berts_vehicle_pack-0.1.1-kh55-test.1.jar.sha256) |
| Superb Warfare fork | `0.8.9-bvp.2` | [SBW JAR](https://github.com/bert11882157-games/BVP-SBW/releases/download/untagged-de887257b447b534452b/superbwarfare-0.8.9-bvp.2-mc1.20.1-6effe43-all.jar) | [SHA-256](https://github.com/bert11882157-games/BVP-SBW/releases/download/untagged-de887257b447b534452b/superbwarfare-0.8.9-bvp.2-mc1.20.1-6effe43-all.jar.sha256) |

Use these two builds together with **Fire From Above `1.0.5-bvp.2`** on clients and the server.
See the [Fire From Above download list](https://github.com/xenoperk/Fire-From-Above/blob/main/DOWNLOADS.md)
for that matching testing build and its access requirements, and the
[Dominions download list](https://github.com/xenoperk/Dominions/blob/main/DOWNLOADS.md) for the required Dominions `1.0.11` or newer.

Keep the matching **`sbwmeshloader` `0.1.1`** and the pack's other required dependencies.
Meshloader is supplied separately by the pack maintainer; this repository does not currently
host a meshloader download. The draft release notes describe compatibility, changes and
validation limits; this testing build has not been promoted to a stable release.

## Release history

Keep earlier version entries and their version-specific links here when adding a new build.
When a draft is published, update its status and replace temporary draft links with the
published release and asset URLs. Do not relabel a testing build as stable without the release review.
