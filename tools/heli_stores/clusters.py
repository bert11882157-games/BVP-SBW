"""Clusters of a bone's parts along |x| (gap > GAP blocks between clusters), each side separately: the stores of one
pylon. Usage: clusters.py <geo.json> <bone> [gap] [min_abs_x]"""
import json, sys
import numpy as np
from comps import bone_parts

geo = json.load(open(sys.argv[1]))
gap = float(sys.argv[3]) if len(sys.argv) > 3 else 0.3
min_x = float(sys.argv[4]) if len(sys.argv) > 4 else 0.0
_, parts = bone_parts(geo, sys.argv[2])
for side, sgn in (('L', 1), ('R', -1)):
    ps = [q for q in parts if sgn * (q['lo'][0] + q['hi'][0]) / 2 > min_x]
    ps.sort(key=lambda q: sgn * q['lo'][0] if sgn > 0 else -q['hi'][0])
    clusters = []
    for q in ps:
        lo, hi = sorted((sgn * q['lo'][0], sgn * q['hi'][0]))
        if clusters and lo <= clusters[-1]['xhi'] + gap:
            c = clusters[-1]; c['xhi'] = max(c['xhi'], hi); c['parts'].append(q)
        else:
            clusters.append({'xlo': lo, 'xhi': hi, 'parts': [q]})
    for c in clusters:
        lo = np.min([q['lo'] for q in c['parts']], 0); hi = np.max([q['hi'] for q in c['parts']], 0)
        print(f"{side} |x| {c['xlo']:5.2f}..{c['xhi']:5.2f}  y {lo[1]:5.2f}..{hi[1]:5.2f}  z {lo[2]:6.2f}..{hi[2]:6.2f}  "
              f"parts {len(c['parts']):3d} polys {sum(len(q['polys']) for q in c['parts'])}")
