"""Blender (bpy) side of the hand-built cockpits: the aircraft model in, the cockpit mesh out, previews and checks.

Frames: the vehicle is x left, y up, z forward in blocks (1 block = 1 m); Blender here is X to the pilot's right,
Y forward, Z up, also in metres: X = -x, Y = z, Z = y. The model file stores pixels (16 per block) with z negated.

Run with the bpy module (pip install bpy); nothing here needs the Blender UI.
"""
import json
import math
import os

import bpy
import bmesh
import numpy as np
from mathutils import Vector, Matrix
from mathutils.bvhtree import BVHTree

HERE = os.path.dirname(os.path.abspath(__file__))
GEN = os.path.join(HERE, '..', '..', 'bvp', 'src', 'generated', 'resources')
GEO_DIR = os.path.join(GEN, 'assets/berts_vehicle_pack/custom_geo')
ASSET_DIR = os.path.join(GEN, 'assets/berts_vehicle_pack/sbw/vehicles')
DATA_DIR = os.path.join(GEN, 'data/berts_vehicle_pack/sbw/vehicles')


def v2b(p):
    """vehicle-local blocks -> Blender metres"""
    p = np.asarray(p, float)
    return np.stack([-p[..., 0], p[..., 2], p[..., 1]], -1)


def b2v(p):
    p = np.asarray(p, float)
    return np.stack([-p[..., 0], p[..., 2], p[..., 1]], -1)


def reset():
    bpy.ops.wm.read_factory_settings(use_empty=True)
    sc = bpy.context.scene
    sc.unit_settings.system = 'METRIC'
    return sc


def collection(name):
    c = bpy.data.collections.get(name)
    if c is None:
        c = bpy.data.collections.new(name)
        bpy.context.scene.collection.children.link(c)
    return c


def image_material(name, tex_path, nearest=True):
    mat = bpy.data.materials.new(name)
    mat.use_nodes = True
    nt = mat.node_tree
    bsdf = nt.nodes.get('Principled BSDF')
    img = bpy.data.images.load(tex_path, check_existing=True)
    tn = nt.nodes.new('ShaderNodeTexImage')
    tn.image = img
    tn.interpolation = 'Closest' if nearest else 'Linear'
    nt.links.new(tn.outputs['Color'], bsdf.inputs['Base Color'])
    nt.links.new(tn.outputs['Alpha'], bsdf.inputs['Alpha'])
    bsdf.inputs['Roughness'].default_value = 0.8
    mat.blend_method = 'CLIP' if hasattr(mat, 'blend_method') else None
    return mat


def colour_material(name, rgb, rough=0.7, metal=0.0, alpha=1.0):
    mat = bpy.data.materials.get(name) or bpy.data.materials.new(name)
    mat.use_nodes = True
    bsdf = mat.node_tree.nodes.get('Principled BSDF')
    c = [x / 255.0 for x in rgb]
    bsdf.inputs['Base Color'].default_value = (srgb_to_lin(c[0]), srgb_to_lin(c[1]), srgb_to_lin(c[2]), 1)
    bsdf.inputs['Roughness'].default_value = rough
    bsdf.inputs['Metallic'].default_value = metal
    if alpha < 1:
        bsdf.inputs['Alpha'].default_value = alpha
    mat.diffuse_color = (c[0], c[1], c[2], alpha)        # workbench / viewport colour
    mat['rgb'] = list(rgb)
    return mat


def srgb_to_lin(c):
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


def mesh_object(name, verts, faces, uvs=None, mat=None, coll=None):
    """verts in Blender metres, faces as vertex index lists; uvs: per face-corner list matching faces"""
    me = bpy.data.meshes.new(name)
    me.from_pydata([tuple(v) for v in verts], [], [tuple(f) for f in faces])
    if uvs is not None:
        ul = me.uv_layers.new(name='UVMap')
        k = 0
        for poly in me.polygons:
            for li in poly.loop_indices:
                ul.data[li].uv = uvs[k]
                k += 1
    me.update()
    ob = bpy.data.objects.new(name, me)
    (coll or bpy.context.scene.collection).objects.link(ob)
    if mat is not None:
        ob.data.materials.append(mat)
    return ob


def load_geo(path):
    return json.load(open(path))


