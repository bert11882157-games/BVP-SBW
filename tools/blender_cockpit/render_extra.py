"""Blender: extra preview renders from a saved structure.blend.
blender --background BLEND --python render_extra.py -- OUTDIR META_JSON name:r,u,f:r,u,f:lens [...]
Positions are metres from the pilot's eye (right, up, forward)."""
import json, os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import bl_common as B
from mathutils import Vector

a = sys.argv[sys.argv.index('--') + 1:]
out, meta = a[0], json.load(open(a[1]))
E = B.g2b(meta['eyes'][0]); k = meta['tub']['s']
R, U, F = Vector((-1, 0, 0)), Vector((0, 0, 1)), Vector((0, -1, 0))


def P(r, u, f):
    return E + (R * r + U * u + F * f) * k


import bpy
for spec in a[2:]:
    if spec.startswith('section:'):
        # a cut-away side view: an orthographic camera on the pilot's right, its near clip at the centre line, so
        # the right half of everything is cut off and the cockpit is seen in section
        _, name, cr, cu, cf, scale = spec.split(':')
        sc = bpy.context.scene
        cd = bpy.data.cameras.new('sec'); cd.type = 'ORTHO'; cd.ortho_scale = float(scale) * k
        cam = bpy.data.objects.new('sec', cd); sc.collection.objects.link(cam)
        dist = 3.0
        cam.location = P(float(cr) + dist, float(cu), float(cf))
        cd.clip_start = (dist + float(cr)) * k - 0.01 * k; cd.clip_end = 20 * k
        B.look_at(cam, P(float(cr), float(cu), float(cf)))
        sc.camera = cam
        sc.render.resolution_x, sc.render.resolution_y = 1600, 900
        sc.render.filepath = os.path.join(out, name + '.png')
        bpy.ops.render.render(write_still=True)
        bpy.data.objects.remove(cam)
        continue
    name, loc, tgt, lens = spec.split(':')
    B.render_view(os.path.join(out, name + '.png'), P(*map(float, loc.split(','))), P(*map(float, tgt.split(','))),
                  lens=float(lens), w=1280, h=800)
print('EXTRA_DONE')
