"""Blender: the cockpit structure generator for the fleet (the F-16C has its own script, f16c_structure.py, whose
machinery this follows: a traced panel outline fitted to the hull, one formed panel body with cheeks to the walls, a
padded hood glareshield following the windscreen, knee panels and a footwell, the seat and the structure behind it,
then the fit to the hull, the clipping check and the 3 cm glass clearance).

Run: blender --background --python cockpit_build.py -- EXPORTDIR OUTDIR
EXPORTDIR is an export_obj.py folder (model.obj, glass.obj, meta.json). Writes OUTDIR/structure.blend, check.json,
spec.json and the preview renders.
"""
import json, math, os, sys
import bpy, bmesh
from mathutils import Vector, Matrix
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import bl_common as B
import cockpit_specs as CS

args = sys.argv[sys.argv.index('--') + 1:]
d, out = args[0], args[1]
os.makedirs(out, exist_ok=True)
B.reset()
model, glass, meta = B.import_aircraft(d)
sc = B.setup_render()
vid = meta['vid']
k = meta['tub']['s'] if meta.get('tub') else 1.0
R, U, F = Vector((-1, 0, 0)), Vector((0, 0, 1)), Vector((0, -1, 0))
for o in model:
    if 'control_stick' in o.name:
        o.hide_render = True
model = [o for o in model if 'control_stick' not in o.name]
skin = B.bvh_of(model)
gl = B.bvh_of([glass])
cockpit = []


def interior_cut():
    """removes the old cockpit's leftovers inside the cockpit volume: a model face is removed only when it is shut in
    on both of its sides (every ray from just off either side meets the hull or the opaque canopy glass) and it is
    more than 6 cm from the glass (canopy frames stay); a face that can be seen from outside from either side is never
    touched, so the exterior does not change"""
    from mathutils.bvhtree import BVHTree
    occl_v, occl_p = [], []
    dg = bpy.context.evaluated_depsgraph_get()
    for o in model + [glass]:
        me = o.evaluated_get(dg).to_mesh()
        off = len(occl_v)
        occl_v += [o.matrix_world @ v.co for v in me.vertices]
        occl_p += [[off + i for i in p.vertices] for p in me.polygons]
        o.evaluated_get(dg).to_mesh_clear()
    occ = BVHTree.FromPolygons(occl_v, occl_p)
    dirs = [Vector((x, y, z)).normalized() for x in (-1, 0, 1) for y in (-1, 0, 1) for z in (-1, 0, 1) if (x, y, z) != (0, 0, 0)]
    eyes_f = [(e - EYES[0]).dot(F) / k for e in EYES]
    f_lo, f_hi = min(eyes_f) - 0.9, max(eyes_f) + 1.4
    removed = 0
    for o in model:
        bm = bmesh.new(); bm.from_mesh(o.data)
        bm.transform(o.matrix_world)
        doomed = []
        for f_ in bm.faces:
            c = f_.calc_center_median()
            rel = c - EYES[0]
            fr, uu, ff = rel.dot(R) / k, rel.dot(U) / k, rel.dot(F) / k
            if not (f_lo < ff < f_hi and abs(fr) < 0.65 and -1.4 < uu < 0.6):
                continue
            loc_, n_, i_, dg_ = gl.find_nearest(c)
            if loc_ is not None and dg_ < float(os.environ.get('CB_GLASSKEEP', '0.06')) * k:
                continue
            # (1) inside the cavity: the hull (or glass) lies more than 3 cm beyond it to both sides, below and above
            inside = True
            for dv in (R, -R, -U, U):
                h = occ.ray_cast(c + dv * 0.005 * k, dv, 40.0 * k)
                if h[0] is None or h[3] < 0.03 * k:
                    inside = False
                    break
            if not inside:
                continue
            # (2) not seen from outside from any of 26 directions (the glass opaque): seen when the first thing a ray
            # from far out meets on its way in is this face
            seen = False
            for dv in dirs:
                h = occ.ray_cast(c + dv * 40.0 * k, -dv, 41.0 * k)
                if h[0] is not None and (h[0] - c).length < 0.004 * k:
                    seen = True
                    break
            if not seen:
                doomed.append(f_)
        if doomed:
            bmesh.ops.delete(bm, geom=doomed, context='FACES')
            bm.transform(o.matrix_world.inverted())
            bm.to_mesh(o.data)
            removed += len(doomed)
        bm.free()
    return removed


EYES = [B.g2b(e) for e in meta['eyes']]
CUT_REMOVED = interior_cut()
print('INTERIOR_CUT removed', CUT_REMOVED)
bpy.context.view_layer.update()
skin = B.bvh_of(model)
station_of = {}                     # part name -> station index (its eye anchors the fit)
EYES = [B.g2b(e) for e in meta['eyes']]
spec = CS.spec_for(vid, meta.get('era'), meta.get('fleet') or {})
json.dump({k_: v for k_, v in spec.items()}, open(os.path.join(out, 'spec.json'), 'w'), indent=1, default=str)


def hit(bvh, p, d_, far=6.0):
    loc, n, i, dist = bvh.ray_cast(p, d_.normalized(), far)
    return dist if loc is not None else None


def mat(name, rgb, rough=0.7, metal=0.0):
    m = bpy.data.materials.new(name)
    m.use_nodes = True
    b = next(n for n in m.node_tree.nodes if n.type == 'BSDF_PRINCIPLED')
    b.inputs['Base Color'].default_value = (*[c / 255 for c in rgb], 1)
    b.inputs['Roughness'].default_value = rough
    b.inputs['Metallic'].default_value = metal
    return m


wwii = spec['outline'] == 'wwii' or meta.get('era') == 'WWII_STEAM'
# interior colours by the airframe's palette: (panel, console, tub, tub dark)
PALETTE = {
    'soviet': ((70, 150, 150), (64, 138, 140), (82, 160, 160), (66, 134, 136)),          # the turquoise cockpits
    'soviet_grey': ((34, 35, 37), (40, 42, 44), (104, 112, 104), (88, 96, 90)),          # early jets: black panel
    'us_ww2': ((30, 30, 30), (36, 36, 36), (96, 110, 62), (82, 94, 54)),                # interior green
    'rlm66': ((48, 51, 53), (52, 55, 57), (60, 63, 65), (52, 55, 57)),                   # RLM 66 schwarzgrau
    'ussr_ww2': ((40, 40, 42), (46, 46, 48), (120, 124, 120), (100, 104, 100)),
    'raf': ((26, 26, 26), (32, 32, 32), (100, 110, 95), (86, 94, 82)),                  # RAF grey-green
}
_pal = PALETTE.get((meta.get('fleet') or {}).get('palette'), ((56, 58, 61), (60, 62, 65), (112, 116, 116), (88, 92, 92)))
M = dict(panel=mat('panel', _pal[0]), coaming=mat('coaming', (22, 22, 23), 0.8),
         tub=mat('tub', _pal[2]), tub_d=mat('tub_d', _pal[3]),
         floor=mat('floor', (28, 28, 29)), console=mat('console', _pal[1]),
         seat=mat('seat_frame', (128, 134, 128), 0.5), cushion=mat('cushion', (150, 156, 132), 0.9),
         headbox=mat('headbox', (108, 116, 104)), black=mat('black', (16, 16, 17), 0.6),
         metal=mat('metal', (140, 144, 146), 0.35, 0.8), yellow=mat('yellow', (230, 190, 30)),
         grip=mat('grip', (26, 26, 27), 0.6), hud=mat('hud', (40, 42, 44), 0.5),
         hudglass=mat('hudglass', (150, 190, 170), 0.05), loop_black=mat('loop_black', (20, 20, 20), 0.6),
         chute=mat('chute', (96, 104, 92), 0.8), armour=mat('armour', (70, 74, 70), 0.5, 0.4),
         harness=mat('harness', (84, 88, 70), 0.9))
_hb = next(n for n in M['hudglass'].node_tree.nodes if n.type == 'BSDF_PRINCIPLED')
_hb.inputs['Alpha'].default_value = 0.22
for _attr, _val in (('surface_render_method', 'BLENDED'), ('blend_method', 'BLEND')):
    try:
        setattr(M['hudglass'], _attr, _val)
    except Exception:
        pass