def import_bones(geo, tex_path, keep=lambda name: True, coll_name='aircraft'):
    """one object per bone with a poly_mesh (bone name as object name), textured with the aircraft texture"""
    coll = collection(coll_name)
    mat = image_material('aircraft_tex', tex_path)
    out = {}
    for b in geo['minecraft:geometry'][0]['bones']:
        pm = b.get('poly_mesh')
        if not pm or not pm.get('polys') or not keep(b['name']):
            continue
        P = np.array(pm['positions'], float) * np.array([1, 1, -1]) / 16.0
        UV = np.array(pm['uvs'], float)
        verts, faces, uvs = [], [], []
        for poly in pm['polys']:
            idx = []
            for v in poly:
                idx.append(len(verts))
                verts.append(v2b(P[v[0]]))
                uvs.append(tuple(UV[v[2]]))
            # the file winds faces clockwise from the front in mirrored pixels: reversed here for Blender
            faces.append(idx[::-1])
            uvs[-len(idx):] = uvs[-len(idx):][::-1]
        ob = mesh_object(b['name'], verts, faces, uvs, mat, coll)
        out[b['name']] = ob
    return out


def glass_object(tris, name='canopy_glass', coll_name='glass'):
    """the CanopyGlass triangles (vehicle blocks, flat list) as a mesh"""
    T = np.array(tris, float).reshape(-1, 3, 3)
    verts = v2b(T.reshape(-1, 3))
    faces = [(3 * i, 3 * i + 1, 3 * i + 2) for i in range(len(T))]
    mat = colour_material('glass', (170, 205, 225), rough=0.05, alpha=0.18)
    return mesh_object(name, verts, faces, None, mat, collection(coll_name))


def bvh_of(objs):
    """one BVH tree over the world-space triangles of several mesh objects"""
    verts, polys = [], []
    dg = bpy.context.evaluated_depsgraph_get()
    for ob in objs:
        ev = ob.evaluated_get(dg)
        me = ev.to_mesh()
        mw = ob.matrix_world
        base = len(verts)
        verts += [mw @ v.co for v in me.vertices]
        polys += [[base + i for i in p.vertices] for p in me.polygons]
        ev.to_mesh_clear()
    return BVHTree.FromPolygons(verts, polys, all_triangles=False), verts, polys


# ---------------------------------------------------------------- previews

def camera(name, loc, look_at=None, rot=None, lens_fov=70.0, coll=None):
    cam = bpy.data.objects.new(name, bpy.data.cameras.new(name))
    (coll or bpy.context.scene.collection).objects.link(cam)
    cam.location = Vector(loc)
    if look_at is not None:
        d = Vector(look_at) - Vector(loc)
        cam.rotation_euler = d.to_track_quat('-Z', 'Y').to_euler()
    elif rot is not None:
        cam.rotation_euler = rot
    cam.data.angle = math.radians(lens_fov)
    cam.data.clip_start = 0.01
    return cam


def eye_camera(name, eye_b, yaw=0.0, pitch=0.0, fov=80.0):
    """a camera at a Blender-space point looking forward (+Y), yaw positive to the pilot's left, pitch up"""
    y, p = math.radians(yaw), math.radians(pitch)
    d = Vector((-math.sin(y) * math.cos(p), math.cos(y) * math.cos(p), math.sin(p)))
    return camera(name, eye_b, look_at=Vector(eye_b) + d, lens_fov=fov)


def setup_render(engine='CYCLES', w=1400, h=980, samples=24):
    sc = bpy.context.scene
    sc.render.engine = engine
    sc.render.resolution_x, sc.render.resolution_y = w, h
    sc.render.resolution_percentage = 100
    sc.render.image_settings.file_format = 'PNG'
    sc.view_settings.view_transform = 'Standard'
    if engine == 'CYCLES':
        sc.cycles.samples = samples
        sc.cycles.device = 'CPU'
        sc.cycles.use_denoising = True
        try:
            sc.cycles.denoiser = 'OPENIMAGEDENOISE'
        except Exception:
            pass
        sc.cycles.max_bounces = 4
    elif engine == 'BLENDER_WORKBENCH':
        sh = sc.display.shading
        sh.light = 'STUDIO'
        sh.color_type = 'TEXTURE'
        sh.show_cavity = True
        sh.cavity_type = 'BOTH'
    world = bpy.data.worlds.get('World') or bpy.data.worlds.new('World')
    sc.world = world
    world.use_nodes = True
    bg = world.node_tree.nodes.get('Background')
    bg.inputs['Color'].default_value = (0.55, 0.66, 0.80, 1)
    bg.inputs['Strength'].default_value = 1.0
    return sc


def sun(name='sun', rot=(math.radians(50), math.radians(10), math.radians(30)), energy=3.0):
    l = bpy.data.objects.new(name, bpy.data.lights.new(name, 'SUN'))
    bpy.context.scene.collection.objects.link(l)
    l.rotation_euler = rot
    l.data.energy = energy
    l.data.angle = math.radians(8)
    return l


def render(cam, path):
    sc = bpy.context.scene
    sc.camera = cam
    sc.render.filepath = path
    bpy.ops.render.render(write_still=True)
    return path


def hide(objs, hidden=True):
    for ob in objs:
        ob.hide_render = hidden
        ob.hide_viewport = hidden
