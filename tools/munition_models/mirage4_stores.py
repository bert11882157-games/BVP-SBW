#!/usr/bin/env python3
"""Store models and store definitions for the Mirage IVA (mirage_4a), after mirage3e_stores.py.

Built here, in the pack's aircraft-store format (nose -Z, one bone 'store', 1 block = 1 m). The Mirage IV is not in War Thunder, so the
loads are the real ones (Wikipedia, Dassault); guesses are marked GUESS.

  tank_2500l  the Mirage IV model's own 2,500 l wing tank, cut out at real size (5.57 m x 0.63 m; work/elite_expansion_2/mirage4/stores).
              Cut from a supplied model, so it is NOT marked (AI). 2,500 l x 0.8 kg/l = 2,000 kg of fuel + about 200 kg of tank.
  ct52_pod    CT52 reconnaissance pod, carried in the belly recess of the Mirage IVR and on the IVP's centreline pylon (3 or 4 cameras, or an
              infrared linescan). Built here (AI): 4.20 m x 0.78 m, 1,100 kg are GUESSES (no published dimensions found).
The nuclear AN-22 (the model's baked belly bomb) is NOT offered; the belly station takes conventional stores already in the pack
(Mk 83 / Mk 82 racks, SAMP bombs) and this pod.

Usage: python tools/munition_models/mirage4_stores.py [--write]
Then:  python tools/munition_models/fix_winding.py <the new geo files>   (and --check)
"""
import json
import math
import os
import shutil
import sys
from collections import OrderedDict

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import f16c_stores as F  # noqa: E402
from f16c_stores import Mesh, revolve, lugs, centred, ogive  # noqa: E402

NS, STORES, GEO, TEX = F.NS, F.STORES, F.GEO, F.TEX
CUT = os.environ.get('MIRAGE4_STORE_SOURCE', r'C:\Users\udstu\Documents\Superb warfare\work\elite_expansion_2\mirage4\stores')
NEW_GEO = []
DOT = '\u00b7'


def import_cut(src, name):
    """Copy a part cut from the Mirage IV model (geo + png); one bone 'store', nose -Z, body on the origin."""
    g = json.load(open(os.path.join(CUT, src, src + '.geo.json'), encoding='utf-8'))
    g['minecraft:geometry'][0]['description']['identifier'] = 'geometry.aircraft_store.' + name
    F.write_json_compact(os.path.join(GEO, name + '.geo.json'), g)
    shutil.copyfile(os.path.join(CUT, src, src + '.png'), os.path.join(TEX, name + '.png'))
    NEW_GEO.append(name)
    return name


def build_ct52(L=4.20, d=0.78):
    """CT52 pod: ogive nose, cylindrical body (white, the pod is a light grey/white external fit), tapered tail, three camera windows on the
    belly (GUESS: shape and count; the real pod has 3 or 4 oblique/vertical camera ports) and two lugs."""
    r = d / 2
    nose, tail = 0.95, 0.80
    prof = [(0.0, 0.05)] + ogive(nose, 0.05, r)[1:] + [(L - tail, r), (L, 0.38 * r)]

    def col(s):
        return 'dgrey' if s < 0.35 else ('grey' if s < nose else ('white' if s < L - tail else 'grey'))
    m = Mesh()
    revolve(m, prof, col, cuts=[0.35, nose, L - tail], caps=('dgrey', 'dgrey'))
    for zc in (1.55, 2.05, 2.55):                       # camera windows on the underside (-Y), 0.30 m x 0.22 m, flush
        m.box(-0.15, 0.15, -r - 0.004, -r + 0.012, zc - 0.11, zc + 0.11, 'glass')
    lugs(m, L * 0.5, 0.356, r)
    return centred(m, L), L


def main(argv):
    if '--write' not in argv:
        print('dry run: pass --write to generate files')
        return
    os.makedirs(GEO, exist_ok=True)
    os.makedirs(TEX, exist_ok=True)
    cat = F.Catalogue()

    import_cut('tank_2500l', 'tank_2500l')
    m, L = build_ct52()
    F.save_model('ct52_pod', m)
    NEW_GEO.append('ct52_pod')

    a, launch, g = F.anchors_for('tank_2500l')
    cat.add('munition/tank_2500l', F.store('2,500 l drop tank', 'VISUAL_ONLY', 'tank_2500l', a, Capacity=1, MassKg=2200.0, LaunchOffset=launch))
    a, launch, g = F.anchors_for('ct52_pod')
    cat.add('munition/ct52_pod', F.store(f'CT52 reconnaissance pod', 'VISUAL_ONLY', 'ct52_pod', a, Capacity=1, MassKg=1100.0,
                                         LaunchOffset=launch))
    print('written', len(cat.items), 'stores; new geo files:', ' '.join(NEW_GEO))


if __name__ == '__main__':
    main(sys.argv[1:])
