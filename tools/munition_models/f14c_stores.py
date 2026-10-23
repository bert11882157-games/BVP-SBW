#!/usr/bin/env python3
"""Store models and definitions added for the F-14C (War Thunder F-14D loads), after f16c_stores.py / f4_stores.py.

Built here, in the pack's aircraft-store format (nose -Z, one bone 'store', 1 block = 1 m):
  tank_280gal        the F-14's 280 US gal external tank (WT us_280gal_drop_tank, 90.7 kg empty). The real tank is the 267 US gal
                     Navy F-14 tank; its length and diameter were not found, so the pack's 300 gal tank is scaled by the cube root
                     of the volume ratio (0.977). Dimensions UNVERIFIED (estimate). VISUAL_ONLY like the other tanks.
  lantirn_pod        AN/AAQ-25 LANTIRN targeting pod (WT an_aaq_25_lantirn, 211.5 kg): 2.51 m x 0.38 m (the USAF LANTIRN targeting pod,
                     af.mil fact sheet 104582: 98.5 in x 15 in; the F-14's AAQ-25 is derived from it, no Navy figures found).
                     TARGETING_POD with the pack's pod mechanism (gimbal limits and zoom are estimates).
  mk82_x4            four Mk 82 on the pack's MER (the first four of its six stations); WT F-14D slot 5 carries eight (Mk 82 AIR x8 is\n                     offered as the pack's 3-bomb TER-9A load: 6).
Usage: python tools/munition_models/f14c_stores.py [--write]
Then:  python tools/munition_models/fix_winding.py <the new geo files>   (and --check)
"""
import json
import os
import sys
from collections import OrderedDict

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import f16c_stores as F  # noqa: E402
from f16c_stores import Mesh, revolve, lugs, centred, store, vec  # noqa: E402

OD = OrderedDict
NS, GEO, TEX, STORES = F.NS, F.GEO, F.TEX, F.STORES
NEW_GEO = []


def load(rel):
    with open(os.path.join(STORES, rel + '.json'), encoding='utf-8') as fh:
        return json.load(fh, object_pairs_hook=OD)


def build_lantirn():
    """AN/AAQ-25 LANTIRN targeting pod: 2.51 m x 0.38 m, blunt nose turret with the sensor window, a chin window, no fins."""
    L, d = 2.51, 0.38
    r = d / 2
    m = Mesh()
    prof = [(0.0, 0.10), (0.05, 0.145), (0.14, 0.17), (0.40, r), (L - 0.50, r), (L, 0.13)]

    def col(s):
        if s < 0.05:
            return 'glass'
        return 'grey' if s < L - 0.50 else 'dgrey'
    revolve(m, prof, col, cuts=[0.05, L - 0.50], caps=('glass', 'dgrey'))
    m.box(-0.09, 0.09, -r - 0.004, -r + 0.016, 0.10, 0.42, 'glass')      # laser / FLIR chin window
    lugs(m, L * 0.5, 0.356, r)
    return centred(m, L), L


def main(argv):
    if '--write' not in argv:
        print('dry run: pass --write to generate files')
        return
    os.makedirs(GEO, exist_ok=True)
    os.makedirs(TEX, exist_ok=True)
    # ---- tank_280gal: the pack's 300 gal tank at 0.977
    g = json.load(open(os.path.join(GEO, 'tank_300gal.geo.json'), encoding='utf-8'))
    d = g['minecraft:geometry'][0]
    d['description']['identifier'] = 'geometry.aircraft_store.tank_280gal'
    pm = d['bones'][0]['poly_mesh']
    k = (280 / 300) ** (1 / 3)
    pm['positions'] = [[round(c * k, 4) for c in p] for p in pm['positions']]
    F.write_json_compact(os.path.join(GEO, 'tank_280gal.geo.json'), g)
    import shutil
    shutil.copyfile(os.path.join(TEX, 'tank_300gal.png'), os.path.join(TEX, 'tank_280gal.png'))
    NEW_GEO.append('tank_280gal')
    cat = F.Catalogue()
    a, launch, geom = F.anchors_for('tank_280gal')
    cat.add('munition/tank_280gal', store('280 US gal external fuel tank', 'VISUAL_ONLY', 'tank_280gal', a, Capacity=1, MassKg=955.7,
                                          LaunchOffset=launch))
    # ---- LANTIRN
    m, L = build_lantirn()
    F.save_model('lantirn_pod', m)
    NEW_GEO.append('lantirn_pod')
    a, launch, geom = F.anchors_for('lantirn_pod')
    import munitions
    eye = munitions.to_anchor_frame([geom.x0, geom.y0, geom.zmin + 0.02])
    cat.add('sensor/lantirn_pod', store('AN/AAQ-25 LANTIRN targeting pod', 'TARGETING_POD', 'lantirn_pod', a, Item='minecraft:spyglass',
                                        Capacity=1, MassKg=211.5,
                                        TargetingPod=OD([('Eye', vec(eye)), ('YawLimit', 120), ('PitchMin', -15), ('PitchMax', 90),
                                                         ('MaxZoom', 16), ('Range', 8192)])))
    # ---- Mk 82 x4 on the MER (the first four stations of mk82_x6)
    mer = load('munition/mk82_x6')
    out = OD()
    for kk, v in mer.items():
        if kk in ('MaxPerPylon', 'FixedRackCount', 'RackAdapter', 'RackSpacing', 'RackColumns', 'RackMassKg', 'LaunchOffset'):
            continue
        out[kk] = v
    out['Name'] = 'Mk 82 \u00b7 500 lb freefall \u00b7 MER four-bomb load'
    out['MaxPerPylon'] = 4
    out['FixedRackCount'] = 4
    out['RackAdapter'] = json.loads(json.dumps(mer['RackAdapter']))
    out['RackAdapter']['Stations'] = mer['RackAdapter']['Stations'][:4]
    out['RackSpacing'] = mer['RackSpacing']
    out['RackColumns'] = 2
    out['RackMassKg'] = mer['RackMassKg']
    out['LaunchOffset'] = mer['LaunchOffset']
    cat.add('munition/mk82_x4', out)
    print('written; new geo:', NEW_GEO)


if __name__ == '__main__':
    main(sys.argv[1:])
