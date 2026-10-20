"""Blender side helpers (run inside Blender: blender --background --python <script> -- <args>).

Frames: the exported OBJ is in vehicle-local blocks (x = pilot's left, y up, z forward). The OBJ importer's default
axes map that to Blender as X = x, Y = -z, Z = y, so in Blender the nose points to -Y, up is +Z and the pilot's left
is +X. g2b / b2g convert points between the two.
"""
import json, math, os, sys
import bpy, bmesh
from mathutils import Vector, Matrix
from mathutils.bvhtree import BVHTree


def g2b(p):
    return Vector((p[0], -p[2], p[1]))


def b2g(v):
    return [v.x, v.z, -v.y]


def reset():
    bpy.ops.wm.read_factory_settings(use_empty=True)


def import_aircraft(d):
    """imports model.obj (textured) and glass.obj from an export folder; returns (model objects, glass object, meta)"""
    meta = json.load(open(os.path.join(d, 'meta.json')))
    bpy.ops.wm.obj_import(filepath=os.path.join(d, 'model.obj'))
    model = [o for o in bpy.context.selected_objects]
    bpy.ops.wm.obj_import(filepath=os.path.join(d, 'glass.obj'))
    glass = bpy.context.selected_objects[0]
    gm = bpy.data.materials.new('canopy_glass')
    gm.use_nodes = True
    bsdf = gm.node_tree.nodes.get('Principled BSDF')
    bsdf.inputs['Base Color'].default_value = (0.6, 0.75, 0.85, 1)
    bsdf.inputs['Alpha'].default_value = 0.18
    gm.blend_method = 'BLEND' if hasattr(gm, 'blend_method') else None
    glass.data.materials.clear(); glass.data.materials.append(gm)
    # the texture: nearest filtering, as in game
    for o in model:
        for slot in o.material_slots:
            m = slot.material
            if m and m.use_nodes:
                for n in m.node_tree.nodes:
                    if n.type == 'TEX_IMAGE':
                        n.interpolation = 'Closest'
    return model, glass, meta


def bvh_of(objs):
    """one BVH tree over the evaluated meshes of the objects (world space)"""
    verts, polys = [], []
    dg = bpy.context.evaluated_depsgraph_get()
    for o in objs:
        eo = o.evaluated_get(dg)
        me = eo.to_mesh()
        off = len(verts)
        verts += [o.matrix_world @ v.co for v in me.vertices]
        polys += [[off + i for i in p.vertices] for p in me.polygons]
        eo.to_mesh_clear()
    return BVHTree.FromPolygons(verts, polys)


def look_at(cam, target):
    d = (Vector(target) - cam.location)
    cam.rotation_euler = d.to_track_quat('-Z', 'Y').to_euler()


def setup_render(w=1280, h=800):
    sc = bpy.context.scene
    sc.render.engine = 'BLENDER_EEVEE' if 'BLENDER_EEVEE' in [e.identifier for e in
                                                             bpy.types.RenderSettings.bl_rna.properties['engine'].enum_items] else 'BLENDER_EEVEE_NEXT'
    sc.render.resolution_x, sc.render.resolution_y = w, h
    sc.render.film_transparent = False
    world = bpy.data.worlds.new('sky'); sc.world = world
    world.use_nodes = True
    bg = world.node_tree.nodes.get('Background')
    bg.inputs['Color'].default_value = (0.55, 0.65, 0.78, 1); bg.inputs['Strength'].default_value = 0.35
    sun = bpy.data.lights.new('sun', 'SUN'); sun.energy = 2.2
    so = bpy.data.objects.new('sun', sun); sc.collection.objects.link(so)
    so.rotation_euler = (math.radians(50), math.radians(10), math.radians(30))
    try:
        sc.view_settings.view_transform = 'Standard'
    except Exception:
        pass
    return sc


def render_view(path, loc, target, lens=18, w=1280, h=800):
    sc = bpy.context.scene
    cd = bpy.data.cameras.new('cam'); cd.lens = lens; cd.clip_start = 0.01
    cam = bpy.data.objects.new('cam', cd); sc.collection.objects.link(cam)
    cam.location = Vector(loc); look_at(cam, target)
    sc.camera = cam
    sc.render.resolution_x, sc.render.resolution_y = w, h
    sc.render.filepath = path
    bpy.ops.render.render(write_still=True)
    bpy.data.objects.remove(cam)
