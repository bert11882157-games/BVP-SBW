#!/usr/bin/env python3
"""Removes the models' own canopy glass: hull faces around a crew station whose texture is see-through (the panes the
model draws itself, which show as pale floating squares in game). Companion of no_glass.py (CanopyGlass), the
owner's call of 2026-10-20: no glass for now.

A face goes when its texture is mostly translucent (strip._see_through) and its centre lies near a crew eye: within
0.9 m sideways, no lower than 0.6 m under the eye, from 1.6 m behind to 1.8 m ahead of it. The new cockpit bones
(__cockpit) are never touched.

Usage: python3 tools/blender_cockpit/no_model_glass.py [--dry-run] VID...   (aircraft and helicopters only: ground vehicles
have see-through periscopes, nets and cages near their crews)
"""
import json, os, sys
import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, '..', 'cockpit_models'))
sys.path.insert(0, os.path.join(HERE, '..', 'canopy_models'))
import cockpit_lib as L  # noqa: E402
import strip as S        # noqa: E402

dry = '--dry-run' in sys.argv
ids = [a for a in sys.argv[1:] if not a.startswith('--')]
if not ids:
    sys.exit(__doc__)
total = {}
for vid in ids:
    try:
        ac = L.Aircraft(vid)
    except (FileNotFoundError, KeyError):
        continue
    eyes = []
    for i, s in enumerate(ac.data.get('Seats') or []):
        try:
            eyes.append(ac.eye(i))
        except (KeyError, TypeError):
            pass
    tp = L.texture_path(vid)
    if not eyes or not os.path.isfile(tp):
        continue
    tex = np.array(Image.open(tp).convert('RGBA'))
    n = 0
    for b in ac.bones:
        pm = b.get('poly_mesh')
        if b['name'].endswith('__cockpit') or not pm or not pm.get('polys'):
            continue
        P = np.array(pm['positions'], float) * np.array([1, 1, -1]) / 16.0
        U = np.array(pm['uvs'], float)
        for i, poly in enumerate(pm['polys']):
            if not poly:
                continue
            c = P[[v[0] for v in poly]].mean(0)
            if not any(abs(c[0] - e[0]) < 0.9 and c[1] > e[1] - 0.6 and e[2] - 1.6 < c[2] < e[2] + 1.8 for e in eyes):
                continue
            if S._see_through(tex, U[[v[2] for v in poly]]):
                pm['polys'][i] = []
                n += 1
    if n:
        total[vid] = n
        if not dry:
            with open(ac.geo_path, 'w') as fh:
                json.dump(ac.geo, fh, separators=(',', ':'))
print(f'{"would remove" if dry else "removed"} see-through faces: {json.dumps(total)}')
