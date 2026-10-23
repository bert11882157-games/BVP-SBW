"""Blender: the smallest gap between the cockpit parts and the canopy glass (metres), from a structure.blend.
blender --background BLEND --python clearance.py -- META_JSON"""
import json, sys
import bpy
from mathutils import Vector
from mathutils.bvhtree import BVHTree

meta = json.load(open(sys.argv[sys.argv.index('--') + 1]))
k = meta['tub']['s']
g = bpy.data.objects['canopy_glass']
dg = bpy.context.evaluated_depsgraph_get()
me = g.evaluated_get(dg).to_mesh()
gl = BVHTree.FromPolygons([g.matrix_world @ v.co for v in me.vertices], [list(p.vertices) for p in me.polygons])
worst = []
for o in bpy.data.objects:
    if o.type != 'MESH' or '__' in o.name or o.name.startswith('wreck') or o.name == 'canopy_glass':
        continue
    best = None
    for v in o.data.vertices:
        p = o.matrix_world @ v.co
        loc, n, i, d = gl.find_nearest(p)
        if loc is not None and (best is None or d < best):
            best = d
    if best is not None:
        worst.append((best / k, o.name))
worst.sort()
print('CLEARANCE_MIN', [(round(d, 3), n) for d, n in worst[:8]])
print('CLEARANCE_UNDER_3CM', [n for d, n in worst if d < 0.03])
