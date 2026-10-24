"""blender -b --factory-startup -P blender_view.py -- MODEL.obj OUT.png AZIM ELEV SCALE CX CY CZ [SIZE] [random|texture] [transparent]
OBJ frame (blocks): +Y nose, +X right, +Z up. AZIM degrees about Z from the nose (+Y) towards +X (right side).
ELEV degrees above horizon. SCALE = ortho width in blocks. Orthographic."""
import math, sys, os
import bpy
from mathutils import Vector, Matrix
a = sys.argv[sys.argv.index('--') + 1:]
obj, out = a[0], a[1]
az, el, sc = map(float, a[2:5]); c = Vector(map(float, a[5:8]))
size = int(a[8]) if len(a) > 8 else 900
mode = a[9] if len(a) > 9 else 'texture'
transparent = len(a) > 10 and a[10] == 'transparent'
bpy.ops.wm.read_factory_settings(use_empty=True)
bpy.ops.wm.obj_import(filepath=obj)
for mat in bpy.data.materials:
    if mat.use_nodes:
        for n in mat.node_tree.nodes:
            if n.type == 'TEX_IMAGE': n.interpolation = 'Closest'
s = bpy.context.scene
s.render.engine = 'BLENDER_WORKBENCH'
sh = s.display.shading
sh.light = 'STUDIO'; sh.color_type = 'RANDOM' if mode == 'random' else 'TEXTURE'
sh.show_backface_culling = False; sh.show_object_outline = False
s.render.resolution_x = size; s.render.resolution_y = int(size * 0.75)
w = bpy.data.worlds.new('w'); s.world = w; w.color = (0.82, 0.84, 0.87)
s.view_settings.view_transform = 'Standard'
s.render.film_transparent = transparent
s.render.image_settings.color_mode = 'RGBA' if transparent else 'RGB'
cd = bpy.data.cameras.new('c'); cam = bpy.data.objects.new('c', cd); s.collection.objects.link(cam); s.camera = cam
cd.type = 'ORTHO'; cd.ortho_scale = sc; cd.clip_end = 1000; cd.clip_start = 0.01
d = Vector((math.sin(math.radians(az)) * math.cos(math.radians(el)), math.cos(math.radians(az)) * math.cos(math.radians(el)), math.sin(math.radians(el))))
cam.location = c + d * 60
f = -d; up = Vector((0, 0, 1)) if abs(f.z) < 0.99 else Vector((0, 1, 0))
r = f.cross(up).normalized(); u = r.cross(f)
cam.rotation_euler = Matrix((r, u, -f)).transposed().to_euler()
s.render.filepath = out
bpy.ops.render.render(write_still=True)
print('RV_OK', out)