CUR_STATION = [0]
# the simple build (default): rigid parts only (a flat panel with its instruments, a slab hood, block consoles, the
# sight, the controls and the seat); no tub linings, and the fit never bends a part: one that clips is shrunk whole or
# dropped. CB_SIMPLE=0 builds the old lined tub with the per-vertex fit.
SIMPLE = os.environ.get('CB_SIMPLE', '1') == '1'
PANEL_PARTS = set()                 # the panel board and everything on it: these shrink together, about the board
PIVOT = {}                          # part name -> the point the rigid fit shrinks it toward, where not the default
INSTR, STATIONS = [], {}
FRONT_INDEX = [0]
ANCHOR_U = {}


def obj(name, bm, material, bevel=0.0, solid=0.0):
    name = f's{CUR_STATION[0]}_{name}' if len(EYES) > 1 else name
    me = bpy.data.meshes.new(name)
    bm.to_mesh(me); bm.free()
    o = bpy.data.objects.new(name, me)
    sc.collection.objects.link(o)
    o.data.materials.append(material)
    if solid:
        s = o.modifiers.new('solid', 'SOLIDIFY'); s.thickness = solid * k; s.offset = -1
    if bevel:
        b = o.modifiers.new('bevel', 'BEVEL'); b.width = bevel * k; b.segments = 1; b.limit_method = 'ANGLE'
    cockpit.append(o)
    station_of[o.name] = CUR_STATION[0]
    return o


def box(name, centre, axes, half, material, bevel=0.0):
    bm = bmesh.new()
    c = Vector(centre)
    A = [a.normalized() * h * k for a, h in zip(axes, half)]
    vs = [bm.verts.new(c + A[0] * sx + A[1] * sy + A[2] * sz) for sx in (-1, 1) for sy in (-1, 1) for sz in (-1, 1)]
    for f in ((0, 1, 3, 2), (4, 6, 7, 5), (0, 4, 5, 1), (2, 3, 7, 6), (0, 2, 6, 4), (1, 5, 7, 3)):
        bm.faces.new([vs[i] for i in f])
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
    return obj(name, bm, material, bevel=bevel)


def sheet(name, rows, material, solid=0.02, bevel=0.003):
    bm = bmesh.new()
    V = [[bm.verts.new(p) for p in row] for row in rows]
    for i in range(len(V) - 1):
        for j in range(len(V[i]) - 1):
            q = [V[i][j], V[i][j + 1], V[i + 1][j + 1], V[i + 1][j]]
            if len({v.co.to_tuple(5) for v in q}) >= 3:
                try:
                    bm.faces.new(q if len({v.co.to_tuple(5) for v in q}) == 4 else list(dict.fromkeys(q)))
                except ValueError:
                    pass
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
    return obj(name, bm, material, bevel=bevel, solid=solid)


def cyl(name, c, axis, radius, half_len, material, segs=20):
    bm = bmesh.new()
    bmesh.ops.create_cone(bm, cap_ends=True, segments=segs, radius1=radius * k, radius2=radius * k,
                          depth=2 * half_len * k)
    rot = Vector((0, 0, 1)).rotation_difference(axis.normalized()).to_matrix().to_4x4()
    bmesh.ops.transform(bm, matrix=Matrix.Translation(c) @ rot, verts=bm.verts)
    return obj(name, bm, material)


def ring(c, a, b, ra, rb, n=14, sq=2.6):
    pts = []
    for i in range(n):
        t = 2 * math.pi * i / n
        ct, st = math.cos(t), math.sin(t)
        pts.append(c + a * math.copysign(abs(ct) ** (2 / sq), ct) * ra + b * math.copysign(abs(st) ** (2 / sq), st) * rb)
    return pts


def loft(name, rings, material, bevel=0.0):
    bm = bmesh.new()
    V = [[bm.verts.new(p) for p in r] for r in rings]
    n = len(rings[0])
    for i in range(len(V) - 1):
        for j in range(n):
            bm.faces.new([V[i][j], V[i][(j + 1) % n], V[i + 1][(j + 1) % n], V[i + 1][j]])
    bm.faces.new(V[0][::-1]); bm.faces.new(V[-1])
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
    return obj(name, bm, material, bevel=bevel)


def convex_hull(pts):
    """the convex hull of 2-D points (monotone chain), counter-clockwise"""
    pts = sorted(set(map(tuple, pts)))
    if len(pts) < 3:
        return pts

    def half(seq):
        h = []
        for p in seq:
            while len(h) >= 2 and (h[-1][0] - h[-2][0]) * (p[1] - h[-2][1]) - (h[-1][1] - h[-2][1]) * (p[0] - h[-2][0]) <= 0:
                h.pop()
            h.append(p)
        return h[:-1]
    return half(pts) + half(pts[::-1])


tub = meta.get('tub') or {}
REPORT = dict(vid=vid, era=meta.get('era'), stations=[])


