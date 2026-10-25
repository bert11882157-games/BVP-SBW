#!/usr/bin/env python3
"""Store models and store definitions for the MiG-25PD (War Thunder mig_25pd loads), after f4_stores.py / f14c_stores.py.

Built here, in the pack's aircraft-store format (nose -Z, one bone 'store', 1 block = 1 m). Generated geometry, so the display
name ends in " (AI)" (owner rule 2026-10-23):
  r40rd       R-40RD (AA-6 Acrid-A), semi-active radar: 6.23 m x 0.31 m, 465 kg (War Thunder su_r_40rd: length 6.231, caliber 0.3, mass 465,
              explosive 25 kg TGAF-5 = 40 kg TNT), four large rear delta wings (span 1.45 m, Wikipedia R-40) and four small forward control
              fins, X layout as the MiG-25 model's own baked R-40; the layout and fin sizes are read off that baked model, not a drawing.
  r40td       R-40TD (AA-6 Acrid-B), infrared: 5.98 m (WT su_r_40td), same airframe, pointed IR dome (WT guidance: optical, 1 s warm-up,
              20 s work time).
  tank_5280l  PTB-5280 centreline drop tank (WT su_5280l_mig_25pd_drop_tank_central: 4.744 m, caliber 0.582 m, 68 kg empty, WT fuel 4,300 kg).
              VISUAL_ONLY like the pack's other tanks; mass 4,368 kg with the fuel.
Stats are the pack's scale (AIM-54 / AIM-7M / R-27 as anchors): MaxSpeed, TurnDegreesPerSecond, LockTicks, cone, CM vulnerability GUESS.
The R-60 is the pack's `r60_x2` twin rail (WT r_60m_x2 on the APU-60-2 adapter).

Usage: python tools/munition_models/mig25_stores.py [--write]
Then:  python tools/munition_models/fix_winding.py <the new geo files>   (and --check)
"""
import json
import os
import sys
from collections import OrderedDict

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import f16c_stores as F  # noqa: E402
import generate  # noqa: E402

NS, GEO, TEX = F.NS, F.GEO, F.TEX
OD = OrderedDict
DOT = '·'
NEW_GEO = []


def build_missile(name, spec):
    mesh, Lpx, rpx = generate.build(spec)
    F.write_json_compact(os.path.join(GEO, name + '.geo.json'), generate.geo(mesh, f'geometry.aircraft_store.{name}', False, Lpx))
    F.write_json_compact(os.path.join(GEO, name + '_flight.geo.json'), generate.geo(mesh, f'geometry.aircraft_store.{name}_flight', True, Lpx))
    generate.texture().save(os.path.join(TEX, name + '.png'))
    NEW_GEO.extend([name, name + '_flight'])


def build_tank():
    """PTB-5280: 4.744 m x 0.582 m, ogive nose, parallel body, tapered tail with four stabilising fins, two suspension lugs."""
    L, d = 4.744, 0.582
    r = d / 2
    m = F.Mesh()
    nose = F.ogive(1.25, 0.03, r, 8)
    prof = nose + [(3.35, r), (4.10, 0.80 * r), (L, 0.30 * r)]

    def col(s):
        return 'alu' if s < L - 0.55 else 'grey'
    F.revolve(m, prof, col, cuts=[L - 0.55], caps=('alu', 'dgrey'))
    F.cruciform(m, (L - 1.00, L - 0.20), (L - 0.62, L - 0.20), 0.80 * r, 0.50, 0.012, 'grey', roll=45)
    F.lugs(m, L * 0.45, 0.72, r)
    return F.centred(m, L), L


def main(argv):
    if '--write' not in argv:
        print('dry run: pass --write to generate files')
        return
    os.makedirs(GEO, exist_ok=True)
    os.makedirs(TEX, exist_ok=True)
    cat = F.Catalogue()

    # ============================================================== R-40RD
    # X layout read off the model's baked R-40 (cut: work/elite_expansion_2/mig25/stores/r40_mig25): control fins at 17 % of the length,
    # delta wings from 42 % to 90 %. Wing span 1.45 m tip to tip (Wikipedia R-40: 1.45 m), forward fins 0.62 m GUESS.
    L = 6.231
    build_missile('r40rd', dict(length=L, diameter=0.31, nose=1.15, boattail=0.10, tail_r=0.85, bands=[(0.30, 0.31, 'yellow'), (0.62, 0.63, 'yellow')],
                                radome=0.95,
                                wings=[dict(root=(2.62, 5.62), tip=(4.55, 5.62), span=1.45 / 2, roll=45),
                                       dict(root=(1.05, 1.62), tip=(1.28, 1.62), span=0.62 / 2, roll=45)], fins=[]))
    a, launch, g = F.anchors_for('r40rd', missile=True)
    guid = OD([('Mode', 'SEMI_ACTIVE_RADAR'), ('LockTicks', 0), ('Range', 3000), ('ConeDegrees', 25), ('CountermeasureVulnerability', 0.8)])
    flight = OD([('InitialSpeed', 0.5), ('MaxSpeed', 8.5), ('AccelerationPerTick', 0.1), ('TurnDegreesPerSecond', 30), ('Damage', 80),
                 ('BlastRadius', 6), ('TntEquivalentKg', 40.0)])
    cat.add('munition/r40rd', F.store(f'R-40RD {DOT} semi-active radar (AI)', 'AIR_TO_AIR', 'r40rd', a, Capacity=1, MassKg=465.0,
                                      Guidance=guid, Flight=flight, AmmoItem='superbwarfare:large_air_to_air_missile', MaxPerPylon=1,
                                      LaunchOffset=launch))

    # ============================================================== R-40TD
    L = 5.98
    build_missile('r40td', dict(length=L, diameter=0.31, nose=1.00, boattail=0.10, tail_r=0.85, bands=[(0.30, 0.31, 'yellow'), (0.62, 0.63, 'yellow')],
                                radome=0.40,
                                wings=[dict(root=(2.45, 5.38), tip=(4.35, 5.38), span=1.45 / 2, roll=45),
                                       dict(root=(1.00, 1.55), tip=(1.22, 1.55), span=0.62 / 2, roll=45)], fins=[]))
    a, launch, g = F.anchors_for('r40td', missile=True)
    guid = OD([('Mode', 'INFRARED'), ('LockTicks', 70), ('Range', 1800), ('ConeDegrees', 20), ('CountermeasureVulnerability', 1.2)])
    cat.add('munition/r40td', F.store(f'R-40TD {DOT} infrared (AI)', 'AIR_TO_AIR', 'r40td', a, Capacity=1, MassKg=465.0,
                                      Guidance=guid, Flight=flight, AmmoItem='superbwarfare:large_air_to_air_missile', MaxPerPylon=1,
                                      LaunchOffset=launch))

    # ============================================================== PTB-5280
    m, L = build_tank()
    F.save_model('tank_5280l', m)
    NEW_GEO.append('tank_5280l')
    a, launch, g = F.anchors_for('tank_5280l')
    cat.add('munition/tank_5280l', F.store(f'PTB-5280 5,280 l centreline drop tank (AI)', 'VISUAL_ONLY', 'tank_5280l', a, Capacity=1,
                                           MassKg=4368.0, LaunchOffset=launch))
    print('written', len(cat.items), 'stores; new geo files:', ' '.join(NEW_GEO))


if __name__ == '__main__':
    main(sys.argv[1:])
