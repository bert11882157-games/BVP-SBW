#!/usr/bin/env python3
"""Store models and store definitions for the Yak-141 (War Thunder yak_141 weapons), after su15_stores.py / yak38_stores.py.

War Thunder's Yak-141 (datamine gamedata/flightmodels/yak_141.blkx, checked 2026-10-23) offers on its four wing pylons: R-60M, R-73, R-27R, R-27T (and
R-27ER / R-27ET on the inner pair), UB-32A-24, B-8M1 (S-8KO), B-13L (S-13OF), S-24B, 4 x OFAB-100-120 on the MBD2-67U, FAB-250M-62, FAB-500M-62T,
OFAB-250Sh, FAB-500Sh, ZB-500 and UPK-23-250. The pack already has R-60, R-73, R-27R, R-27T, the S-8KO pod, FAB-250, FAB-500 and the UPK-23-250 pod;
UB-32A-24, S-24B and ZB-500 come from yak38_stores.py. Built here, in the pack's aircraft-store format (nose -Z, one bone 'store', 1 block = 1 m);
generated geometry carries " (AI)" in the name (owner rule 2026-10-23). Numbers are the pack's scale with War Thunder's masses and explosives.

  munition/r27er   R-27ER (AA-10 Alamo-C) semi-active radar: WT su_r_27er 350 kg, 4.7 m x 0.26 m, 39 kg warhead (15 kg tgaf_5), maxDistance 67.5 km against
                   the R-27R's 55 km. Cloned from the pack's R-27R store (Flight, warhead, guidance) with Range x 1.227 (67.5 / 55). Layout GUESS (the pack's R-27).
  munition/r27et   R-27ET infrared: WT su_r_27et 343 kg, 4.5 m x 0.26 m, same motor and range; cloned from the pack's R-27T.
  munition/b13l    B-13L 5 x S-13OF: WT b13l_s13of container 160 kg + 5 x 69 kg rockets (S-13OF 122 mm, 2.826 m, 6.9 kg A-IX-2) = 505 kg; length 3.3 m and
                   diameter 0.38 m are ESTIMATES (no figure in the datamine); five tubes in a ring. Fired by a native rocket weapon.
  munition/ofab100_x4  MBD2-67U four-bomb rack with OFAB-100-120 (WT su_ofab_100_120: 114 kg, 0.273 m x 1.033 m, 38 kg TNT): the pack's FAB-100 body and
                   profile on the pack's four-station ejector-rack adapter (rack geometry is generated, so the store is marked AI).
  munition/ofab250sh   OFAB-250Sh (WT su_ofab250sh: 275 kg, 0.325 m x 1.56 m, dragCx 0.07, 99 kg tg_20): the pack's FAB-250 body and profile, WT numbers
                   (TNT 99 x 1.33 = 131.7 kg: WT explosive.blkx strengthEquivalent of tg_20); drag x 2.7 (0.07 / 0.0257 of the FAB-250M-62).
  munition/fab500sh    FAB-500Sh (WT su_fab500sh: 515 kg, 0.45 m x 2.388 m, 226.6 kg tgaf_5 x 1.6 = 362.6 kg TNT): the pack's FAB-500 body and profile.
Models cut from the supplied model are not marked; numbers-only stores on a human-made body are not marked either.

Usage: python tools/munition_models/yak141_stores.py [--write]
Then:  python tools/munition_models/fix_winding.py <the new geo files>   (and --check)
"""
import copy
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

NS, GEO, TEX, STORES = F.NS, F.GEO, F.TEX, F.STORES
OD = OrderedDict
DOT = '·'
NEW_GEO = []


def load(rel):
    return json.load(open(os.path.join(STORES, rel + '.json'), encoding='utf-8'), object_pairs_hook=OD)


def build_missile(name, spec):
    mesh, Lpx, rpx = generate.build(spec)
    F.write_json_compact(os.path.join(GEO, name + '.geo.json'), generate.geo(mesh, f'geometry.aircraft_store.{name}', False, Lpx))
    F.write_json_compact(os.path.join(GEO, name + '_flight.geo.json'), generate.geo(mesh, f'geometry.aircraft_store.{name}_flight', True, Lpx))
    generate.texture().save(os.path.join(TEX, name + '.png'))
    F.mark_generated(name, name + '_flight')
    NEW_GEO.extend([name, name + '_flight'])


def r27_spec(L):
    wing = dict(root=(0.40 * L, 0.62 * L), tip=(0.50 * L, 0.62 * L), span=0.348, roll=0)
    fin = dict(root=(0.88 * L, 1.0 * L), tip=(0.94 * L, 1.0 * L), span=0.398, roll=0)
    return wing, fin


