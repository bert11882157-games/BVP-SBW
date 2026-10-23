"""Blender: what the scene camera would see at given pixels of a view (same camera as render_view).
blender --background BLEND --python probe_px.py -- META_JSON loc(r,u,f) target(r,u,f) lens W H x,y [x,y ...]"""
import json, sys
import bpy
from mathutils import Vector
sys.path.insert(0, __import__('os').path.dirname(__import__('os').path.abspath(__file__)))
import bl_common as B

a = sys.argv[sys.argv.index('--') + 1:]
meta = json.load(open(a[0]))
E = B.g2b(meta['eyes'][0]); k = meta['tub']['s']
R, U, F = Vector((-1, 0, 0)), Vector((0, 0, 1)), Vector((0, -1, 0))
P = lambda r, u, f: E + (R * r + U * u + F * f) * k
loc = P(*map(float, a[1].split(','))); tgt = P(*map(float, a[2].split(',')))
lens, W, H = float(a[3]), int(a[4]), int(a[5])
cd = bpy.data.cameras.new('c'); cd.lens = lens
cam = bpy.data.objects.new('c', cd); bpy.context.scene.collection.objects.link(cam)
cam.location = loc; B.look_at(cam, tgt); bpy.context.view_layer.update()
sc = bpy.context.scene; sc.render.resolution_x, sc.render.resolution_y = W, H
fr = [cam.matrix_world @ v for v in cd.view_frame(scene=sc)]   # tr, br, bl, tl
dg = bpy.context.evaluated_depsgraph_get()
for xy in a[6:]:
    x, y = map(float, xy.split(','))
    tx, ty = x / W, y / H
    top = fr[3].lerp(fr[0], tx); bot = fr[2].lerp(fr[1], tx)
    p = top.lerp(bot, ty)
    d = (p - loc).normalized()
    start = loc
    for _ in range(8):                       # look through the canopy glass
        hit, hp, n, i, o, m = sc.ray_cast(dg, start, d)
        if not hit or o.name != 'canopy_glass':
            break
        start = hp + d * 1e-4
    if hit:
        q = hp - E
        print('PX', xy, o.name, 'facing camera' if n.dot(d) < 0 else 'BACKFACE',
              [round(q.dot(R) / k, 3), round(q.dot(U) / k, 3), round(q.dot(F) / k, 3)])
    else:
        print('PX', xy, 'nothing')
