#!/usr/bin/env python3
"""Store models and store definitions for the Avro Vulcan B.2 (real conventional load: War Thunder has no Vulcan), after vautour_stores.py.

Built here, in the pack's aircraft-store format (nose -Z, one bone 'store', 1 block = 1 m). Generated geometry, so each display name ends in
" (AI)" (owner rule 2026-10-23). The model carries no baked store (work/elite_expansion_2/vulcan/STORES.md), so nothing is cut from it.
  mc1000   British 1,000 lb MC bomb (Mk 11 / Mk 13 family), the 21 x 1,000 lb bay load (three carriers of seven; Black Buck 1 carried 21).
           454 kg, a slim tailed bomb; dimensions GUESS (about 1.6 m x 0.36 m). Filler: MC bombs are about 45% charge (RDX / TNT), so about 205 kg
           x 1.1 = 225 kg TNT equivalent: blast numbers interpolated between the pack's Mk 82 (117.6 kg: 7 m, 450) and Mk 83 (272.4 kg: 10 m, 650).
  alq101   Westinghouse AN/ALQ-101(V)-10 ("Dash 10") ECM pod, carried on the Black Buck wing pylons. Visual store (the pack has no jamming
           effect): about 2.8 m x 0.30 m, 290 kg, a ram-air turbine on the nose; the figures are GUESSES.
  The Shrike (AGM-45A) is the pack's own store: munition/agm45a.
Nuclear weapons (Blue Danube, Yellow Sun, Red Beard, WE.177, Blue Steel) are left out (owner brief 2026-10-23).

Usage: python tools/munition_models/vulcan_stores.py [--write]
Then:  python tools/munition_models/fix_winding.py <the new geo files>   (and --check)
"""
import os
import sys
from collections import OrderedDict

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import f16c_stores as F  # noqa: E402
from f16c_stores import Mesh, revolve, cruciform, lugs, centred, ogive  # noqa: E402

NS, STORES, SBW, GEO, TEX = F.NS, F.STORES, F.SBW, F.GEO, F.TEX
OD = OrderedDict
DOT = '·'
NEW_GEO = []
NEW_GEO_FILES = ['mc1000', 'alq101']


def save(name, mc):
    m, L = mc
    F.save_model(name, m)
    NEW_GEO.append(name)
    return L


def build_bomb(L=1.60, d=0.36, nose=0.42, tail_at=1.05, tail_r=0.13, fin_z=(1.28, 1.58), reach=0.22, band=(0.62, 0.70), lug_z=0.80):
    """Tailed general-purpose bomb: olive body with a yellow band, tapered tail cone with four fins."""
    r = d / 2
    m = Mesh()
    prof = [(0.0, 0.04)] + ogive(nose, 0.04, r)[1:] + [(tail_at, r), (L, tail_r)]

    def col(s):
        if band[0] <= s < band[1]:
            return 'yellow'
        return 'olive' if s < tail_at else 'dgrey'
    revolve(m, prof, col, cuts=[band[0], band[1], tail_at], caps=('dgrey', 'dgrey'))
    cruciform(m, (fin_z[0], fin_z[1]), (fin_z[0] + 0.10, fin_z[1]), tail_r * 0.9, reach, 0.012, 'dgrey', 45)
    lugs(m, lug_z, 0.356, r)
    return centred(m, L), L


def build_pod(L=2.80, d=0.30):
    """ECM pod: grey cylinder with an ogive nose carrying a small ram-air turbine disc, a tapered tail, two lugs."""
    r = d / 2
    m = Mesh()
    nose, tail = 0.45, 0.50
    prof = [(0.0, 0.05)] + ogive(nose, 0.05, r)[1:] + [(L - tail, r), (L, 0.45 * r)]

    def col(s):
        if s < 0.12:
            return 'black'
        return 'grey' if s < L - tail else 'dgrey'
    revolve(m, prof, col, cuts=[0.12, L - tail], caps=('black', 'dgrey'))
    # two antenna blisters on the sides, a dorsal fin-like ram-air scoop on the nose
    m.box(-r - 0.025, -r + 0.01, -0.07, 0.07, L * 0.55, L * 0.55 + 0.40, 'white')
    m.box(r - 0.01, r + 0.025, -0.07, 0.07, L * 0.55, L * 0.55 + 0.40, 'white')
    lugs(m, L * 0.5, 0.356, r)
    return centred(m, L), L


def load(rel):
    import json
    with open(os.path.join(STORES, rel + '.json'), encoding='utf-8') as fh:
        return json.load(fh, object_pairs_hook=OD)


def main(argv):
    if '--write' not in argv:
        print('dry run: pass --write to generate files')
        return
    os.makedirs(GEO, exist_ok=True)
    os.makedirs(TEX, exist_ok=True)
    cat = F.Catalogue()
    prof_dir = os.path.join(SBW, 'projectile_profiles')

    save('mc1000', build_bomb())
    save('alq101', build_pod())

    # ---- bomb projectile profile (the pack's authored bomb visual, as mirage3e_stores.bomb_profile)
    F.write_json(os.path.join(prof_dir, 'aircraft_bombs', 'mc1000.json'), OD([
        ('VisualProfile', f'{NS}:authored_bomb'), ('MotionSync', 'INHERIT'), ('TrailMode', 'SUPPRESS'), ('RenderScale', 1),
        ('Extensions', OD([(f'{NS}:projectile_mesh_v1', OD([
            ('Model', f'{NS}:custom_geo/aircraft_stores/mc1000.geo.json'),
            ('Texture', f'{NS}:textures/aircraft_stores/mc1000.png'), ('ForwardYaw', 0), ('SpinDegreesPerTick', 0)]))]))]))
    a, launch, g = F.anchors_for('mc1000')
    b = OD([('Mode', 'DUMB'), ('Gravity', 0.08), ('DragMultiplier', 1), ('TurnDegreesPerTick', 0),
            ('BlastRadius', 9), ('TntEquivalentKg', 225.0), ('BlastDamage', 600)])
    cat.add('munition/mc1000', F.store(f'1,000 lb MC bomb {DOT} freefall', 'BOMB', 'mc1000', a, Capacity=1, MassKg=454.0, Bomb=b,
                                       AmmoItem='superbwarfare:medium_freefall_bomb', ProjectileProfile=f'{NS}:aircraft_bombs/mc1000',
                                       LaunchOffset=launch))
    a, launch, g = F.anchors_for('alq101')
    cat.add('munition/alq101', F.store(f'AN/ALQ-101(V)-10 ECM pod', 'VISUAL_ONLY', 'alq101', a, Capacity=1, MassKg=290.0, LaunchOffset=launch))
    print('mc1000 anchors', a['MountAnchor'], '; written; new geo files:', ' '.join(NEW_GEO))


if __name__ == '__main__':
    main(sys.argv[1:])