def clone_r27(src, name, model, label, mass, L, radome, range_factor):
    wing, fin = r27_spec(L)
    build_missile(model, dict(length=L, diameter=0.26, nose=1.0, boattail=0.15, tail_r=0.85, bands=[(0.20, 0.22, 'yellow')],
                              radome=radome, wings=[wing], fins=[fin]))
    a, launch, g = F.anchors_for(model, missile=True)
    d = load(src)
    d['Name'] = label
    d['Model'] = f'{NS}:custom_geo/aircraft_stores/{model}.geo.json'
    d['Texture'] = f'{NS}:textures/aircraft_stores/{model}.png'
    d['ModelForward'] = '-Z'
    for k, v in a.items():
        d[k] = v
    d['LaunchOffset'] = launch
    d['MassKg'] = mass
    d['Guidance']['Range'] = int(round(d['Guidance']['Range'] * range_factor))
    return d, a


def build_b13l():
    """B-13L: 3.3 m x 0.38 m, frangible nose cone, five tubes in a ring (mouths drawn aft), tail fairing, two lugs."""
    L, d = 3.3, 0.38
    r = d / 2
    m = Mesh()
    nose, tail = 0.22, 0.24
    prof = [(0.0, 0.50 * r), (nose, r), (L - tail, r), (L, 0.80 * r)]

    def col(s):
        return 'dgrey' if (s < nose or s > L - tail) else 'olive'
    revolve(m, prof, col, cuts=[nose, L - tail], caps=('grey', 'grey'))
    for k in range(5):
        a = math.radians(90 + 72 * k)
        disc(m, 0.62 * r * math.cos(a), 0.62 * r * math.sin(a), L + 0.001, 0.0615, 'black', +1)
    lugs(m, L * 0.5, 0.356, r)
    return centred(m, L), L


def main(argv):
    if '--write' not in argv:
        print('dry run: pass --write to generate files')
        return
    os.makedirs(GEO, exist_ok=True)
    os.makedirs(TEX, exist_ok=True)
    cat = F.Catalogue()

    # ============================================================== R-27ER / R-27ET
    d, a = clone_r27('r27r', 'r27er', 'r27er', f'R-27ER {DOT} extended-range semi-active radar (AI)', 350.0, 4.7, 0.95, 67.5 / 55.0)
    cat.add('munition/r27er', d)
    print('r27er anchors', a['MountAnchor'], 'range', d['Guidance']['Range'])
    d, a = clone_r27('r27t', 'r27et', 'r27et', f'R-27ET {DOT} extended-range infrared (AI)', 343.0, 4.5, 0.40, 67.5 / 55.0)
    cat.add('munition/r27et', d)
    print('r27et anchors', a['MountAnchor'], 'range', d['Guidance']['Range'])

    # ============================================================== B-13L
    m, L = build_b13l()
    F.save_model('b13l', m)
    NEW_GEO.append('b13l')
    a, launch, g = F.anchors_for('b13l')
    cat.add('munition/b13l', F.store(f'B-13L {DOT} 5 x S-13OF rockets', 'ROCKET_POD', 'b13l', a, Capacity=5, Scale=1, MassKg=101.0,
                                     AmmoItem='superbwarfare:small_unguided_rocket', LaunchOffset=launch))
    print('b13l anchors', a['MountAnchor'])

    # ============================================================== OFAB-100-120 x4 on the MBD2-67U (the pack's ejector rack adapter)
    d = load('fab_100_x3')
    rack = load('munition/mk82_x4')
    d['Name'] = f'MBD2-67U {DOT} 4 x OFAB-100-120 rack'
    d['MassKg'] = 114
    d['Bomb']['BlastRadius'] = 15.0
    d['Bomb']['TntEquivalentKg'] = 38.0
    d['MaxPerPylon'] = 4
    d['FixedRackCount'] = 4
    d['RackAdapter'] = copy.deepcopy(rack['RackAdapter'])
    d['RackSpacing'] = copy.deepcopy(rack['RackSpacing'])
    d['RackColumns'] = rack['RackColumns']
    d['RackMassKg'] = 60.0
    cat.add('munition/ofab100_x4', d)

    # ============================================================== OFAB-250Sh, FAB-500Sh (numbers on the pack's bodies)
    d = load('fab_250')
    d['Name'] = f'OFAB-250Sh {DOT} 275 kg HE high-drag'
    d['MassKg'] = 275
    d['Bomb']['DragMultiplier'] = 2.7
    d['Bomb']['BlastRadius'] = 19
    d['Bomb']['TntEquivalentKg'] = 131.7
    d['Bomb']['BlastDamage'] = 450
    cat.add('munition/ofab250sh', d)
    d = load('fab_500')
    d['Name'] = f'FAB-500Sh {DOT} 515 kg HE'
    d['MassKg'] = 515
    d['Bomb']['BlastRadius'] = 29
    d['Bomb']['TntEquivalentKg'] = 362.6
    d['Bomb']['BlastDamage'] = 850
    cat.add('munition/fab500sh', d)
    print('written; new geo files:', ' '.join(NEW_GEO))


if __name__ == '__main__':
    main(sys.argv[1:])
