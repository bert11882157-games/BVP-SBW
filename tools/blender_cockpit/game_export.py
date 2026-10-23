"""Blender side of the trip back to the game: writes game_export.json next to a built structure.

  faces        every cockpit triangle in vehicle-local blocks (x left, y up, z forward), with its outward normal and the
               colour of its material; HUD / gunsight glass goes to 'glass' instead (it joins CanopyGlass)
  keep         per model object (= geo bone): the centroids of the faces still there after the cuts, so the game side
               drops exactly the faces the cuts removed
  instruments  each instrument item after the fit: its kind and name, centre, face normal (toward the pilot), up, size
  stations     per crew station: seat index, eye, the stick's foot and whether the stick is a side stick
  hud          the combiner face (centre, normal toward the pilot, up, width, height) per station that has one
"""
import json
from mathutils import Vector
from cockpit_details import TILES


def g(v):
    return [round(v.x, 5), round(v.z, 5), round(-v.y, 5)]


def gn(v):
    v = v.normalized()
    return [round(v.x, 5), round(v.z, 5), round(-v.y, 5)]


def colour_of(o):
    m = o.data.materials[0] if o.data.materials else None
    if m is None or not m.use_nodes:
        return m.name if m else 'none', [128, 128, 128]
    b = next((n for n in m.node_tree.nodes if n.type == 'BSDF_PRINCIPLED'), None)
    c = b.inputs['Base Color'].default_value if b else (0.5, 0.5, 0.5, 1)
    return m.name, [int(round(max(0.0, min(1.0, x)) * 255)) for x in c[:3]]


def face_frame(o):
    """centre, normal and up of an instrument part: the area-weighted centre of its faces, the normal of its largest
    face turned toward +normal majority, up from that face's longest edge direction closest to world up"""
    me = o.data
    if not me.polygons:
        return None
    big = max(me.polygons, key=lambda p: p.area)
    mw = o.matrix_world
    tot = sum(p.area for p in me.polygons) or 1.0
    c = sum((mw @ p.center * p.area for p in me.polygons), Vector()) / tot
    n = (mw.to_3x3() @ big.normal).normalized()
    up = Vector((0, 0, 1))
    up = (up - n * up.dot(n))
    if up.length < 1e-6:
        up = Vector((0, -1, 0)) - n * Vector((0, -1, 0)).dot(n)
    return c, n, up.normalized()


def planar(objs, deg=5.0):
    """dissolve the cuts the fit made in flat areas (planar decimate): no vertex moves, so the clearances hold"""
    import bpy, math
    for o in objs:
        m = o.modifiers.new('planar', 'DECIMATE'); m.decimate_type = 'DISSOLVE'; m.angle_limit = math.radians(deg)
        bpy.context.view_layer.objects.active = o
        bpy.ops.object.modifier_apply(modifier=m.name)


def export(path, cockpit, model, instruments, stations, eye_of, glass_materials=('hudglass',), k=1.0):
    planar(cockpit)
    faces, glass = [], []
    for o in cockpit:
        name, rgb = colour_of(o)
        me = o.data
        me.calc_loop_triangles()
        mw = o.matrix_world
        rot = mw.to_3x3()
        tl = me.attributes.get('tile')
        uvl = me.uv_layers.active
        for t in me.loop_triangles:
            pts = [g(mw @ me.vertices[i].co) for i in t.vertices]
            if name.split('.')[0] in glass_materials:
                glass.append(pts)
                continue
            f = {'p': pts, 'n': gn(rot @ t.normal), 'c': rgb, 'part': o.name}
            tid = tl.data[t.polygon_index].value if tl is not None and tl.domain == 'FACE' else 0
            if tid and uvl is not None and tid < len(TILES):
                f['tile'] = TILES[tid]
                f['uv'] = [[round(uvl.data[li].uv[0], 4), round(uvl.data[li].uv[1], 4)] for li in t.loops]
            faces.append(f)
    keep = {}
    for o in model:
        mw = o.matrix_world
        keep[o.name.split('.')[0]] = [g(mw @ p.center) for p in o.data.polygons]
    inst = []
    by_name = {o.name: o for o in cockpit}
    for it in instruments:
        o = by_name.get(it['object'])
        if o is None:
            continue
        fr = face_frame(o)
        if fr is None:
            continue
        c, n, up = fr
        E = eye_of(it['station'])
        if n.dot(E - c) < 0:                    # the face normal points at the pilot
            n = -n
        d = dict(it)
        d.update(center=g(c), normal=gn(n), up=gn(up))
        inst.append(d)
    hud = []
    for si, st in stations.items():
        o = by_name.get(st.get('hud_object', ''))
        if o is None:
            continue
        c, n, up = face_frame(o)
        E = eye_of(si)
        if n.dot(E - c) < 0:
            n = -n
        hud.append(dict(station=si, center=g(c), normal=gn(n), up=gn(up), width=st['hud_w'], height=st['hud_h']))
    st_out = {str(si): dict(st, eye=g(eye_of(si)), stick_foot=g(st['stick_foot']) if st.get('stick_foot') is not None
                            else None) for si, st in stations.items()}
    json.dump(dict(faces=faces, glass=glass, keep=keep, instruments=inst, stations=st_out, hud=hud, blocks_per_m=k),
              open(path, 'w'))
    return len(faces), len(glass), len(inst)
