# ATGM roll

Every spinning ATGM in the pack declares its real roll in its projectile profile:

```json
"superbwarfare:missile_roll_v1": {"Schema": 1, "RollHz": 8.5, "RollAccelerationHzPerSecond": 17, "Direction": "CLOCKWISE"}
```

`tools/ballistics/atgm_roll.py` writes it (by RoundId; idempotent, `--check` to preview). SBW `MissileRoll` reads it and
drives, from one curve:

- the body roll of the BVP missile model (`BvpSpinningProjectileRenderer`);
- the four orbiting thruster plumes (FFA `MissileExhaust` through reflection, BVP fallback trail);
- the plumes' length and outward cant: 25% at launch, 150% at full roll (`MissileRoll.plumeScale`).

The roll drawn is capped at **6 Hz** (`MissileRoll.MAX_HZ`): faster reads as stutter of the orbiting plumes. The spin-up
uses the real acceleration, so a missile reaches the cap after `min(RollHz, 6) / acceleration` seconds. Direction is as
seen from behind (from the launcher).

| Missile (RoundId) | Real roll | Spin-up | Basis |
|---|---|---|---|
| 9M14 Malyutka | 8.5 Hz | booster phase (~0.5 s) | sourced: spun to 8.5 rev/s by canted booster nozzles, kept by 3°15′ canted wings |
| HJ-73E | 8.5 Hz | ~0.5 s | Malyutka derivative (estimate) |
| 9M113 Konkurs | 6 Hz | ~0.5 s | sourced 5–7 Hz (kept clear of the 2–3 Hz airframe mode) |
| 9M133 Kornet | 10 Hz | ~0.5 s | estimate from the KBP family (Fagot 10 rev/s, Metis 7–12 rev/s, both sourced) |
| 9M114 Shturm, 9M120 Ataka, 9K127 Vikhr | 10 Hz | ~0.5 s | estimate: spun in the tube by canted booster nozzles / motors |
| 9M117 Bastion, 9M119M1 Refleks, 9M112 Kobra, GP105, GP125 | 7.5 Hz | 0.5 s | estimate 5–10 Hz; Kobra sourced to reach its design rate within 0.5 s of the wings opening |
| MILAN, MILAN 3 | 12 Hz | ~0.5 s | sourced 12 tours/s ("rotation lente"); spin-up estimate |
| QN-502CDD, QN-201DD | 8 Hz | ~0.5 s | nothing published; single-channel family estimate |
| BGM-71A/E TOW | 0 | — | sourced: roll-stabilised, "does not spin in flight" (FM 23-34) |

Only Fagot and Metis have a published direction (clockwise seen in the direction of flight); all are drawn clockwise.
Every spinning missile except Konkurs has a real rate at or above 6 Hz, so in game they all spin at 6 Hz and differ in
how fast they get there (Konkurs 0.5 s, Kornet/Shturm 0.3 s, Bastion family 0.4 s, MILAN 0.25 s).

Sources:
- [9M14 Malyutka — Wikipedia](https://en.wikipedia.org/wiki/9M14_Malyutka); Soviet course text (studfile.net) for the
  booster-phase 8.5 rev/s
- [9M113 Konkurs — Wikipedia](https://en.wikipedia.org/wiki/9M113_Konkurs); Konkurs training text (studfile.net) for 5–7 Hz
- [Milan (missile) — Wikipédia (fr)](https://fr.wikipedia.org/wiki/Milan_(missile))
- [FM 23-34 TOW, ch. 1 — GlobalSecurity](https://www.globalsecurity.org/military/library/policy/army/fm/23-34/Ch1.htm)
- [9K112 Kobra — missilery.info](https://en.missilery.info/missile/cobra)
- [Fagot / Metis data — btvt.narod.ru](https://btvt.narod.ru/4/kornet.htm), [weaponland.ru](https://weaponland.ru/publ/poslednij_predstavitel_vtorogo_pokolenija_ptrk/21-1-0-2012)