def build_station(si, E, front_eye=None):
    """one crew station anchored on eye E; front_eye is the station ahead (tandem), whose seat bounds this panel"""
    CUR_STATION[0] = si
    rep = dict(station=si)

    def P(r, u, f):
        return E + (R * r + U * u + F * f) * k

    def wall(u, f, side, cap=0.50):
        dist = hit(skin, P(0, u, f), R * side)
        return min(cap, dist / k) if dist is not None else cap

    def glass_over(r, f, u=-0.3):
        dist = hit(gl, P(r, u, f), U)
        return u + dist / k if dist is not None else None

    def ahead(u, r=0.0, f0=0.1):
        dist = hit(skin, P(r, u, f0), F)
        return f0 + dist / k if dist is not None else 3.0

    def reach_pt(pt, side, clear=0.04, cap=0.50):
        lo, hi = 0.0, cap
        for _ in range(14):
            m = (lo + hi) / 2
            p = pt(side * m)
            anchor = E + U * (ANCHOR_U[si] * k) + F * (p - E).dot(F)
            dv = p - anchor
            L_ = dv.length
            blocked = any(h is not None for h in (hit(skin, anchor, dv, far=L_ + clear * k),
                                                   hit(gl, anchor, dv, far=L_ + clear * k)))
            lo, hi = (lo, m) if blocked else (m, hi)
        return lo

    eye_y = meta['eyes'][FRONT_INDEX[0]][1]          # the tub heights are the front cockpit's; a rear seat keeps them
    #                                                  relative to its own eye
    sill_u = (tub['sill'] - eye_y) / k if tub.get('sill') else -0.42
    floor_u = max(-1.25, min(-0.80, (tub['floor_y'] - eye_y) / k)) if tub.get('floor_y') else -1.05
    # the floor as the hull has it: straight down from just ahead of the seat (the tub's figure is a guess)
    hd = hit(skin, P(0, -0.30, 0.25), -U)
    if hd is not None:
        floor_u = max(-1.25, min(floor_u, -0.30 - hd / k + 0.02))
        floor_u = max(floor_u, -0.30 - hd / k + 0.02)
    floor_u = min(floor_u, -0.55)
    ANCHOR_U[si] = max(-0.55, min(-0.25, floor_u + 0.40))      # the fit's anchor sits well inside the tub
    con_u = max(floor_u + 0.30, min(sill_u - 0.18, (tub['console_y'] - eye_y) / k)) if tub.get('console_y') else -0.70
    rear = front_eye is not None
    gunner = rear and vid in ('ju_87_b2', 'il_10')
    # ------------------------------------------------------------------ the seat (first, the panel works back from it)
    rec = math.radians(spec['recline'])
    s_up = (U * math.cos(rec) - F * math.sin(rec)).normalized()
    s_fw = s_up.cross(R).normalized()
    if s_fw.dot(F) < 0:
        s_fw = -s_fw
    head = P(0, -0.05, -0.15)
    hip = head - s_up * 0.74 * k
    hip_u = (hip - E).dot(U) / k
    if hip_u < floor_u + 0.28:                # a shallow tub: the seat rides higher
        hip = hip + U * (floor_u + 0.28 - hip_u) * k
    if gunner:                                # a rear gunner faces aft: mirror the seat about the eye's station
        build_gunner_seat(si, E, P, floor_u)
        rep['gunner'] = True
        REPORT['stations'].append(rep)
        return
    # ------------------------------------------------------------------ the panel
    ws_f = ahead(-0.30) - 0.03
    lean = math.radians(spec['lean'])
    # the panel's station and top: stepping in from 0.80 m, the top goes under the hull's ceiling there (the nose's
    # upper skin, or what the hull keeps under the windscreen) and the first station whose top and sides the pilot
    # can see from the eye is taken
    def ceiling(f):
        h = hit(skin, P(0, -0.40, f), U)           # from panel height: the old panel's own underside is no ceiling
        return -0.40 + h / k if h is not None else 0.5

    def seen(p):
        dv = p - E
        h = hit(skin, E, dv, far=dv.length)
        return h is None or h > dv.length - 0.02 * k

    choice = None
    # first every station at the natural height (under the glass and the hull's ceiling), stepping in toward the
    # pilot; only when none fits is the top lowered (a panel stands closer before it ever goes down)
    for lower in (False, True):
        for i in range(14):
            F_c = (0.80 if not SIMPLE else 0.72) - 0.035 * i
            g0 = glass_over(0, F_c, -0.4)
            top = min(-0.20, (g0 - 0.24) if g0 is not None else -0.20, ceiling(F_c) - 0.05)
            top = max(top, -0.55)
            room = lambda tp: min(reach_pt(lambda x, tp=tp, fc=F_c: P(x, tp, fc), sd, clear=0.035) for sd in (1, -1))
            if lower:
                while top > max(-0.55, sill_u - 0.12) and room(top) < 0.18:
                    top -= 0.02
            elif room(top) < 0.12:                 # a narrow top is fine: the panel's outline scales to it
                if os.environ.get('CB_DEBUG'):
                    print('DBG F', round(F_c, 3), 'top', round(top, 3), 'no room', round(room(top), 3))
                continue
            mid = top - 0.17
            # free space ahead: at the top, either side of the centre line (a sight standing on the centre of the
            # coaming does not push the panel down), and along the centre lower down
            free = min([ahead(top - 0.02, r=sd * 0.15) for sd in (1, -1)] + [ahead(u) for u in (mid, mid - 0.10)])
            if os.environ.get('CB_DEBUG'):
                print('DBG free F', round(F_c, 3), 'top', round(top, 3), [round(ahead(top - 0.02, r=sd * 0.15), 3) for sd in (1, -1)], [round(ahead(u), 3) for u in (mid, mid - 0.10)])
            if free < F_c + 0.06:
                continue
            pts = [P(0, top, F_c), P(0.18, top - 0.08, F_c), P(-0.18, top - 0.08, F_c), P(0, mid, F_c)]
            if os.environ.get('CB_DEBUG'):
                print('DBG F', round(F_c, 3), 'top', round(top, 3), 'room', round(room(top), 3), 'free', round(free, 3), 'seen', [seen(q) for q in pts])
            if all(seen(q) for q in pts):
                choice = (F_c, top, free)
                break
        if choice:
            break
    if choice is None:
        choice = (0.55, max(sill_u + 0.02, -0.30), ahead(-0.40))
    F_PANEL, U_TOP, free = choice
    U_MID = U_TOP - 0.17
    F_PANEL = F_PANEL - abs(U_TOP - U_MID) * math.tan(lean) * 0.0
    if rear:
        fs = (front_eye - E).dot(F) / k          # the front seat's back is about 0.35 m behind the front eye
        F_PANEL = min(F_PANEL, fs - 0.55)
    pn = (F * -math.cos(lean) + U * math.sin(lean)).normalized()
    upl = (U * math.cos(lean) + F * math.sin(lean)).normalized()

    def panel_pt(r, u, lift=0.0):
        f = F_PANEL + (u - U_MID) * math.tan(lean)
        return P(r, u, f) + pn * lift * k

    def reach(u, side, lift=0.0, clear=0.04):
        return reach_pt(lambda x: panel_pt(x, u, lift), side, clear)

    def facing(c, half=True):
        n = (E - c).normalized()
        if half:
            n = (n + pn).normalized()
        ar = U.cross(n).normalized()
        if ar.dot(R) < 0:
            ar = -ar
        au = n.cross(ar).normalized()
        if au.dot(U) < 0:
            au = -au
        return ar, au, n

    TRACE = spec['rear_trace'] if rear and spec.get('rear_trace') else spec['trace']
    if SIMPLE:                                # the outline's convex hull: a clean panel edge, no notches or steps
        TRACE = convex_hull(TRACE)
    foot_floor = floor_u + 0.30

    def trace_fits(s):
        for r, du in TRACE:
            if U_TOP + du * s < foot_floor:
                return False
            if abs(r) < 0.05:
                continue
            if abs(r) * s > reach(U_TOP + du * s, 1 if r > 0 else -1, clear=0.035):
                return False
        return True

    S = next((s / 100 for s in range(110, 40, -1) if trace_fits(s / 100)), 0.4)
    if os.environ.get('CB_DEBUG'):
        for r, du in TRACE:
            u = U_TOP + du * 0.6
            print('DBG trace', r, du, 'u', round(u, 3), 'reach', round(reach(u, 1 if r >= 0 else -1, clear=0.035), 3),
                  'foot_floor', round(foot_floor, 3), 'anchor', ANCHOR_U[si])
    if rear:
        ws_f = min(ws_f, F_PANEL + 0.16)
    rep.update(F_PANEL=round(F_PANEL, 3), U_TOP=round(U_TOP, 3), scale=S, sill=round(sill_u, 3),
               floor=round(floor_u, 3), console=round(con_u, 3))
    bm = bmesh.new()
    vs = [bm.verts.new(panel_pt(r * S, U_TOP + du * S)) for r, du in TRACE]
    bm.faces.new(vs)
    bmesh.ops.triangulate(bm, faces=bm.faces[:])
    for f_ in bm.faces:
        f_.normal_update()
        if f_.normal.dot(pn) < 0:
            f_.normal_flip()
    obj('panel_board', bm, M['panel'], solid=0.025, bevel=0.004)
    panel_group = [len(cockpit) - 1]
    if not SIMPLE:
        bm = bmesh.new()
        fv = [bm.verts.new(panel_pt(r * S, U_TOP + du * S, -0.024)) for r, du in TRACE]
        depth = max(0.06, min(0.30, free - F_PANEL - 0.06))   # into the nose, short of what the hull keeps there
        bv = [bm.verts.new(panel_pt(r * S, U_TOP + du * S, -depth)) for r, du in TRACE]
        bm.faces.new(fv); bm.faces.new(bv[::-1])
        for i in range(len(TRACE)):
            j = (i + 1) % len(TRACE)
            bm.faces.new([fv[j], fv[i], bv[i], bv[j]])
        bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
        bmesh.ops.triangulate(bm, faces=[f_ for f_ in bm.faces if len(f_.verts) > 4])
        obj('panel_body', bm, M['coaming'])
    # side cheeks from the traced sides to the walls
    side_pts = sorted([(r, du) for r, du in TRACE if r > 0.2], key=lambda q: -q[1])
    top_side = max(du for r, du in side_pts) if side_pts else -0.05
    foot_side = min(du for r, du in side_pts) if side_pts else -0.30
    r_side = max(r for r, du in side_pts) if side_pts else 0.30
    for sg in ((-1, 1) if not SIMPLE else ()):
        rows = []
        for t in (0.0, 0.25, 0.5, 0.75, 1.0):
            du = top_side + (foot_side - top_side) * t
            u = U_TOP + du * S
            r_e = r_side * S
            r_w = max(r_e + 0.004, reach(u, sg, clear=0.03))
            rows.append([panel_pt(sg * r_e, u, -0.002), panel_pt(sg * r_w, u, -0.012)])
        sheet(f'panel_cheek_{sg}', rows if sg > 0 else [r_[::-1] for r_ in rows], M['panel'], solid=0.012)
    # ------------------------------------------------------------------ instruments on the panel
    panel_plates, overlays = [], []
    pref = f's{si}_' if len(EYES) > 1 else ''
    STATIONS[si] = dict(seat=si, stick=spec['stick'])
    for it in (spec['rear_items'] if rear and spec.get('rear_items') else spec['items']):
        kind, name = it[0], it[1]
        face_obj = {'mfd': '_floor', 'display': '_floor', 'gauge': '_face', 'scope': '_face', 'ufcp': '_face'}.get(kind, '')
        size = list(it[4:])
        INSTR.append(dict(station=si, kind=kind, name=name, object=pref + name + face_obj, size_m=size, scale=S))
        r, du = it[2] * S, it[3] * S
        if kind == 'mfd':
            size = it[4] * min(1.0, S)
            c = panel_pt(r, U_TOP + du, 0.040)
            ax = facing(c)
            framed(name, c, ax, size, size, 0.028, size * 0.63, size * 0.63)
            ar, au, an = ax
            off = size * 0.41
            pitch = size * 0.123
            for sd, (dx, dy) in enumerate(((0, 1), (0, -1), (-1, 0), (1, 0))):
                for i in range(5):
                    o_ = (i - 2) * pitch
                    q = c + (ar * (dx * off + (o_ if dy else 0)) + au * (dy * off + (o_ if dx else 0))) * k + an * 0.003 * k
                    box(f'{name}_osb_{sd}_{i}', q, ax, (size * 0.04 if dy else size * 0.031, size * 0.031 if dy else size * 0.04,
                                                       0.003), M['grip'], bevel=0.0015)
        elif kind == 'display':
            w, h = it[4] * min(1.0, S), it[5] * min(1.0, S)
            c = panel_pt(r, U_TOP + du, 0.030)
            ax_ = facing(c)
            framed(name, c, ax_, w, h, 0.024, w * 0.86, h * 0.80)
            if not any(s_ in name.lower() for s_ in ('vsd', 'hdd', 'ci_radar', 'wide', 'lower', 'tv_monitor', 'mpd', 'mpcd', 'mfd', 'radar')):      # a text window: painted readout
                overlays.append((c - ax_[2] * 0.0135 * k, ax_, w * 0.86, h * 0.80, 'text_window'))
        elif kind in ('gauge', 'scope'):
            rad = it[4] * min(1.0, S)
            c = panel_pt(r, U_TOP + du, 0.004)
            ax = facing(c)
            cyl(f'{name}_rim', c, ax[2], rad, 0.005, M['grip'])
            cyl(f'{name}_face', c + ax[2] * 0.0055 * k, ax[2], rad * 0.84, 0.0008, M['black'])
            if kind == 'scope':                   # the radar scope's hood
                for sg in (-1, 1):
                    box(f'{name}_hood_{sg}', c + ax[0] * sg * rad * 1.05 * k + ax[2] * 0.03 * k, ax,
                        (0.004, rad * 0.95, 0.03), M['coaming'], bevel=0.002)
                box(f'{name}_hood_top', c + ax[1] * rad * 1.02 * k + ax[2] * 0.03 * k, ax, (rad * 1.08, 0.004, 0.03),
                    M['coaming'], bevel=0.002)
        elif kind == 'plate':
            w, h = it[4] * S, it[5] * S
            c = panel_pt(r, U_TOP + du, 0.002)
            box(name, c, facing(c), (w / 2, h / 2, 0.0025), M['console'], bevel=0.001)
            panel_plates.append((c, facing(c), w, h))
        elif kind == 'ufcp':
            w, h = it[4] * min(1.0, S), it[5] * min(1.0, S)
            c = panel_pt(r, U_TOP + du, 0.045)
            box(f'{name}_housing', c - pn * 0.024 * k, (R, upl, pn), (w / 2, h / 2, 0.024), M['panel'], bevel=0.006)
            box(f'{name}_face', c + pn * 0.002 * k, (R, upl, pn), (w / 2 - 0.006, h / 2 - 0.006, 0.003), M['console'],
                bevel=0.002)
            overlays.append((c + pn * 0.0053 * k, (R, upl, pn), w - 0.014, h - 0.014, 'keypad'))
    PANEL_PARTS.update(o.name for o in cockpit[panel_group[0]:])

    # ------------------------------------------------------------------ the centre pedestal down to the floor
    bottom = min(du for r, du in TRACE)
    if spec.get('column', True):
        ped_top = panel_pt(0, U_TOP + bottom * S, 0.02)
        ped_ax = (U * math.cos(math.radians(50)) + F * math.sin(math.radians(50))).normalized()
        ped_len = max(0.05, ((ped_top - E).dot(U) / k - floor_u - 0.02) / ped_ax.dot(U))
        cw = min(0.10, 0.09 * S / 0.9)
        sheet('pedestal', [[ped_top + R * r * k for r in (-cw, cw)],
                           [ped_top - ped_ax * ped_len * 0.5 * k + R * r * k for r in (-cw * 0.92, cw * 0.92)],
                           [ped_top - ped_ax * ped_len * k + R * r * k for r in (-cw * 0.85, cw * 0.85)]],
              M['panel'], solid=0.05)

    # ------------------------------------------------------------------ glareshield: a padded hood over the panel
    top_path = [(r, du) for r, du in TRACE if du >= top_side - 1e-6]
    top_path = sorted(top_path, key=lambda q: q[0])
    if SIMPLE:
        # one flat hood: a slab over the panel's top edge, from 5 cm proud of the panel forward toward the windscreen
        hood_w = max(abs(r) for r, du in top_path) * S + 0.01
        f0 = (panel_pt(0, U_TOP) - E).dot(F) / k
        lip_f = f0 - 0.05
        f1 = max(f0 + 0.06, min(f0 + 0.24, ws_f - 0.08))
        box('glareshield', P(0, U_TOP + 0.016, (lip_f + f1) / 2), (R, U, F), (hood_w, 0.016, (f1 - lip_f) / 2),
            M['coaming'], bevel=0.004)
        PIVOT[cockpit[-1].name] = P(0, U_TOP + 0.016, lip_f)       # the fit shrinks it toward its aft edge
    lip, lip_du = [], []
    for r, du in (top_path if not SIMPLE else ()):
        lip.append(panel_pt(r * S, U_TOP + du * S, 0.05) + U * 0.015 * k)
        lip_du.append(du * S)
    if not SIMPLE:
        rows = [lip]
        hood_w = max(abs(r) for r, du in top_path) * S
        for t in (0.35, 0.7, 1.0):
            row = []
            for p in lip:
                r = (p - E).dot(R) / k
                u0 = (p - E).dot(U) / k
                f0 = (p - E).dot(F) / k
                f = f0 + max(0.03, ws_f - 0.06 - f0) * t
                u = u0 + 0.028 * math.sin(math.pi * t) * max(0.0, 1 - (r / max(0.1, hood_w)) ** 2) - 0.02 * t
                g = glass_over(r, f, u - 0.3)
                if g is not None:
                    u = min(u * (1 - t) + (g - 0.045) * t, g - 0.045)
                row.append([r, u, f])
            for sgn in (-1, 1):
                half = [q for q in row if q[0] * sgn > 1e-6]
                if half:
                    r_o, u_o, f_o = max(half, key=lambda q: abs(q[0]))
                    w = reach_pt(lambda x, u_o=u_o, f_o=f_o: P(x, u_o, f_o), sgn, clear=0.035)
                    s_ = min(1.0, w / abs(r_o))
                    for q in half:
                        q[0] *= s_
            rows.append([P(*q) for q in row])
        sheet('glareshield', rows, M['coaming'], solid=0.028 if not wwii else 0.015, bevel=0.010)
        sheet('glareshield_lip', [lip, [panel_pt((p - E).dot(R) / k, U_TOP + du_) for p, du_ in zip(lip, lip_du)]],
              M['coaming'], solid=0.03 if not wwii else 0.015, bevel=0.012)
        lip_f = (panel_pt(0, U_TOP, 0.05) - E).dot(F) / k

    # ------------------------------------------------------------------ the sight
    sight = spec['sight'] if not rear else None
    if sight == 'hud':
        hud_base = P(0, U_TOP + 0.050, lip_f + 0.060)
        box('hud_body', hud_base, (R, U, F), (0.080, 0.045, 0.065), M['hud'], bevel=0.012)
        g_up = (U * math.cos(math.radians(20)) - F * math.sin(math.radians(20))).normalized()
        g_bot = P(0, U_TOP + 0.095, lip_f + 0.005)

        def clear_h(h):
            for r_ in (-0.08, 0.0, 0.08):
                top = g_bot + g_up * h * k + R * r_ * k
                hd = min([x for x in (hit(gl, top, U), hit(skin, top, U)) if x is not None], default=None)
                if hd is None or hd < 0.03 * k:
                    return False
            return True
        g_h = next((h / 100 for h in range(18, 5, -1) if clear_h(h / 100)), 0.0)
        if g_h >= 0.06:
            g_n = R.cross(g_up).normalized()
            for sg in (-1, 1):
                box(f'hud_frame_{sg}', g_bot + R * sg * 0.070 * k + g_up * g_h / 2 * k, (R, g_up, g_n),
                    (0.008, g_h / 2, 0.016), M['hud'], bevel=0.003)
            box('hud_frame_top', g_bot + g_up * (g_h - 0.006) * k, (R, g_up, g_n), (0.078, 0.006, 0.010), M['hud'],
                bevel=0.002)
            box('hud_combiner', g_bot + g_up * g_h / 2 * k, (R, g_up, g_n), (0.062, g_h / 2 - 0.006, 0.0015),
                M['hudglass'], bevel=0.0)
            STATIONS[si].update(hud_object=pref + 'hud_combiner', hud_w=0.124, hud_h=g_h - 0.012)
        rep['combiner'] = g_h
    elif sight in ('gunsight', 'reflector'):
        # the gyro gunsight (jets) or the reflector sight (WWII): a body on the coaming and a tilted glass plate
        gb = P(0, U_TOP + 0.045, lip_f + 0.04)
        box('gunsight_body', gb, (R, U, F), (0.045, 0.040, 0.060 if sight == 'gunsight' else 0.045), M['hud'],
            bevel=0.008)
        g_up = (U * math.cos(math.radians(35)) - F * math.sin(math.radians(35))).normalized()
        gp = gb + U * 0.040 * k + g_up * 0.040 * k
        hd = hit(gl, gp, U)
        if hd is None or hd > 0.06 * k:
            box('gunsight_glass', gp, (R, g_up, R.cross(g_up).normalized()), (0.040, 0.040, 0.0015), M['hudglass'],
                bevel=0.0)
        if sight == 'gunsight':
            cyl('gunsight_pad', gb - F * 0.065 * k + U * 0.01 * k, F, 0.025, 0.012, M['black'])

    # ------------------------------------------------------------------ tub: consoles, walls, sills, floor
    st_back = -0.42
    st_front = F_PANEL - 0.22
    CON_R0 = max(0.13, min(0.20, 0.5 * wall(con_u + 0.05, 0.2, 1)))
    st = [st_back + (st_front - st_back) * i / 8 for i in range(9)]
    if SIMPLE:
        # the consoles as three plain blocks a side, each as wide as the hull allows along its length
        bot = max(floor_u + 0.02, con_u - 0.30)
        for sg in (-1, 1):
            for j in range(3):
                fa, fb = st[j * 3], st[j * 3 + 3] if j < 2 else st[8]
                outer = min(0.40, min(wall(u_, f_, sg) for u_ in (con_u + 0.02, bot + 0.02)
                                      for f_ in (fa, (fa + fb) / 2, fb)) - 0.025)
                if outer < CON_R0 + 0.04:
                    continue
                box(f'console_{sg}_{j}', P(sg * (CON_R0 + outer) / 2, (con_u + bot) / 2, (fa + fb) / 2), (R, U, F),
                    ((outer - CON_R0) / 2, (con_u - bot) / 2, (fb - fa) / 2 - 0.004), M['console'], bevel=0.003)
    for sg in ((-1, 1) if not SIMPLE else ()):
        outer = [max(CON_R0 + 0.02, min(wall(con_u + 0.02, f, sg) - 0.012, 0.42)) for f in st]
        sheet(f'console_top_{sg}', [[P(sg * CON_R0, con_u, f) for f in st], [P(sg * w, con_u, f) for w, f in zip(outer, st)]],
              M['console'], solid=0.012)
        sheet(f'console_side_{sg}', [[P(sg * CON_R0, con_u, f) for f in st], [P(sg * CON_R0, floor_u, f) for f in st]],
              M['tub_d'], solid=0.01)
        sill = [max(w0 + 0.01, min(wall(sill_u, f, sg) - 0.01, 0.46)) for w0, f in zip(outer, st)]
        sheet(f'wall_{sg}', [[P(sg * w, con_u, f) for w, f in zip(outer, st)], [P(sg * w, sill_u, f) for w, f in zip(sill, st)]],
              M['tub'], solid=0.01)
        box(f'sill_{sg}', P(sg * (min(sill) - 0.02), sill_u + 0.01, (st_back + st_front) / 2), (R, U, F),
            (0.035, 0.012, (st_front - st_back) / 2), M['tub'])
    F_FW = min(F_PANEL + 0.28, ahead(floor_u + 0.15) - 0.04)
    fw = max(0.12, min(0.22, wall(floor_u + 0.05, 0.3, 1) - 0.02))
    if not SIMPLE:
        sheet('floor', [[P(r, floor_u, f) for r in (-fw, fw)] for f in (st_back, F_FW)], M['floor'], solid=0.01)

    def span(u, f, side, cap=0.45, clear=0.03):
        return min(cap, reach_pt(lambda x: P(x, u, f), side, clear=clear, cap=cap))

    # footwell and knee panels
    foot_u = U_TOP + bottom * S
    side_foot_u = U_TOP + foot_side * S
    if not SIMPLE:
        rows = []
        for t in (0.0, 0.25, 0.5, 0.75, 1.0):
            u = floor_u + (side_foot_u - floor_u) * t
            w = min(span(u, F_FW, 1, cap=0.40), span(u, F_FW, -1, cap=0.40))
            rows.append([P(x * w, u, F_FW) for x in (-1, -0.5, 0, 0.5, 1)])
        sheet('footwell_front', rows, M['tub_d'], solid=0.01)
        for sg in (-1, 1):
            rows = []
            for f in (st_front, (st_front + F_FW) / 2, F_FW):
                rows.append([P(sg * span(floor_u + 0.02, f, sg, cap=0.40), floor_u + 0.02, f),
                             P(sg * span(con_u, f, sg, cap=0.40), con_u, f)])
            sheet(f'footwell_side_{sg}', rows if sg > 0 else [r_[::-1] for r_ in rows], M['tub_d'], solid=0.01)
            r0 = 0.10 * S
            r1 = max(r0 + 0.05, min(r_side * S, span(side_foot_u, F_PANEL, sg, cap=0.40)))
            top = [panel_pt(sg * x, side_foot_u, -0.02) for x in (r0, (r0 + r1) / 2, r1)]
            bot_u = max(floor_u + 0.25, side_foot_u - 0.20)
            f_kb = min(F_FW, F_PANEL + 0.12)
            bot = [P(sg * x, bot_u, f_kb) for x in (r0, (r0 + r1) / 2, min(r1, span(bot_u, f_kb, sg, cap=0.40)))]
            sheet(f'knee_panel_{sg}', [top, bot] if sg > 0 else [top[::-1], bot[::-1]], M['coaming'], solid=0.012)

        # ------------------------------------------------------------------ behind the seat: bulkhead and deck
        F_BH = (head - E).dot(F) / k - 0.16
        U_DECK = max(sill_u + 0.06, -0.30)
        rows = []
        for t in (0.0, 0.2, 0.4, 0.6, 0.8, 1.0):
            u = floor_u + (U_DECK - floor_u) * t
            w = min(span(u, F_BH, 1), span(u, F_BH, -1))
            rows.append([P(x * w, u, F_BH) for x in (-1, -0.5, 0, 0.5, 1)])
        sheet('rear_bulkhead', rows, M['tub'], solid=0.01)
        rows = []
        for f in (F_BH, F_BH - 0.12, F_BH - 0.24, F_BH - 0.36, F_BH - 0.48):
            w = min(span(U_DECK, f, 1), span(U_DECK, f, -1))
            rows.append([P(x * w, U_DECK, f) for x in (-1, -0.6, -0.2, 0.2, 0.6, 1)])
        sheet('aft_deck', rows[::-1], M['tub_d'], solid=0.01)

    # ------------------------------------------------------------------ controls and seat
    STATIONS[si]['stick_foot'] = build_controls(si, E, P, hip, con_u, floor_u, F_FW, CON_R0)
    import cockpit_details
    cockpit_details.add(dict(P=P, R=R, U=U, F=F, k=k, M=M, E=E, obj=obj, seed=f'{vid}{si}', era=meta.get('era'),
                             wwii=wwii, con_u=con_u, CON_R0=CON_R0, st_back=st_back, st_front=st_front, hip=hip,
                             s_up=s_up, s_fw=s_fw, stick=spec['stick'], wall=wall, sill_u=sill_u, floor_u=floor_u,
                             panel_plates=panel_plates, overlays=overlays, pn=pn,
                             lip_pt=(lambda x: panel_pt(x * S, U_TOP - 0.02, 0.072)) if not rear else None,
                             pedal_pos=lambda sg: P(sg * 0.10, floor_u + 0.20, F_FW - 0.12)))
    build_seat(si, E, P, hip, s_up, s_fw)
    REPORT['stations'].append(rep)


