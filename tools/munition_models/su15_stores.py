#!/usr/bin/env python3
"""Store models and store definitions for the Su-15TM (no War Thunder datamine entry yet: real Su-15TM loads), after mig25_stores.py.

Built here, in the pack's aircraft-store format (nose -Z, one bone 'store', 1 block = 1 m). Generated geometry, so the display name
ends in " (AI)" (owner rule 2026-10-23):
  r98mr       R-98MR (K-8M, AA-3 Anab-A), semi-active radar: 4.27 m x 0.28 m, 292 kg, 40 kg blast-fragmentation warhead, wing span 1.3 m
              (Wikipedia K-8 / GlobalSecurity), long ogival radome. Layout read off the Su-15 model's own baked R-98: two small control
              fins (canards) at 24-30 % of the length and four large clipped-delta wings at 55-83 %, all in the X layout.
  r98mt       R-98MT (AA-3 Anab-B), infrared: 4.0 m x 0.28 m, 272 kg, blunt IR dome, same airframe.
  tank_600l_su15  PTB-600 fuselage drop tank of the Su-15 (War Thunder su_600l_su_15tm_left/right_fuse: 4.7 m, caliber 0.65 m, 120 kg empty):
              tapered nose and tail like the model's baked tank, two small horizontal tail fins; VISUAL_ONLY, 600 kg with the fuel.
  gun_pod/upk23_250  UPK-23-250 gun pod: GSh-23L, 250 rounds, about 3.2 m long (TWZ: 10 ft 5 in); diameter 0.40 m is an ESTIMATE (no published
              figure found); two barrel mouths in the nose fairing. Its native weapon is written by su15_armament.py.
Stats are the pack's scale (AIM-7E / R-27R / R-13M1 as anchors): MaxSpeed, TurnDegreesPerSecond, LockTicks, cone, CM vulnerability, the
TNT equivalent are a GUESS.

Usage: python tools/munition_models/su15_stores.py [--write]
Then:  python tools/munition_models/fix_winding.py <the new geo files>   (and --check)
"""
import json
import math
import os
import sys
from collections import OrderedDict

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import f16c_stores as F  # noqa: E402
import generate  # noqa: E402
from f16c_stores import Mesh, revolve, disc, lugs, centred  # noqa: E402

NS, GEO, TEX = F.NS, F.GEO, F.TEX
OD = OrderedDict
DOT = '\u00b7'
NEW_GEO = []

TANK_L, TANK_D = 4.7, 0.65
POD_L, POD_D = 3.2, 0.40


def build_missile(name, spec):
    mesh, Lpx, rpx = generate.build(spec)
    F.write_json_compact(os.path.join(GEO, name + '.geo.json'), generate.geo(mesh, f'geometry.aircraft_store.{name}', False, Lpx))
    F.write_json_compact(os.path.join(GEO, name + '_flight.geo.json'), generate.geo(mesh, f'geometry.aircraft_store.{name}_flight', True, Lpx))
    generate.texture().save(os.path.join(TEX, name + '.png'))
    F.mark_generated(name, name + '_flight')
    NEW_GEO.extend([name, name + '_flight'])


def build_tank():
    """4.7 m x 0.65 m: blunt rounded nose over 28 % of the length, parallel body, tail taper over 20 %, two small horizontal fins, two lugs."""
    L, d = TANK_L, TANK_D
    r = d / 2
    m = Mesh()
    nose = F.ogive(1.30, 0.10 * r, r, 8)
    prof = nose + [(L * 0.80, r), (L, 0.28 * r)]

    def col(s):
        return 'alu' if s < L - 0.30 else 'grey'
    revolve(m, prof, col, cuts=[L - 0.30], caps=('alu', 'dgrey'))
    F.cruciform(m, (L - 0.95, L - 0.15), (L - 0.60, L - 0.15), 0.85 * r, 0.50, 0.012, 'grey', roll=0, count=2)
    lugs(m, L * 0.50, 0.50, r)
    return centred(m, L), L


def build_pod():
    """UPK-23-250: 3.2 m x 0.40 m; rounded nose fairing with two barrel mouths, cylinder, tail cone; lugs at the middle."""
    L, d = POD_L, POD_D
    r = d / 2
    m = Mesh()
    prof = [(0.0, 0.42 * r), (0.10, 0.80 * r), (0.35, 0.97 * r), (0.60, r), (L - 0.70, r), (L - 0.12, 0.62 * r), (L, 0.40 * r)]

    def col(s):
        return 'dgrey' if s < 0.32 else ('alu' if s < L - 0.12 else 'dgrey')
    revolve(m, prof, col, cuts=[0.32, L - 0.12], caps=('dgrey', 'dgrey'))
    for sx in (-1, 1):                                   # barrel mouths in the nose
        disc(m, sx * 0.05, 0.0, -0.002, 0.026, 'black', -1, n=8)
        revolve(m, [(0.0, 0.026), (0.12, 0.026)], lambda s: 'black', ax=sx * 0.05, ay=0.0, cap_nose=False, cap_tail=False)
    lugs(m, L / 2, 0.25, r)
    m.box(-0.05, 0.05, r - 0.012, r + 0.002, L / 2 - 0.30, L / 2 + 0.30, 'alu')
    return centred(m, L), L


