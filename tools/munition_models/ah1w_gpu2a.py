#!/usr/bin/env python3
"""GPU-2/A 20 mm gun pod (AH-1W SuperCobra, War Thunder ah_1w presets gpu_2a_1..4): model and store.

Same conventions as f16c_stores.py / f4c_stores.py (1 block = 1 m, flat colour cells, nose at -Z, body axis on the origin,
one bone 'store'); the lug anchors are derived from the written model. The store is a GUN_POD; its native weapon
(GpuInner / GpuOuter) is written by work/elite_expansion_2/tools/ah1w_armament.py.

Shape (bert's references, a 3D render and a 1980 photo under an A-4M): length to maximum diameter about 6.3 : 1. Published length and
diameter were not found (Wikipedia, FAS DOD 101 and the kit makers give only the loaded mass 586 lb / 266 kg, the M197 and the 750 / 1,500
rpm choice), so the length stays at 3.05 m and the diameter follows from the ratio, 0.48 m. Rear 28 % a straight cylinder with a blunt
rounded tail cap and panel joints; middle 58 % a long cone tapering forward to 0.53 of the maximum diameter; front 14 % a separate dark
nose shroud whose upper side is a hood over a recessed opening, the three M197 barrels sitting in it slightly below the axis inside a
perforated ring. Right side: a louvred vent (6 slats in a raised frame) 57 % back from the nose tip and a horizontal slot with a bar 42 %
back. On top: 14 in (0.356 m) lugs on a saddle plate and a small fairing aft. Light grey (the palette's aluminium) with thin dark rivet bands at the joints.
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
from f16c_stores import Mesh, revolve, disc, lugs, centred  # noqa: E402

OD = OrderedDict
NAME = 'gpu2a'
L, D = 3.05, 0.48
R = D / 2
NOSE_END, TAPER_END = 0.43, 2.20     # shroud / cone / cylinder joints (s from the nose tip)
R_FRONT = 0.53 * R                   # cone radius at the shroud
FACE = 0.15                          # the recessed opening's back face
MUZ_S, MUZ_Y = 0.06, -0.03           # muzzle tips: s from the nose tip, height below the axis (ah1w_armament.py uses both)
LUG_Z = 2.08


def r_at(s):
    return R_FRONT + (R - R_FRONT) * (s - NOSE_END) / (TAPER_END - NOSE_END) if s < TAPER_END else R


def hood(m, s0, s1, r, n=16):
    """Upper half shell of the shroud (angles 0..180 degrees), outer dark grey, inner black, thin edge strips."""
    for k in range(n // 2):
        a0, a1 = 2 * math.pi * k / n, 2 * math.pi * (k + 1) / n
        for rr, col, flip in ((r, 'dgrey', False), (r - 0.008, 'black', True)):
            q = [np.array([rr * math.cos(a0), rr * math.sin(a0), s0]), np.array([rr * math.cos(a1), rr * math.sin(a1), s0]),
                 np.array([rr * math.cos(a1), rr * math.sin(a1), s1]), np.array([rr * math.cos(a0), rr * math.sin(a0), s1])]
            m.poly(q[::-1] if flip else q, col)
        m.poly([np.array([r * math.cos(a0), r * math.sin(a0), s0]), np.array([(r - 0.008) * math.cos(a0), (r - 0.008) * math.sin(a0), s0]),
                np.array([(r - 0.008) * math.cos(a1), (r - 0.008) * math.sin(a1), s0]), np.array([r * math.cos(a1), r * math.sin(a1), s0])], 'dgrey')


def build():
    m = Mesh()
    joints = [NOSE_END, TAPER_END, 2.45, 2.80]
    bands = [s + d for s in joints for d in (-0.006, 0.006)]
    body_end = L - 0.07
    prof = [(FACE, R_FRONT), (NOSE_END, R_FRONT), (TAPER_END, R), (body_end, R), (L - 0.03, 0.88 * R), (L, 0.62 * R)]

    def col(s):
        if s < NOSE_END:
            return 'dgrey'
        return 'dgrey' if any(j - 0.006 <= s < j + 0.006 for j in joints) else 'alu'
    revolve(m, prof, col, cuts=bands, caps=('dgrey', 'grey'))
    hood(m, 0.0, FACE, R_FRONT)
    # three M197 barrels recessed in the opening (one above, two below, the cluster slightly below the axis) and a ring of vent holes
    rb, pitch = 0.024, 0.043
    for k in range(3):
        a = math.radians(90 + 120 * k)
        revolve(m, [(MUZ_S, rb), (MUZ_S + 0.03, rb), (FACE + 0.02, rb)], lambda s: 'black' if s < MUZ_S + 0.03 else 'dgrey',
                cuts=[MUZ_S + 0.03], ax=pitch * math.cos(a), ay=MUZ_Y + pitch * math.sin(a), caps=('black', 'dgrey'))
    for k in range(10):
        a = 2 * math.pi * k / 10
        disc(m, 0.092 * math.cos(a), MUZ_Y + 0.092 * math.sin(a), FACE - 0.002, 0.012, 'black', -1, n=6)
    # louvred vent, 6 slats in a raised frame, on the vehicle's right (geo -x) at 57 % back from the nose tip
    zc, hw, hh = 0.57 * L, 0.15, 0.09
    rr = r_at(zc)
    x_out, x_in = -(rr + 0.014), -(rr - 0.03)
    m.box(x_in, x_out, -hh, hh, zc - hw, zc + hw, 'dgrey')
    m.box(x_in, x_out - 0.004, -hh + 0.015, hh - 0.015, zc - hw + 0.015, zc + hw - 0.015, 'black')
    for i in range(6):
        z = zc - hw + 0.03 + i * (2 * hw - 0.06) / 5
        m.box(x_in, x_out - 0.012, -hh + 0.015, hh - 0.015, z - 0.007, z + 0.007, 'alu')
    # horizontal slot with a bar across it at 42 % back
    zs = 0.42 * L
    rs = r_at(zs)
    m.box(-(rs - 0.03), -(rs + 0.006), -0.03, 0.03, zs - 0.12, zs + 0.12, 'black')
    m.box(-(rs - 0.03), -(rs + 0.012), -0.034, 0.034, zs - 0.006, zs + 0.006, 'dgrey')
    # saddle plate and the 14 in lugs over the mid / rear body, a small fairing aft on top
    m.box(-0.05, 0.05, r_at(LUG_Z - 0.4) - 0.015, R + 0.002, LUG_Z - 0.40, LUG_Z + 0.40, 'alu')
    lugs(m, LUG_Z, 0.356, R)
    m.box(-0.045, 0.045, R - 0.01, R + 0.035, 2.62, 2.86, 'alu')
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