def framed(name, c, axes, w, h, depth, open_w, open_h, recess=0.012):
    ar, au, an = axes
    t_x, t_y = (w - open_w) / 2, (h - open_h) / 2
    for nm, cx, cy, hx, hy in (('t', 0, (h - t_y) / 2, w / 2, t_y / 2), ('b', 0, -(h - t_y) / 2, w / 2, t_y / 2),
                               ('l', -(w - t_x) / 2, 0, t_x / 2, open_h / 2), ('r', (w - t_x) / 2, 0, t_x / 2, open_h / 2)):
        box(f'{name}_{nm}', c + ar * cx * k + au * cy * k - an * depth / 2 * k, axes, (hx, hy, depth / 2), M['panel'],
            bevel=0.002)
    box(f'{name}_floor', c - an * (recess + 0.002) * k, axes, (open_w / 2, open_h / 2, 0.002), M['black'], bevel=0.0)


def build_controls(si, E, P, hip, con_u, floor_u, F_FW, con_r=0.22):
    hip_f = (hip - E).dot(F) / k
    if spec['stick'] == 'side':
        base = P(0.30, con_u, hip_f + 0.42)
        cyl('stick_boot', base + U * 0.012 * k, U, 0.030, 0.012, M['grip'])
        s_ax = (U * math.cos(math.radians(13)) + F * math.sin(math.radians(13))).normalized()
        s_fw = s_ax.cross(R).normalized()
        if s_fw.dot(F) < 0:
            s_fw = -s_fw
        rings = [ring(base + U * 0.03 * k + s_ax * t * k + s_fw * df * k, R, s_fw, rr * k, rf * k, n=14)
                 for t, rr, rf, df in ((0.0, 0.018, 0.022, 0.0), (0.06, 0.017, 0.023, 0.003), (0.10, 0.021, 0.029, 0.009),
                                       (0.13, 0.020, 0.027, 0.010), (0.145, 0.014, 0.020, 0.008))]
        loft('stick_grip', rings, M['grip'], bevel=0.002)
        rings = [ring(P(0.30, con_u + h / 2, f), R, U, w * k, h / 2 * k, n=14, sq=3.5)
                 for f, h, w in ((hip_f + 0.05, 0.05, 0.04), (hip_f + 0.20, 0.065, 0.047), (hip_f + 0.36, 0.08, 0.042))]
        loft('stick_armrest', rings, M['tub_d'], bevel=0.003)
        foot = base
    else:
        # the centre stick: a boot on the floor, the column, the grip with its trigger
        sf = hip_f + (0.36 if not wwii else 0.32)
        hd = hit(skin, P(0, -0.50, sf), -U)            # the floor under the stick, as the hull has it there
        if SIMPLE and hd is not None:
            floor_u = max(floor_u, -0.50 - hd / k + 0.01)
        base = P(0, floor_u, sf)
        top_u = (hip - E).dot(U) / k + 0.26
        cyl('stick_boot', base + U * 0.025 * k, U, 0.05, 0.025, M['black'])
        s_ax = (U * math.cos(math.radians(8)) - F * math.sin(math.radians(8))).normalized()
        L_ = (top_u - floor_u) / s_ax.dot(U)
        cyl('stick_column', base + s_ax * (L_ / 2) * k, s_ax, 0.012, L_ / 2, M['metal'])
        g0 = base + s_ax * L_ * k
        rings = [ring(g0 + s_ax * t * k, R, F, rr * k, rf * k, n=14) for t, rr, rf in
                 ((0.0, 0.017, 0.020), (0.05, 0.016, 0.019), (0.09, 0.019, 0.024), (0.12, 0.018, 0.022), (0.135, 0.012, 0.015))]
        loft('stick_grip', rings, M['grip'], bevel=0.002)
        box('stick_trigger', g0 + s_ax * 0.06 * k - F * -0.022 * k, (R, s_ax, F), (0.005, 0.012, 0.004), M['metal'])
        foot = base
    # throttle on the left console's forward end (WWII: on the left wall above it)
    tq = P(-0.29 if not SIMPLE else -min(0.29, con_r + 0.07), con_u + 0.012, hip_f + 0.28)
    box('throttle_quadrant', tq, (R, U, F), (0.028, 0.012, 0.10), M['grip'], bevel=0.003)
    t_ax = (U * math.cos(math.radians(20)) + F * math.sin(math.radians(20))).normalized()
    lever = tq + U * 0.012 * k + F * 0.02 * k
    cyl('throttle_lever', lever + t_ax * 0.03 * k, t_ax, 0.008, 0.03, M['metal'])
    rings = [ring(lever + t_ax * t * k + R * dr * k, R, F, rr * k, rf * k, n=14) for t, rr, rf, dr in
             ((0.055, 0.022, 0.034, 0.0), (0.08, 0.027, 0.040, 0.004), (0.10, 0.025, 0.036, 0.006), (0.115, 0.017, 0.026, 0.005))]
    loft('throttle_grip', rings, M['grip'], bevel=0.002)
    for sg in (-1, 1):
        p_ax = (U * math.cos(math.radians(-25)) + F * math.sin(math.radians(-25))).normalized()
        box(f'pedal_{sg}', P(sg * 0.10, floor_u + 0.20, F_FW - 0.12), (R, p_ax, R.cross(p_ax)), (0.038, 0.06, 0.012),
            M['metal'])
    return foot