def main(argv):
    if '--write' not in argv:
        print('dry run: pass --write to generate files')
        return
    os.makedirs(GEO, exist_ok=True)
    os.makedirs(TEX, exist_ok=True)
    cat = F.Catalogue()

    # ============================================================== R-98MR
    L = 4.27
    wing = lambda L_: dict(root=(0.55 * L_, 0.83 * L_), tip=(0.74 * L_, 0.83 * L_), span=1.3 / 2, roll=45)
    canard = lambda L_: dict(root=(0.24 * L_, 0.30 * L_), tip=(0.27 * L_, 0.30 * L_), span=0.45 / 2, roll=45)
    build_missile('r98mr', dict(length=L, diameter=0.28, nose=1.10, boattail=0.35, tail_r=0.85, bands=[(0.12, 0.14, 'yellow')],
                                radome=0.95, wings=[wing(L)], fins=[canard(L)]))
    a, launch, g = F.anchors_for('r98mr', missile=True)
    guid = OD([('Mode', 'SEMI_ACTIVE_RADAR'), ('LockTicks', 0), ('Range', 1500), ('ConeDegrees', 25), ('CountermeasureVulnerability', 0.85)])
    flight = OD([('InitialSpeed', 0.5), ('MaxSpeed', 7), ('AccelerationPerTick', 0.1), ('TurnDegreesPerSecond', 28), ('Damage', 55),
                 ('BlastRadius', 5), ('TntEquivalentKg', 20.0)])
    cat.add('munition/r98mr', F.store(f'R-98MR {DOT} semi-active radar (AI)', 'AIR_TO_AIR', 'r98mr', a, Capacity=1, MassKg=292.0,
                                      Guidance=guid, Flight=flight, AmmoItem='superbwarfare:medium_air_to_air_missile', MaxPerPylon=1,
                                      LaunchOffset=launch))
    print('r98mr anchors', a['MountAnchor'], 'launch', launch)

    # ============================================================== R-98MT
    L = 4.0
    build_missile('r98mt', dict(length=L, diameter=0.28, nose=0.85, boattail=0.35, tail_r=0.85, bands=[(0.12, 0.14, 'yellow')],
                                radome=0.45, wings=[wing(L)], fins=[canard(L)]))
    a, launch, g = F.anchors_for('r98mt', missile=True)
    guid = OD([('Mode', 'INFRARED'), ('LockTicks', 70), ('Range', 1200), ('ConeDegrees', 15), ('CountermeasureVulnerability', 1.2)])
    cat.add('munition/r98mt', F.store(f'R-98MT {DOT} infrared (AI)', 'AIR_TO_AIR', 'r98mt', a, Capacity=1, MassKg=272.0,
                                      Guidance=guid, Flight=flight, AmmoItem='superbwarfare:medium_air_to_air_missile', MaxPerPylon=1,
                                      LaunchOffset=launch))
    print('r98mt anchors', a['MountAnchor'], 'launch', launch)

    # ============================================================== PTB-600
    m, L = build_tank()
    F.save_model('tank_600l_su15', m)
    NEW_GEO.append('tank_600l_su15')
    a, launch, g = F.anchors_for('tank_600l_su15')
    cat.add('munition/tank_600l_su15', F.store(f'PTB-600 600 l fuselage drop tank (AI)', 'VISUAL_ONLY', 'tank_600l_su15', a, Capacity=1,
                                               MassKg=600.0, LaunchOffset=launch))
    print('tank anchors', a['MountAnchor'])

    # ============================================================== UPK-23-250
    m, L = build_pod()
    F.save_model('upk23_250', m)
    NEW_GEO.append('upk23_250')
    a, launch, g = F.anchors_for('upk23_250')
    d = F.store(f'UPK-23-250 {DOT} 23 mm GSh-23L gun pod', 'GUN_POD', 'upk23_250', a, MassKg=218.0)
    for k in ('SideMountAnchors', 'MountAxis'):
        d.pop(k, None)           # a gun pod hangs from its top lugs only, as the SUU-23/A
    F.write_json(os.path.join(F.STORES, 'gun_pod', 'upk23_250.json'), F.mark_store(d))
    print('pod anchors', a['MountAnchor'])
    print('written; new geo files:', ' '.join(NEW_GEO))


if __name__ == '__main__':
    main(sys.argv[1:])
