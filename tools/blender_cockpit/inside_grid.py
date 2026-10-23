"""Blender: prints, for a cockpit export, which points of a grid across the cockpit lie inside the hull (as the rigid
fit of cockpit_build.py tests it: fewer than 12 of the 26 rays from the point get out past the hull and glass;
GRID26=1 prints the count per point, 0-9 then a-q).

Run: blender --background --python inside_grid.py -- EXPORTDIR F1,F2,...   (stations in metres ahead of the eye)
Prints one map per station: rows from 0.2 m above the eye down to 1.2 m below, columns from 0.5 m left to 0.5 m
right; '#' inside, '.' outside, 'g' inside only thanks to the glass (outside the skin alone).
"""
import os, sys
from mathutils import Vector
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import bl_common as B

args = sys.argv[sys.argv.index('--') + 1:]
B.reset()
model, glass, meta = B.import_aircraft(args[0])
k = meta['tub']['s'] if meta.get('tub') else 1.0
R, U, F = Vector((-1, 0, 0)), Vector((0, 0, 1)), Vector((0, -1, 0))
skin, gl = B.bvh_of(model), B.bvh_of([glass])
E = B.g2b(meta['eyes'][0])


D26 = [Vector((x, y, z)).normalized() for x in (-1, 0, 1) for y in (-1, 0, 1) for z in (-1, 0, 1) if (x, y, z) != (0, 0, 0)]


def out(bvh, p, d):
    return bvh.ray_cast(p, d, 40.0 * k)[0] is None


for f in [float(x) for x in args[1].split(',')]:
    print(f'STATION F={f}')
    for i in range(29):
        u = 0.2 - i * 0.05
        row = ''
        for j in range(21):
            r = -0.5 + j * 0.05
            p = E + (R * r + U * u + F * f) * k
            miss = sum(out(skin, p, d) and out(gl, p, d) for d in (R, -R, U, -U, F, -F))
            miss_skin = sum(out(skin, p, d) for d in (R, -R, U, -U, F, -F))
            if os.environ.get('GRID26'):
                m26 = sum(out(skin, p, d) and out(gl, p, d) for d in D26)
                row += '0123456789abcdefghijklmnopq'[m26]
                continue
            row += '.' if miss >= 2 else ('g' if miss_skin >= 2 else '#')
        print(f'{u:+.2f} {row}')