def build_seat(si, E, P, hip, s_up, s_fw):
    kind = spec['seat']
    ax_back = (R, s_up, s_fw)
    W_ = 0.19 if kind != 'bucket' else 0.20
    for sg in (-1, 1):
        x = sg * (W_ + 0.01)
        prof = [hip + R * x * k + F * 0.40 * k - U * 0.12 * k, hip + R * x * k + F * 0.40 * k + U * 0.02 * k,
                hip + R * x * k + F * 0.10 * k + U * 0.08 * k, hip + R * x * k + s_up * 0.45 * k - s_fw * 0.02 * k,
                hip + R * x * k + s_up * 0.45 * k - s_fw * 0.11 * k, hip + R * x * k - s_fw * 0.11 * k - U * 0.12 * k]
        bm = bmesh.new()
        bm.faces.new([bm.verts.new(p) for p in prof])
        obj(f'seat_side_frame_{sg}', bm, M['seat'] if kind != 'bucket' else M['armour'], bevel=0.004, solid=0.012)
    box('seat_pan', hip + F * 0.19 * k + U * 0.012 * k, (R, U, F), (W_ - 0.01, 0.024, 0.19), M['cushion'], bevel=0.012)
    box('seat_kit', hip + F * 0.19 * k - U * 0.06 * k, (R, U, F), (W_ - 0.005, 0.05, 0.20),
        M['chute'] if kind != 'bucket' else M['armour'], bevel=0.008)
    back = hip + s_up * 0.30 * k - s_fw * 0.045 * k
    box('seat_back_cushion', back, ax_back, (W_ - 0.04, 0.26, 0.026), M['cushion'], bevel=0.012)
    box('seat_back_shell', back - s_fw * 0.05 * k + s_up * 0.04 * k, ax_back, (W_ - 0.015, 0.32, 0.022),
        M['seat'] if kind != 'bucket' else M['armour'], bevel=0.008)
    head = hip + s_up * 0.74 * k
    if kind in ('ejection', 'ejection_early'):
        hb_d = 0.07 if kind == 'ejection' else 0.05
        box('seat_headbox', head - s_fw * (0.03 + hb_d) * k + s_up * 0.03 * k, ax_back,
            (0.155 if kind == 'ejection' else 0.13, 0.13, hb_d), M['headbox'], bevel=0.018)
        box('seat_headrest', head - s_fw * 0.012 * k, ax_back, (0.075, 0.08, 0.016), M['cushion'], bevel=0.01)
        for sg in (-1, 1):
            box(f'seat_rail_{sg}', hip + R * sg * 0.14 * k + s_up * 0.32 * k - s_fw * 0.10 * k, ax_back,
                (0.016, 0.40, 0.018), M['metal'], bevel=0.003)
        # the ejection handle: a loop on the seat's front (modern) or the face-curtain handle over the head (early)
        if kind == 'ejection':
            lp = hip + F * 0.395 * k + U * 0.02 * k
            pts = [lp + R * (0.042 * math.cos(a)) * k + U * (0.032 * math.sin(a)) * k for a in [math.pi * i / 6 for i in range(7)]]
            for i in range(len(pts) - 1):
                a, b = pts[i], pts[i + 1]
                cyl(f'seat_loop_{i}', (a + b) / 2, b - a, 0.007, (b - a).length / k / 2 + 0.001,
                    M['yellow'] if i % 2 == 0 else M['loop_black'], segs=8)
        else:
            box('seat_face_curtain', head + s_up * 0.15 * k + s_fw * 0.01 * k, ax_back, (0.06, 0.012, 0.012), M['yellow'],
                bevel=0.004)
    else:
        # WWII: an armour plate behind the head instead of a headbox
        box('seat_armour_head', head - s_fw * 0.04 * k + s_up * 0.04 * k, ax_back, (0.13, 0.14, 0.008), M['armour'],
            bevel=0.004)
        box('seat_headrest', head - s_fw * 0.025 * k, ax_back, (0.07, 0.06, 0.015), M['cushion'], bevel=0.01)


