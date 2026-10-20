"""Blender: prints each cockpit part's extents in metres from the eye (right, up, forward).
blender --background BLEND --python extents.py -- META_JSON"""
import json, sys
from mathutils import Vector
import bpy

meta = json.load(open(sys.argv[sys.argv.index('--') + 1]))
p = meta['eyes'][0]; E = Vector((p[0], -p[2], p[1])); k = meta['tub']['s']
R, U, F = Vector((-1, 0, 0)), Vector((0, 0, 1)), Vector((0, -1, 0))
for o in sorted(bpy.data.objects, key=lambda o: o.name):
    if o.type != 'MESH' or o.name.startswith('wreck') or '__' in o.name or o.name == 'canopy_glass':
        continue
    vs = [o.matrix_world @ v.co for v in o.data.vertices]
    if not vs:
        continue
    q = [((v - E).dot(R) / k, (v - E).dot(U) / k, (v - E).dot(F) / k) for v in vs]
    lo = [round(min(c[i] for c in q), 3) for i in range(3)]
    hi = [round(max(c[i] for c in q), 3) for i in range(3)]
    print('EXT', o.name, lo, hi)
