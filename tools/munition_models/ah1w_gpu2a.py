#!/usr/bin/env python3
"""GPU-2/A 20 mm gun pod (AH-1W SuperCobra, War Thunder ah_1w presets gpu_2a_1..4): model and store.

Same conventions as f16c_stores.py / f4c_stores.py (1 block = 1 m, flat colour cells, nose at -Z, body axis on the origin,
one bone 'store'); the lug anchors are derived from the written model. The store is a GUN_POD; its native weapon
(GpuInner / GpuOuter) is written by work/elite_expansion_2/tools/ah1w_armament.py.

Dimensions. The pod's published length and diameter were not found (Wikipedia, FAS DOD 101 and the kit makers give only
the loaded mass 586 lb / 266 kg with 300 rounds, the M197 and the 750 / 1,500 rpm choice). Known: the bare M197 is
1.83 m long (GD-OTS) and the barrels leave the nose fairing. A modding data file lists 2.15 m x 0.30 m (unit-less, short
for a pod that holds the whole gun); the task brief said about 3.5 m x 0.40 to 0.45 m. The model takes 3.05 m overall
(2.50 m cylindrical body with a rounded nose and tail cone, 0.55 m of barrel clamp and muzzles in front of it) and 0.36 m
body diameter: GUESS between the two, to be corrected if a drawing turns up. Suspension lugs 14 in (0.356 m) apart, as every
NATO pod. Three barrels in the M197's triangle (one up, two down), a muzzle clamp ring, black muzzle ends.

MassKg 96.3 is War Thunder's pod: gun 66 kg (cannonM197_external) + 300 x 0.101 kg rounds. The real loaded pod is 266 kg.

Usage: python tools/munition_models/ah1w_gpu2a.py [--write]
Then:  python tools/munition_models/fix_winding.py <the new geo file>   (and --check)
"""
import math
import os
import sys
from collections import OrderedDict

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import f16c_stores as F  # noqa: E402
from f16c_stores import Mesh, revolve, lugs, centred, ogive  # noqa: E402

OD = OrderedDict
NAME = 'gpu2a'
L, D = 3.05, 0.36
BARREL = 0.55           # barrels, clamp and muzzles in front of the nose fairing
R = D / 2


def build():
    m = Mesh()
    nose = 0.34          # rounded nose fairing (s measured from the muzzle tips)
    s0 = BARREL
    body_end = L - 0.30  # start of the tail cone
    prof = [(s0, 0.075)] + [(s0 + s, r) for s, r in ogive(nose, 0.075, R)[1:]] + [(body_end, R), (L, 0.62 * R)]

    def col(s):
        if s < s0 + nose:
            return 'dgrey'
        if s > body_end:
            return 'dgrey'
        if 1.70 <= s < 1.78:
            return 'yellow'
        return 'olive'
    revolve(m, prof, col, cuts=[s0 + nose, body_end, 1.70, 1.78], caps=('dgrey', 'dgrey'))
    # three barrels (M197 triangle: one above the axis, two below) and a clamp ring near the muzzles
    rb, pitch = 0.024, 0.043
    for k in range(3):
        a = math.radians(90 + 120 * k)
        cx, cy = pitch * math.cos(a), pitch * math.sin(a)
        revolve(m, [(0.0, rb), (0.06, rb), (BARREL + 0.01, rb)], lambda s: 'black' if s < 0.06 else 'dgrey',
                cuts=[0.06], ax=cx, ay=cy, caps=('black', 'dgrey'))
    revolve(m, [(0.20, 0.085), (0.28, 0.085)], lambda s: 'dgrey', caps=('dgrey', 'dgrey'))
    # 14 in lugs on the top, a saddle plate under them
    zc = 1.45 + 0.0
    m.box(-0.05, 0.05, R - 0.02, R + 0.002, zc - 0.28, zc + 0.28, 'rack')
    lugs(m, zc, 0.356, R)
    return centred(m, L)


def main(argv):
    if '--write' not in argv:
        print('dry run: pass --write to generate files')
        return
    os.makedirs(F.GEO, exist_ok=True); os.makedirs(F.TEX, exist_ok=True)
    F.save_model(NAME, build())
    a, launch, g = F.anchors_for(NAME)
    d = F.store('GPU-2/A · 20 mm M197 gun pod', 'GUN_POD', NAME, a, MassKg=96.3)
    for k in ('SideMountAnchors', 'MountAxis'):
        d.pop(k, None)       # a gun pod hangs from its top lugs only, as the SUU-23/A
    F.write_json(os.path.join(F.STORES, 'gun_pod', NAME + '.json'), d)
    print('anchor', a['MountAnchor'], 'axis', a.get('MountAxis'))


if __name__ == '__main__':
    main(sys.argv[1:])