def build_gunner_seat(si, E, P, floor_u):
    """the rear gunner (Ju 87, Il-10): a simple seat facing aft at his station"""
    hip = P(0, max(floor_u + 0.28, -0.75), 0.05)
    box('gunner_seat_pan', hip, (R, U, F), (0.17, 0.025, 0.16), M['cushion'], bevel=0.01)
    box('gunner_seat_back', hip + U * 0.25 * k + F * 0.16 * k, (R, U, F), (0.17, 0.24, 0.02), M['armour'], bevel=0.006)
    box('gunner_floor', P(0, floor_u, 0.0), (R, U, F), (0.18, 0.006, 0.30), M['floor'])


# ======================================================================== the stations
order = sorted(range(len(EYES)), key=lambda i: -(EYES[i] - EYES[0]).dot(F))      # front first
FRONT_INDEX[0] = order[0]
front = None
for si in order:
    build_station(si, EYES[si], None if front is None else front)
    front = EYES[si] if front is None else front

D26 = [Vector((x, y, z)).normalized() for x in (-1, 0, 1) for y in (-1, 0, 1) for z in (-1, 0, 1) if (x, y, z) != (0, 0, 0)]


def outside(p):
    """outside the hull: a quick test on the six axis rays first, then 12 or more of the 26 rays from the point get out
    past the hull and the glass. The models leak (small holes, the gap between the glass and the sill), so a few rays
    getting out is still inside; tools/blender_cockpit/inside_grid.py maps the counts"""
    if sum(hit(skin, p, dv) is None and hit(gl, p, dv) is None for dv in (R, -R, U, -U, F, -F)) < 2:
        return False
    return sum(hit(skin, p, dv, far=40.0 * k) is None and hit(gl, p, dv, far=40.0 * k) is None for dv in D26) >= 12


