# Reloads, fire rates and reload sounds (ground vehicles)

`tools/ballistics/wt_reloads.py` sets the reload time, fire rate and reload clip of every ground-vehicle and emplaced
weapon in the pack. Aircraft and helicopters are not touched. A second run reports `0 changes`.

## Main guns: War Thunder reloads

The value comes from the War Thunder datamine (gszabi99/War-Thunder-Datamine, `gamedata/units/tankmodels/<unit>.blkx`):
- A `shotFreq` on the unit's gun entry takes precedence over the one in the weapon `.blk`. For example, the Challenger 2
  and T-55A override theirs.
- For a human loader, the value is the **aced** crew reload. A basic crew is 1.3x slower. This was checked against the
  WT wiki: M1A1 "6.5 → 5 s".
- An autoloader has a fixed reload.

| Vehicle | s | Source |
|---|---|---|
| T-72A, T-72B, T-72B3, T-90A, T-90M | 7.0 | WT autoloader |
| T-80B, T-80U, T-64B | 6.0 | WT autoloader |
| T-14 | 5.5 | Estimate: not in WT; faster than the T-80 |
| T-55A / T-62 | 7.5 / 8.0 | WT, aced |
| M1, M1A1, M1A2 SEP v2, Challenger 2 | 5.0 | WT, aced |
| Leopard 2A4 / 2A6 | 6.0 | WT, aced |
| M48 (90 mm) / M60A1 / M551 | 6.0 / 6.67 / 12.0 | WT, aced |
| Leclerc S1 / Type 90 | 5.0 / 4.0 | WT autoloader |
| VT-4, ZTZ99A / ZTL-09 | 6.67 | WT (ZTL-09 uses the ZTL-11's 105 mm) |
| M1128 / PzH 2000 | 7.5 / 5.0 | WT autoloader |
| M109A7 | 10.0 | Estimate: WT's M109A1 is 13.3 s; the A7 has a powered rammer |
| K2 | 4.0 | Estimate: not in WT; rated 15 rounds/min |
| BMP-3 100 mm / BMP-1, BMD-1 73 mm | 4.0 / 6.0 | WT autoloader |

A magazine-fed main gun gets `RPM 60`, so that its reload sets the interval between shots and the fire-rate gate does
not. Before this, the Type 90's RPM of 10 held its 4 s autoloader to 6 s.

## Everything else: rules of thumb

| Weapon | Reload |
|---|---|
| Coaxial / pintle LMG (≤ 8 mm) | 20 s |
| HMG (12.7 / 14.5 mm) | 10 s |
| Autocannon and automatic grenade launcher | 20 s |
| Externally mounted ATGM / SAM launcher | 12 s |

Machine-gun fire rates follow WT for the same gun:

| Gun | Rounds/min |
|---|---|
| PKT | 700 |
| M240 | 940 |
| MG3 | 1200 |
| M73 | 500 |
| Type 74 / Type 86 | 700 |
| QJT | 800 |
| M2HB | 575 |
| NSVT / Kord | 700 |
| DShK | 600 |
| M85 | 625 |

The generic "7.62 mm coax" is matched to the vehicle's nation. Twin mounts count both guns. The ZU-23-2 is 2 × 800 =
1600.

Left as they were:
- the 9P149's internal autoloading launcher, whose interval between missiles is now WT's 8.33 s;
- the SPG-9 recoilless guns;
- the S-5 rocket pod.

## Inconsistencies fixed

- **Autocannons reloading almost instantly:**
  - Bradley / LAV-25 M242: 4 ticks.
  - CV9040: 4 ticks.
  - BMP-2, BTR-80A, QN-506: 1 s.
- **No magazine:** the Marder 1A1/1A2 Rh202 fed straight from the hold and never reloaded. It now has a 200-round box,
  like the 1A5.
- **Fire rates:**
  - The Type 90 coax was at 1200 rounds/min; the Type 74 fires 700.
  - The Abrams / Type 90 M2HB was at 450.
  - The T-64 NSVT was at 450.
  - The ZU-23-2 was at 2000.
- **Tank reloads:** every tank sat at 5.75 s (115 ticks) whatever its gun or loader. The T-80B's 6.0 s was one of the
  few that differed.
- **ATGMs:** the BMPT's Ataka took 45 s. Most other external launchers took 5–10 s.

## Reload sounds

Each weapon now has one crew reload clip, heard by the seats that operate it. The clip is timed with
`VehicleReloadClipDurationTicks` (measured from the `.ogg`, rounded up), so it ends as the reload completes.

A clip longer than the reload is cut off at completion (`VehicleReloadAudio.finish`). Every gun therefore gets a clip
no longer than its reload:

| Clip | Length | Used by |
|---|---|---|
| `soviet_125mm_autoloader` | 6.66 s | T-72 / T-90 (7.0 s), M1128 |
| `t_90a_reload` | 5.41 s | T-80, T-64, T-14; BMP-1 / BMD-1 carousel |
| `ztz_99a_reload` | 5.45 s | VT-4, ZTZ99A |
| `m_1a_2_reload` | 5.2 s | Leopards |
| `m68a1_105mm_reload` | 4.31 s | Other hand-loaded guns, Leclerc, PzH 2000 |
| `cannon_reload` | ≤ 1.82 s breech close | 4 s autoloaders (Type 90, K2, BMP-3), SPG-9s, the technical's 73 mm |
| `plz_05_reload` | 6.02 s | M109A7 |
| `m_60_reload_empty` | 6.3 s | LMGs |
| `m_2_hb_reload_empty` | 6.34 s | HMGs and autocannons |
| `medium_missile_reload` | 1.58 s | Missiles |

Before this:
- the legacy `VehicleReloadSoundTime 28` started the 1.58 s missile clip 1.4 s before completion, cutting its end;
- machine guns and autocannons had no reload sound at all.

The durations were measured on the PC with `claude_jobs/job-ogg-durations.mjs`: last Ogg granule ÷ Vorbis sample rate.
