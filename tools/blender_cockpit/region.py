"""Blender: which model objects have vertices in a box (metres from the eye: right, up, forward).
blender --background BLEND --python region.py -- META_JSON r0 r1 u0 u1 f0 f1"""
import json, sys
from mathutils import Vector
import bpy

a = sys.argv[sys.argv.index('--') + 1:]
meta = json.load(open(a[0]))
r0, r1, u0, u1, f0, f1 = map(float, a[1:7])
p = meta['eyes'][0]; E = Vector((p[0], -p[2], p[1])); k = meta['tub']['s']
R, U, F = Vector((-1, 0, 0)), Vector((0, 0, 1)), Vector((0, -1, 0))
for o in sorted(bpy.data.objects, key=lambda o: o.name):
    if o.type != 'MESH' or not ('__' in o.name or o.name.startswith('wreck')):
        continue
    q = [((v - E).dot(R) / k, (v - E).dot(U) / k, (v - E).dot(F) / k) for v in (o.matrix_world @ v.co for v in o.data.vertices)]
    inside = [c for c in q if r0 <= c[0] <= r1 and u0 <= c[1] <= u1 and f0 <= c[2] <= f1]
    if inside:
        lo = [round(min(c[i] for c in inside), 3) for i in range(3)]
        hi = [round(max(c[i] for c in inside), 3) for i in range(3)]
        print('REG', o.name, len(inside), lo, hi)