# ======================================================================== fit to the hull (as f16c_structure.py)
MARGIN = 0.015
TUB_PARTS = ('wall_', 'sill_', 'floor', 'rear_bulkhead', 'aft_deck', 'footwell_', 'console_side_', 'pedal_', 'gunner_floor')
EDGE_PARTS = ('panel_board', 'panel_body', 'panel_cheek', 'knee_panel', 'glareshield', 'console_top')


def base(name):
    return name.split('_', 1)[1] if len(EYES) > 1 and name[:1] == 's' and '_' in name else name


def anchor_of(o, p):
    E = EYES[station_of.get(o.name, 0)]
    return E + U * (ANCHOR_U[station_of.get(o.name, 0)] * k) + F * (p - E).dot(F)


if SIMPLE:
    # the rigid fit: no vertex is ever moved on its own. A part that crosses the hull, comes within 3 cm of the glass
    # or has a vertex outside the walls is scaled whole toward a point inside the cockpit (the centre line at its own
    # height and station; seat and control parts about their own centre), 8 % a step, up to ten steps; one that still
    # does not fit is dropped. The panel board and its instruments shrink together about the board's centre.
    moved = nudged = removed = glass_trim = 0
    dropped, trimmed, shrunk = [], {}, {}
    bpy.context.view_layer.update()
    for o in cockpit:
        for m_ in list(o.modifiers):
            bpy.context.view_layer.objects.active = o
            bpy.ops.object.modifier_apply(modifier=m_.name)
    SELF_PIVOT = ('seat_', 'stick_', 'throttle_', 'pedal_', 'gunner_')

    def clips(objs):
        bpy.context.view_layer.update()
        for o in objs:
            if B.bvh_of([o]).overlap(skin):
                if os.environ.get('CB_DEBUG'):
                    print('DBG clip hull', o.name, len(B.bvh_of([o]).overlap(skin)))
                return True
            mw = o.matrix_world
            for v in o.data.vertices:
                p = mw @ v.co
                loc_, n_, i_, dist_ = gl.find_nearest(p)
                if loc_ is not None and dist_ < 0.030 * k:
                    if os.environ.get('CB_DEBUG'):
                        print('DBG clip glass', o.name, [round(x / k, 3) for x in (p - EYES[0])])
                    return True
                if outside(p):
                    if os.environ.get('CB_DEBUG'):
                        print('DBG clip outside', o.name, [round(x / k, 3) for x in (p - EYES[0])])
                    return True
        return False

    def centre(objs):
        pts = [o.matrix_world @ v.co for o in objs for v in o.data.vertices]
        return sum(pts, Vector()) / max(1, len(pts))

    def scale_about(objs, pivot, s):
        for o in objs:
            mi = o.matrix_world.inverted()
            for v in o.data.vertices:
                p = o.matrix_world @ v.co
                v.co = mi @ (pivot + (p - pivot) * s)

    def drop(o):
        dropped.append(o.name)
        cockpit.remove(o)
        bpy.data.objects.remove(o)

    PANEL_FIT = {}
    for si in order:
        grp = [o for o in cockpit if o.name in PANEL_PARTS and station_of.get(o.name) == si]
        board = [o for o in grp if base(o.name) == 'panel_board']
        if not board:
            continue
        pivot = centre(board)
        f_ = 1.0
        for _ in range(10):
            if not clips(grp):
                break
            scale_about(grp, pivot, 0.92)
            f_ *= 0.92
        PANEL_FIT[si] = f_
        if f_ < 1.0:
            shrunk[board[0].name] = round(f_, 3)
    for it in INSTR:
        it['fit'] = PANEL_FIT.get(it['station'], 1.0)
    for o in list(cockpit):
        if not clips([o]):
            continue
        if o.name in PANEL_PARTS:
            drop(o)
            continue
        c = centre([o])
        co0 = [v.co.copy() for v in o.data.vertices]
        pivots = [PIVOT[o.name]] if o.name in PIVOT else []
        pivots.append(c if base(o.name).startswith(SELF_PIVOT) else anchor_of(o, c))
        fitted = None
        for pivot in pivots:                  # a part's own pivot first; failing that, the default one
            for v, c0 in zip(o.data.vertices, co0):
                v.co = c0
            f_ = 1.0
            for _ in range(10):
                scale_about([o], pivot, 0.92)
                f_ *= 0.92
                if not clips([o]):
                    fitted = f_
                    break
            if fitted:
                break
        if not fitted:
            drop(o)
            continue
        shrunk[o.name] = round(fitted, 3)
    print('SHRUNK', json.dumps(shrunk))
else:
    moved = 0
    bpy.context.view_layer.update()
    for o in cockpit:
        nm = base(o.name)
        for m_ in list(o.modifiers):
            bpy.context.view_layer.objects.active = o
            bpy.ops.object.modifier_apply(modifier=m_.name)
        bm = bmesh.new(); bm.from_mesh(o.data)
        lim = (0.08 if nm.startswith(TUB_PARTS) else 0.10 if nm.startswith(EDGE_PARTS) else 99) * k
        for _ in range(2):
            long_edges = [e for e in bm.edges if e.calc_length() > lim]
            if long_edges:
                bmesh.ops.subdivide_edges(bm, edges=long_edges, cuts=1, use_grid_fill=True)
        bm.to_mesh(o.data); bm.free()
        marg = MARGIN if nm.startswith(TUB_PARTS) else 0.045 if nm.startswith(EDGE_PARTS) else 0.03
        for v in [v for _ in range(2) for v in o.data.vertices]:
            p = o.matrix_world @ v.co
            anchor = anchor_of(o, p)
            dvec = p - anchor
            L_ = dvec.length
            if L_ < 1e-6:
                continue
            dn = dvec / L_
            GM = max(marg, 0.036)
            hs = hit(skin, anchor, dn, far=L_ + marg * k)
            hg = hit(gl, anchor, dn, far=L_ + GM * k)
            stops = [h - m_ * k for h, m_ in ((hs, marg), (hg, GM)) if h is not None]
            if stops and min(stops) < L_:
                v.co = o.matrix_world.inverted() @ (anchor + dn * max(0.0, min(stops)))
                moved += 1
    nudged = 0
    for o in cockpit:
        if base(o.name).startswith(TUB_PARTS):
            continue
        for _ in range(25):
            bpy.context.view_layer.update()
            bad = {a for a, b_ in B.bvh_of([o]).overlap(skin)}
            if not bad:
                break
            vids = {i for f in bad if f < len(o.data.polygons) for i in o.data.polygons[f].vertices}
            for i in vids:
                v = o.data.vertices[i]
                p = o.matrix_world @ v.co
                d_ = p - anchor_of(o, p)
                if d_.length > 0.02 * k:
                    v.co = o.matrix_world.inverted() @ (p - d_.normalized() * 0.015 * k)
                    nudged += 1
    for o in cockpit:
        for v in o.data.vertices:
            for _ in range(10):
                p = o.matrix_world @ v.co
                loc_, n_, i_, dist_ = gl.find_nearest(p)
                if loc_ is None or dist_ >= 0.032 * k:
                    break
                d_ = p - anchor_of(o, p)
                if d_.length < 0.02 * k:
                    break
                v.co = o.matrix_world.inverted() @ (p - d_.normalized() * (0.034 * k - dist_ + 0.002 * k))
    removed = 0
    for o in cockpit:
        if not base(o.name).startswith(TUB_PARTS):
            continue
        bpy.context.view_layer.update()
        bad = {a for a, b_ in B.bvh_of([o]).overlap(skin)}
        if bad:
            bm = bmesh.new(); bm.from_mesh(o.data); bm.faces.ensure_lookup_table()
            bmesh.ops.delete(bm, geom=[bm.faces[i] for i in bad if i < len(bm.faces)], context='FACES')
            bm.to_mesh(o.data); bm.free()
            removed += len(bad)
    # a part that still crosses the hull after all that is dropped (and reported), so nothing ever clips
    bpy.context.view_layer.update()
    # faces with a vertex still within 3 cm of the glass after the fit are trimmed (the 3 cm clearance is a rule)
    glass_trim = 0
    for o in cockpit:
        close = {v.index for v in o.data.vertices
                 if (lambda q: q[0] is not None and q[3] < 0.030 * k)(gl.find_nearest(o.matrix_world @ v.co))}
        if close:
            bm = bmesh.new(); bm.from_mesh(o.data); bm.verts.ensure_lookup_table()
            faces = {f_ for i in close for f_ in bm.verts[i].link_faces}
            bmesh.ops.delete(bm, geom=list(faces), context='FACES')
            loose = [v for v in bm.verts if not v.link_faces]
            bmesh.ops.delete(bm, geom=loose, context='VERTS')
            bm.to_mesh(o.data); bm.free()
            glass_trim += len(faces)
    print('GLASS_TRIM', glass_trim)
    dropped, trimmed = [], {}
    for o in list(cockpit):
        bad = {a for a, b_ in B.bvh_of([o]).overlap(skin)}
        if not bad:
            continue
        n_all = len(o.data.polygons)
        if len(bad) < 0.5 * n_all:            # trim the few faces that still cross the hull; the rest of the part stays
            bm = bmesh.new(); bm.from_mesh(o.data); bm.faces.ensure_lookup_table()
            bmesh.ops.delete(bm, geom=[bm.faces[i] for i in bad if i < len(bm.faces)], context='FACES')
            bm.to_mesh(o.data); bm.free()
            trimmed[o.name] = len(bad)
            bpy.context.view_layer.update()
            if not B.bvh_of([o]).overlap(skin):
                continue
        dropped.append(o.name)
        cockpit.remove(o)
        bpy.data.objects.remove(o)

# ======================================================================== check
bpy.context.view_layer.update()
clip = {}
for o in cockpit:
    n = len(B.bvh_of([o]).overlap(skin))
    if n:
        clip[o.name] = n
inside_fail = 0
outside_parts = {}
min_glass = 9.0
for o in cockpit:
    for v in o.data.vertices:
        p = o.matrix_world @ v.co
        if any(math.isnan(c) for c in p):
            inside_fail += 1
            continue
        if outside(p):
            inside_fail += 1
            outside_parts[o.name] = outside_parts.get(o.name, 0) + 1
        loc_, n_, i_, dist_ = gl.find_nearest(p)
        if loc_ is not None:
            min_glass = min(min_glass, dist_ / k)
check = dict(face_pairs_touching_fuselage=sum(clip.values()), clipping_parts=clip, vertices_outside_walls=inside_fail, outside_parts=outside_parts,
             min_glass_clearance_m=round(min_glass, 4), dropped_parts=dropped, trimmed_faces=trimmed, moved=moved, nudged=nudged,
             tub_faces_removed=removed, shrunk=shrunk if SIMPLE else {}, report=REPORT)
json.dump(check, open(os.path.join(out, 'check.json'), 'w'), indent=1)
print('CHECK', json.dumps({k_: v for k_, v in check.items() if k_ != 'report'}))
print('REPORT', json.dumps(REPORT))
import game_export
_n = game_export.export(os.path.join(out, 'game_export.json'), cockpit, model, INSTR, STATIONS,
                        lambda si: EYES[si], k=k)
print('GAME_EXPORT faces/glass/instruments', _n)

# ======================================================================== save and render
E0 = EYES[order[0]]


def P0(r, u, f):
    return E0 + (R * r + U * u + F * f) * k


for nm, loc, energy, size in (('key', P0(0.15, 0.25, -0.15), 6, 0.6), ('fill', P0(0, 0.15, 0.05), 0.6, 0.5)):
    ld = bpy.data.lights.new(nm, 'AREA'); ld.energy = energy * k * k; ld.size = size * k
    lo = bpy.data.objects.new(nm, ld); sc.collection.objects.link(lo)
    lo.location = loc; B.look_at(lo, P0(0, -0.5, 0.75))
bpy.ops.wm.save_as_mainfile(filepath=os.path.join(out, 'structure.blend'))
views = dict(pilot_eye=(E0, P0(0, -0.40, 0.80), 24), panel_straight=(P0(0.0, -0.30, -0.05), P0(0.0, -0.42, 0.80), 26),
             seat_back=(P0(0, -0.08, 0.45), P0(0, -0.45, -0.1), 18),
             console_left=(P0(0.10, 0.02, -0.10), P0(-0.27, -0.62, 0.20), 22),
             console_right=(P0(-0.10, 0.02, -0.10), P0(0.27, -0.62, 0.20), 22),
             outside=(E0 + B.g2b([2.0, 1.2, 3.0]), P0(0, -0.2, 0.2), 30))
for name, (loc, tgt, lens) in views.items():
    B.render_view(os.path.join(out, f'{name}.png'), loc, tgt, lens=lens, w=1280, h=800)
print('DONE')
