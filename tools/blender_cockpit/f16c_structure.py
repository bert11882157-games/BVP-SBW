"""Blender: the F-16C Block 50/52 cockpit structure (no instruments yet), modelled inside the real model.

References (all Block 50/52; see research/f_16c_cockpit.md):
  1. "F16 Cockpit, Asian Aerospace 2006" (Block 52): panel layout, the ICP tower, the MFD hoods, the engine
     column, the centre pedestal, the seat handle.
  2. "FalconBMSCptNolight" (Falcon BMS, Block 50): the pilot's-eye layout.
  3. Misawa Block 50 (90-0812), "Maj Kathryn Gaetke", profile: glareshield line, HUD height, seat position.
  4. Shaw Block 50, "Weasels operate at night during RF-N 23-2", profile: the ACES II headbox and frame colours.
  5. 78th FS F-16CJ, "1LT Brad Turner": the HUD and glareshield close up.

Run: blender --background --python f16c_structure.py -- EXPORTDIR OUTDIR
Writes OUTDIR/structure.blend, renders (seat, panel, left, right, seat_back, outside) and check.json (the clipping
test: every cockpit face against every fuselage face, every vertex inside the walls and under the glass).
"""
import json, math, os, sys
import bpy, bmesh
from mathutils import Vector, Matrix
from mathutils.bvhtree import BVHTree
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import bl_common as B

args = sys.argv[sys.argv.index('--') + 1:]
d, out = args[0], args[1]
os.makedirs(out, exist_ok=True)
B.reset()
model, glass, meta = B.import_aircraft(d)
sc = B.setup_render()
E = B.g2b(meta['eyes'][0])
k = meta['tub']['s']                         # model blocks per metre for this airframe
R, U, F = Vector((-1, 0, 0)), Vector((0, 0, 1)), Vector((0, -1, 0))     # pilot's right, up, forward (Blender)
# the model's own stick goes (the new one replaces it), so it is no obstacle
for o in model:
    if 'control_stick' in o.name:
        o.hide_render = True
model = [o for o in model if 'control_stick' not in o.name]
skin = B.bvh_of(model)
gl = B.bvh_of([glass])
cockpit = []                                 # every part object


def P(r, u, f):
    return E + (R * r + U * u + F * f) * k


def hit(bvh, p, d, far=6.0):
    loc, n, i, dist = bvh.ray_cast(p, d.normalized(), far)
    return dist if loc is not None else None


def wall(u, f, side, cap=0.50):
    """metres from the centre line to the nearest fuselage surface on the right (+1) or left (-1)"""
    dist = hit(skin, P(0, u, f), R * side)
    return min(cap, dist / k) if dist is not None else cap


def glass_over(r, f, u=-0.3):
    dist = hit(gl, P(r, u, f), U)
    return u + dist / k if dist is not None else None


def ahead(u, r=0.0):
    dist = hit(skin, P(r, u, 0.1), F)
    return 0.1 + dist / k if dist is not None else 3.0


# -- materials (flat colours for the structure stage; textures come with the instruments)
def mat(name, rgb, rough=0.7, metal=0.0):
    m = bpy.data.materials.new(name)
    m.use_nodes = True
    b = m.node_tree.nodes.get('Principled BSDF')
    b.inputs['Base Color'].default_value = (*[c / 255 for c in rgb], 1)
    b.inputs['Roughness'].default_value = rough
    b.inputs['Metallic'].default_value = metal
    return m

M = dict(panel=mat('panel', (34, 35, 37)), coaming=mat('coaming', (22, 22, 23), 0.8),
         tub=mat('tub', (112, 116, 116)), tub_d=mat('tub_d', (88, 92, 92)), floor=mat('floor', (28, 28, 29)),
         console=mat('console', (36, 37, 39)), seat=mat('seat_frame', (128, 134, 128), 0.5),
         cushion=mat('cushion', (150, 156, 132), 0.9), headbox=mat('headbox', (108, 116, 104)),
         black=mat('black', (16, 16, 17), 0.6), metal=mat('metal', (140, 144, 146), 0.35, 0.8),
         yellow=mat('yellow', (230, 190, 30)), grip=mat('grip', (26, 26, 27), 0.6),
         hud=mat('hud', (40, 42, 44), 0.5), glassplate=mat('glassplate', (120, 160, 150), 0.1),
         hudglass=mat('hudglass', (150, 190, 170), 0.05), loop_black=mat('loop_black', (20, 20, 20), 0.6),
         chute=mat('chute', (96, 104, 92), 0.8))
_hb = next(n for n in M['hudglass'].node_tree.nodes if n.type == 'BSDF_PRINCIPLED')
_hb.inputs['Alpha'].default_value = 0.22
for _attr, _val in (('surface_render_method', 'BLENDED'), ('blend_method', 'BLEND')):
    try:
        setattr(M['hudglass'], _attr, _val)
    except Exception:
        pass


def obj(name, bm, material, bevel=0.0, solid=0.0):
    me = bpy.data.meshes.new(name)
    bm.to_mesh(me); bm.free()
    o = bpy.data.objects.new(name, me)
    sc.collection.objects.link(o)
    o.data.materials.append(material)
    if solid:
        s = o.modifiers.new('solid', 'SOLIDIFY'); s.thickness = solid * k; s.offset = -1
    if bevel:
        b = o.modifiers.new('bevel', 'BEVEL'); b.width = bevel * k; b.segments = 2; b.limit_method = 'ANGLE'
    cockpit.append(o)
    return o


def box(name, centre, axes, half, material, bevel=0.004):
    """an oriented box: centre, three unit axes (Vectors), half sizes in metres"""
    bm = bmesh.new()
    c = Vector(centre)
    A = [a.normalized() * h * k for a, h in zip(axes, half)]
    vs = [bm.verts.new(c + A[0] * sx + A[1] * sy + A[2] * sz) for sx in (-1, 1) for sy in (-1, 1) for sz in (-1, 1)]
    for f in ((0, 1, 3, 2), (4, 6, 7, 5), (0, 4, 5, 1), (2, 3, 7, 6), (0, 2, 6, 4), (1, 5, 7, 3)):
        bm.faces.new([vs[i] for i in f])
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
    return obj(name, bm, material, bevel=bevel)


def sheet(name, rows, material, solid=0.02, bevel=0.003):
    """a surface through rows of points (each row a list of Vectors, all rows the same length), thickened"""
    bm = bmesh.new()
    V = [[bm.verts.new(p) for p in row] for row in rows]
    for i in range(len(V) - 1):
        for j in range(len(V[i]) - 1):
            bm.faces.new([V[i][j], V[i][j + 1], V[i + 1][j + 1], V[i + 1][j]])
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
    return obj(name, bm, material, bevel=bevel, solid=solid)


# ======================================================================== measurements
lean = math.radians(15)
F_PANEL = 0.80
up15 = (U * math.cos(lean) + F * math.sin(lean)).normalized()
U_TOP = -0.28
hw_panel = min(wall(-0.45, F_PANEL, 1), wall(-0.45, F_PANEL, -1)) - 0.012
ws_f = ahead(-0.30) - 0.03
print('MEASURE panel half width', round(hw_panel, 3), 'windscreen base f', round(ws_f, 3),
      'glass over panel', glass_over(0, F_PANEL))

# ======================================================================== main instrument panel
# a flat board leaning back 15 degrees across the cockpit, its outer corners clipped to the walls; the centre
# column (ICP tower above, centre instruments, the pedestal below) stands proud of it by 4 cm
def panel_pt(r, u, lift=0.0):
    f = F_PANEL + (u + 0.45) * math.tan(lean)
    n = (F * -math.cos(lean) + U * math.sin(lean)).normalized()
    return P(r, u, f) + n * lift * k

pn = (F * -math.cos(lean) + U * math.sin(lean)).normalized()          # the panel's face normal, toward the pilot
def reach(u, side, lift=0.0, clear=0.04, pt=None):
    """metres from the centre line a panel point at height u can go on one side and still be seen from the fit's
    anchor (half a metre under the eye at its station) with `clear` to spare: what the keep-inside pass allows, so
    the panel's edges are laid there and not pulled in afterwards"""
    lo, hi = 0.0, 0.50
    for _ in range(14):
        m = (lo + hi) / 2
        p = pt(side * m) if pt else panel_pt(side * m, u, lift)
        anchor = E + U * (-0.5 * k) + F * (p - E).dot(F)
        dv = p - anchor
        L_ = dv.length
        blocked = any(h is not None for h in (hit(skin, anchor, dv, far=L_ + clear * k), hit(gl, anchor, dv, far=L_ + clear * k)))
        lo, hi = (lo, m) if blocked else (m, hi)
    return lo


rows = []
# the main panel is the upper band only (the xflight.de Block 50/52 "Cockpit Arrangement (Typical)" drawing): it
# ends a little under the MFDs, the centre column alone runs on down between the knees, and the aux consoles stand
# apart at the sides lower down
PANEL_FOOT = -0.585
# the main panel's outline traced off that drawing (metres at the drawing's scale, 1 px = 1.25 mm at 1400 px wide,
# from the MFD bezel's 162 mm): (right, down from the glareshield lip), symmetrical, the centre column left out
# (it is its own part below); the shoulders slope in under the glareshield, the sides step out at the foot into the
# two "wings", and on the right the engine-instrument column runs on below the wing
TRACE = [(-0.238, 0.0), (0.238, 0.0), (0.352, -0.1175), (0.355, -0.309), (0.354, -0.350), (0.282, -0.350),
         (0.280, -0.2875), (-0.281, -0.2875), (-0.289, -0.3375), (-0.355, -0.3375), (-0.355, -0.309), (-0.352, -0.1175)]
# (the drawing's two "wings" at the foot are the side shelves seen from above, so they are left off)


def trace_fits(s):
    for r, du in TRACE:
        if abs(r) < 0.05:
            continue
        if abs(r) * s > reach(U_TOP + du * s, 1 if r > 0 else -1, clear=0.035):
            return False
    return True


TRACE_S = next((s / 100 for s in range(100, 60, -1) if trace_fits(s / 100)), 0.6)
print('MEASURE trace scale', TRACE_S)
bm = bmesh.new()
vs = [bm.verts.new(panel_pt(r * TRACE_S, U_TOP + du * TRACE_S)) for r, du in TRACE]
bm.faces.new(vs)
bmesh.ops.triangulate(bm, faces=bm.faces[:])
for f_ in bm.faces:                  # face the pilot, so the thickness goes behind the panel face
    f_.normal_update()
    if f_.normal.dot(pn) < 0:
        f_.normal_flip()
obj('panel_board', bm, M['panel'], solid=0.025)
json.dump(dict(trace=TRACE, scale=TRACE_S), open(os.path.join(out, 'trace.json'), 'w'))


def housing(name, a, u, w, h, proud, open_w=None, open_h=None, material=None):
    """a raised housing on the board (an MFD or display bezel): a frame w x h standing `proud` metres off the board,
    its opening open_w x open_h left recessed"""
    c = panel_pt(a, u)
    if open_w is None:
        box(name, c + pn * proud / 2 * k, (R, up15, pn), (w / 2, h / 2, proud / 2), material or M['panel'], bevel=0.003)
        return
    t_x, t_y = (w - open_w) / 2, (h - open_h) / 2
    for nm, cx, cy, hx, hy in (('t', 0, (h - t_y) / 2, w / 2, t_y / 2), ('b', 0, -(h - t_y) / 2, w / 2, t_y / 2),
                               ('l', -(w - t_x) / 2, 0, t_x / 2, open_h / 2), ('r', (w - t_x) / 2, 0, t_x / 2, open_h / 2)):
        box(f'{name}_{nm}', c + R * cx * k + up15 * cy * k + pn * proud / 2 * k, (R, up15, pn), (hx, hy, proud / 2),
            material or M['panel'], bevel=0.002)


def facing(c, yaw=None, half=False):
    """axes (right, up, normal) for a face at c turned toward the eye; yaw (degrees) instead turns it about the
    vertical only, by that much toward the centre line, on the panel's 15 degree lean"""
    if yaw is not None:
        n = (pn * math.cos(math.radians(yaw)) - R * math.copysign(1, (c - E).dot(R)) * math.sin(math.radians(yaw)))
        n = n.normalized()
    else:
        n = (E - c).normalized()
        if half:                         # half way between the panel's face and the eye, so no edge sinks in
            n = (n + pn).normalized()
    ar = U.cross(n).normalized()
    if ar.dot(R) < 0:
        ar = -ar
    au = n.cross(ar).normalized()
    if au.dot(U) < 0:
        au = -au
    return ar, au, n


def framed(name, c, axes, w, h, depth, open_w, open_h, material, recess=0.012):
    """a bezel w x h standing `depth` proud along axes[2], with a recessed open_w x open_h window and the window's
    floor `recess` behind the bezel face"""
    ar, au, an = axes
    t_x, t_y = (w - open_w) / 2, (h - open_h) / 2
    for nm, cx, cy, hx, hy in (('t', 0, (h - t_y) / 2, w / 2, t_y / 2), ('b', 0, -(h - t_y) / 2, w / 2, t_y / 2),
                               ('l', -(w - t_x) / 2, 0, t_x / 2, open_h / 2), ('r', (w - t_x) / 2, 0, t_x / 2, open_h / 2)):
        box(f'{name}_{nm}', c + ar * cx * k + au * cy * k - an * depth / 2 * k, axes, (hx, hy, depth / 2), material,
            bevel=0.002)
    box(f'{name}_floor', c - an * (recess + 0.002) * k, axes, (open_w / 2, open_h / 2, 0.002), M['black'], bevel=0.0)


def cyl(name, c, axis, radius, half_len, material, segs=20):
    bm = bmesh.new()
    bmesh.ops.create_cone(bm, cap_ends=True, segments=segs, radius1=radius * k, radius2=radius * k,
                          depth=2 * half_len * k)
    rot = Vector((0, 0, 1)).rotation_difference(axis.normalized()).to_matrix().to_4x4()
    bmesh.ops.transform(bm, matrix=Matrix.Translation(c) @ rot, verts=bm.verts)
    return obj(name, bm, material)


# -- the upfront row (ref. p-013, f_16c_01): ICP centre under the HUD, DED right of it, RWR scope and the threat
# prime block upper left; the MFDs side by side below, either side of a narrow centre column
# positions off the traced drawing, at its fitted scale; the bezels keep their real 162 mm
MFD_U, MFD_R, MFD_W, MFD_OPEN = U_TOP - 0.214 * TRACE_S, 0.191 * TRACE_S, 0.162, 0.102
for sg in (-1, 1):
    c = panel_pt(sg * MFD_R, MFD_U, 0.045)
    ax = facing(c, half=True)
    framed(f'mfd_{sg}', c, ax, MFD_W, MFD_W, 0.03, MFD_OPEN, MFD_OPEN, M['panel'])
    ar, au, an = ax
    # five raised OSB blanks on each edge of the bezel, rocker blanks across each corner
    pitch = 0.020
    for side, (dx, dy) in enumerate(((0, 1), (0, -1), (-1, 0), (1, 0))):
        for i in range(5):
            o_ = (i - 2) * pitch
            q = c + (ar * (dx * 0.066 + (o_ if dy else 0)) + au * (dy * 0.066 + (o_ if dx else 0))) * k + an * 0.003 * k
            box(f'mfd_{sg}_osb_{side}_{i}', q, ax, (0.0065 if dy else 0.005, 0.005 if dy else 0.0065, 0.003),
                M['grip'], bevel=0.0015)
    for cx_, cy_ in ((-1, 1), (1, 1), (-1, -1), (1, -1)):
        d45 = (ar * cx_ + au * cy_).normalized()
        q = c + (ar * cx_ + au * cy_) * 0.066 * k + an * 0.003 * k
        box(f'mfd_{sg}_rocker_{cx_}_{cy_}', q, (d45.cross(an).normalized(), d45, an), (0.004, 0.011, 0.003), M['grip'],
            bevel=0.0015)
# the hood over each MFD: the coaming steps down from the glareshield lip to the bezel's top edge
for sg in (-1, 1):
    a, b_ = panel_pt(sg * (MFD_R - 0.085), U_TOP, 0.05), panel_pt(sg * (MFD_R + 0.085), U_TOP, 0.05)
    a2, b2 = panel_pt(sg * (MFD_R - 0.085), MFD_U + 0.084, 0.07), panel_pt(sg * (MFD_R + 0.085), MFD_U + 0.084, 0.07)
    rows = [[a, b_], [a2, b2]] if sg > 0 else [[b_, a], [b2, a2]]
    pass   # (no separate hood: the glareshield lip covers the MFDs, and a hood hid their top keys from the eye)

# left upper: the RWR azimuth scope and, outboard of it, the threat-prime / TWP block (above the left MFD's outer edge)
MFD_EDGE = MFD_R + MFD_W / 2


def outboard(side, u, hw, lift, pref):
    """the panel's outboard column: as far out as `pref`, but inside what the fit allows and clear of the MFD"""
    r = min(pref, reach(u, side, lift, clear=0.05) - hw)
    return side * max(r, MFD_EDGE + hw + 0.006)


c = panel_pt(-0.156 * TRACE_S, U_TOP - 0.069 * TRACE_S, 0.03)
framed('rwr_scope', c, facing(c), 0.084, 0.084, 0.022, 0.062, 0.062, M['panel'])
def facet(name, side, u_top, u_bot, yaw=25):
    """the panel's outboard facet: one plane hinged on the MFD's outer edge (just clear of the bezel) and turned
    `yaw` degrees toward the pilot, out to the traced side or the wall, whichever is nearer; returns
    (centre, (right, up, normal), half width, half height) for the details laid on it"""
    r_in = MFD_EDGE + 0.006
    corners = []
    for u in (u_top, u_bot):
        r_out = min(0.352 * TRACE_S, reach(u, side, clear=0.045) - 0.01)
        lift_out = (r_out - r_in) * math.tan(math.radians(yaw))
        corners.append((panel_pt(side * r_in, u, 0.004), panel_pt(side * r_out, u, 0.004 + lift_out)))
    (ti, to), (bi, bo) = corners
    rows = [[ti, to], [bi, bo]] if side > 0 else [[to, ti], [bo, bi]]
    sheet(name, rows, M['panel'], solid=0.012)
    c = (ti + to + bi + bo) / 4
    ar = (to - ti).normalized() * side
    au = (ti - bi).normalized()
    n = ar.cross(au).normalized() if side > 0 else au.cross(ar).normalized() * -1
    if n.dot(E - c) < 0:
        n = -n
    hw = min((to - ti).length, (bo - bi).length) / k / 2
    return c, (ar, au, n), hw, (ti - bi).length / k / 2


# left: the threat warning prime / MISC / master arm facet from the shoulder down to the panel's foot
c, ax, hw, hh = facet('left_facet', -1, U_TOP - 0.125 * TRACE_S, U_TOP - 0.330 * TRACE_S)
print('MEASURE left facet half width', round(hw, 3))
for i, (du, h_) in enumerate(((0.62, 0.030), (0.15, 0.050), (-0.55, 0.034))):   # three switch plates, flush
    box(f'left_facet_plate_{i}', c + ax[1] * du * hh * k + ax[2] * 0.0015 * k, ax, (hw - 0.006, h_, 0.0015),
        M['console'], bevel=0.001)

# right: the engine instrument facet beside the right MFD: four gauges (FTIT, RPM, nozzle, oil) in a column
ENG_TOP, ENG_BOT = U_TOP - 0.120 * TRACE_S, U_TOP - 0.345 * TRACE_S
c, ax, hw, hh = facet('engine_facet', 1, ENG_TOP, ENG_BOT)
print('MEASURE engine facet half width', round(hw, 3))
g_r = min(0.022, hw - 0.004)
for i in range(4):
    q = c + ax[1] * (hh - (2 * i + 1) * hh / 4) * k + ax[2] * 0.004 * k
    cyl(f'engine_gauge_rim_{i}', q, ax[2], g_r, 0.004, M['grip'])
    cyl(f'engine_gauge_face_{i}', q + ax[2] * 0.0035 * k, ax[2], g_r - 0.004, 0.0012, M['black'])
# (the right aux panel is the sloping aux console below, by the knee)

# the centre column: narrow, from under the ICP down between the knees, flanked by the dark V-flanks that slope
# back to the MFDs' inner edges
COL_TOP = MFD_U - MFD_W / 2 - 0.004
col = [[panel_pt(r, u, 0.045) for r in (-0.098, -0.086, 0.086, 0.098)] for u in (COL_TOP, -0.66)]
for sg in (-1, 1):                   # standby airspeed (left) and altimeter (right) between the MFDs, recessed
    q = panel_pt(sg * 0.049 * TRACE_S, U_TOP - 0.209 * TRACE_S, 0.004)
    cyl(f'standby_rim_{sg}', q, pn, 0.040, 0.004, M['grip'])
    cyl(f'standby_face_{sg}', q + pn * 0.0045 * k, pn, 0.034, 0.0008, M['black'])
for row in col:
    row[0] = row[0] - pn * 0.025 * k; row[3] = row[3] - pn * 0.025 * k
sheet('centre_column', col, M['panel'], solid=0.035)
for sg in (-1, 1):
    top_in, bot_in = panel_pt(sg * 0.100, COL_TOP, 0.02), panel_pt(sg * 0.100, -0.66, 0.02)
    top_out, bot_out = panel_pt(sg * 0.112, COL_TOP, 0.0), panel_pt(sg * 0.150, -0.66, 0.0)
    rows = [[top_in, top_out], [bot_in, bot_out]] if sg > 0 else [[top_out, top_in], [bot_out, bot_in]]
    sheet(f'centre_vflank_{sg}', rows, M['coaming'], solid=0.008)
# the pedestal: leaning back 50 degrees between the knees, from the column's foot toward the floor
ped_top = panel_pt(0, -0.66, 0.045)
ped_ax_u = (U * math.cos(math.radians(50)) + F * math.sin(math.radians(50))).normalized()
ped_rows = [[ped_top + R * r * k for r in (-0.11, 0.11)],
            [ped_top - ped_ax_u * 0.16 * k + R * r * k for r in (-0.095, 0.095)]]
sheet('pedestal', ped_rows, M['panel'], solid=0.05)

# ======================================================================== glareshield
# from the panel's top edge forward to the windscreen base, a crowned surface under the glass; its lip stands
# 5 cm toward the pilot over the panel
lip, front = [], []
for r in [x / 10 for x in range(-4, 5)]:   # r: position across, as a share of 0.4
    w = min(reach(U_TOP, 1 if r >= 0 else -1, lift=0.05, clear=0.045), 0.40)
    rr = r / 0.4 * w                     # spread over the trimmed width (no bunched, doubled points)
    lip.append(panel_pt(rr, U_TOP, 0.05) + U * 0.015 * k)
rows = [lip]
for t in (0.35, 0.7, 1.0):
    row = []
    for p in lip:
        r = (p - E).dot(R) / k
        u0 = (p - E).dot(U) / k
        f0 = (p - E).dot(F) / k
        f = f0 + (ws_f - f0) * t
        u = u0 + 0.035 * math.sin(math.pi * t) * (1 - (r / 0.4) ** 2) - 0.02 * t
        g = glass_over(r, f, u - 0.3)
        if g is not None:
            u = min(u, g - 0.045)               # 4.5 cm under the windscreen
        row.append([r, u, f])
    # each half of the row shrinks evenly to what the fit allows at its outer end (no bunched points)
    for sgn in (-1, 1):
        half = [q for q in row if q[0] * sgn > 1e-6]
        if half:
            r_o, u_o, f_o = max(half, key=lambda q: abs(q[0]))
            w = reach(u_o, sgn, clear=0.035, pt=lambda x, u_o=u_o, f_o=f_o: P(x, u_o, f_o))
            s_ = min(1.0, w / abs(r_o))
            for q in half:
                q[0] *= s_
    rows.append([P(*q) for q in row])
sheet('glareshield', rows, M['coaming'], solid=0.015)
# its lip face down to the panel top
sheet('glareshield_lip', [lip, [panel_pt((p - E).dot(R) / k, U_TOP) for p in lip]], M['coaming'], solid=0.01)
# the glareshield's cheeks (ref. 1): black wedges from the lip's outer ends back along the canopy rails to above the
# side consoles, sloping down to the sill
for sg, end in ((1, lip[-1]), (-1, lip[0])):
    r_e = (end - E).dot(R) / k
    u_e = (end - E).dot(U) / k
    f_e = (end - E).dot(F) / k
    rows = []
    for t in (0.0, 0.5, 1.0):
        f = f_e + (0.30 - f_e) * t
        u_top = u_e - (u_e - (-0.42)) * t * 0.9
        w = min(wall(u_top, f, sg) - 0.05, abs(r_e) + 0.03)
        rows.append([P(sg * (w - 0.10), u_top - 0.01, f), P(sg * w, u_top, f), P(sg * w, u_top - 0.10, f)])
    # the cheeks crumple against the canopy rails once fitted to the hull, so they are left out: the hull's rail
    # already frames the coaming there
    # sheet(f'glareshield_cheek_{sg}', rows, M['coaming'], solid=0.012)

# ======================================================================== ICP tower and HUD
icp_c = P(0, -0.322, 0.625)
icp_n = (F * -math.cos(math.radians(12)) + U * math.sin(math.radians(12))).normalized()
icp_u = (U * math.cos(math.radians(12)) + F * math.sin(math.radians(12))).normalized()
box('icp_housing', icp_c - icp_n * 0.05 * k, (R, icp_u, icp_n), (0.083, 0.050, 0.05), M['panel'], bevel=0.008)
box('icp_face', icp_c + icp_n * 0.002 * k, (R, icp_u, icp_n), (0.077, 0.044, 0.003), M['console'], bevel=0.002)
for sg in (-1, 1):                     # the rocker-wheel housings either side of the keypad
    box(f'icp_rocker_{sg}', icp_c + R * sg * 0.064 * k + icp_n * 0.006 * k, (R, icp_u, icp_n), (0.007, 0.034, 0.006),
        M['grip'], bevel=0.003)
# the DED bezel immediately right of the ICP, on the same upfront face
ded_c = icp_c + R * 0.165 * k - icp_n * 0.012 * k
framed('ded', ded_c, (R, icp_u, icp_n), 0.118, 0.062, 0.03, 0.098, 0.040, M['panel'], recess=0.008)
# the HUD body sits on the glareshield over the ICP (the ICP housing is on its face); two dark side frames rise from
# it, leaning back 20 degrees, with the clear combiner glass held between them
hud_base = P(0, U_TOP + 0.040, 0.70)
box('hud_body', hud_base, (R, U, F), (0.085, 0.040, 0.075), M['hud'], bevel=0.012)
g_lean = math.radians(20)
g_up = (U * math.cos(g_lean) - F * math.sin(g_lean)).normalized()
g_bot = P(0, U_TOP + 0.080, 0.665)
def hud_clear(h):                                 # 3 cm under the windscreen at the frames' tops (and the middle)
    for r_ in (-0.085, 0.0, 0.085):
        top = g_bot + g_up * h * k + R * r_ * k
        hd = min([x for x in (hit(gl, top, U), hit(skin, top, U)) if x is not None], default=None)
        if hd is None or hd < 0.03 * k:
            return False
    return True


g_h = next((h / 100 for h in range(18, 7, -1) if hud_clear(h / 100)), 0.08)
g_n = R.cross(g_up).normalized()
for sg in (-1, 1):
    box(f'hud_frame_{sg}', g_bot + R * sg * 0.074 * k + g_up * g_h / 2 * k, (R, g_up, g_n), (0.008, g_h / 2, 0.016),
        M['hud'], bevel=0.003)
box('hud_frame_top', g_bot + g_up * (g_h - 0.006) * k, (R, g_up, g_n), (0.082, 0.006, 0.010), M['hud'], bevel=0.002)
box('hud_combiner', g_bot + g_up * g_h / 2 * k, (R, g_up, g_n), (0.066, g_h / 2 - 0.006, 0.0015), M['hudglass'],
    bevel=0.0)
print('MEASURE combiner height', round(g_h, 3))
# the AOA indexer (left) and the AR status / NWS indicator (right): small upright posts on the glareshield either
# side of the HUD (xflight.de arrangement drawing)
for sg, nm in ((-1, 'aoa_indexer'), (1, 'ar_status_indicator')):
    pc = P(sg * 0.135, U_TOP + 0.040, 0.715)
    box(nm, pc, (R, U, F), (0.013, 0.040, 0.013), M['hud'], bevel=0.003)
    box(f'{nm}_lamps', pc - F * 0.0135 * k + U * 0.008 * k, (R, U, F), (0.009, 0.026, 0.001), M['black'], bevel=0.0)
# the eyebrow lamp strips (master caution / threat warning on the left, the warning lamps on the right): slim
# housings along the panel's sloping shoulders, as on the traced drawing
for sg in (-1, 1):
    a_ = panel_pt(sg * 0.246 * TRACE_S, U_TOP - 0.014 * TRACE_S, 0.006)
    b_ = panel_pt(sg * 0.336 * TRACE_S, U_TOP - 0.108 * TRACE_S, 0.006)
    ax_ = (b_ - a_).normalized()
    box(f'eyebrow_{sg}', (a_ + b_) / 2, (ax_, ax_.cross(pn).normalized(), pn), ((b_ - a_).length / k / 2, 0.010, 0.005),
        M['coaming'], bevel=0.002)

# ======================================================================== tub, consoles, aux consoles
CON_U, CON_R0 = -0.76, 0.20
for sg in (-1, 1):
    st = [-0.40 + 0.11 * i for i in range(10)]
    outer = [min(wall(CON_U + 0.02, f, sg) - 0.012, 0.42) for f in st]
    top = [[P(sg * CON_R0, CON_U, f) for f in st], [P(sg * w, CON_U, f) for w, f in zip(outer, st)]]
    sheet(f'console_top_{sg}', top, M['console'], solid=0.012)
    sheet(f'console_side_{sg}', [[P(sg * CON_R0, CON_U, f) for f in st], [P(sg * CON_R0, -1.12, f) for f in st]],
          M['tub_d'], solid=0.01)
    sill = [min(wall(-0.40, f, sg) - 0.01, 0.46) for f in st]
    sheet(f'wall_{sg}', [[P(sg * w, CON_U, f) for w, f in zip(outer, st)], [P(sg * w, -0.40, f) for w, f in zip(sill, st)]],
          M['tub'], solid=0.01)
    # aux console: the 45 degree panel at the console's front, by the knee (refs. 1, 2)
    # aux console (left: the landing gear panel; right: the right aux panel): the sloping panel from the main
    # panel's foot down to the side console's front, by the knee (refs. 1, 2, p-013); laid inside the fit
    edge = []
    for t_ in (0.0, 0.5, 1.0):
        u_, f_ = -0.648 - 0.105 * t_, 0.742 - 0.150 * t_
        w_out = reach(u_, sg, clear=0.045, pt=lambda r, u_=u_, f_=f_: P(r, u_, f_))
        edge.append([P(sg * x, u_, f_) for x in (0.205, (0.205 + min(w_out, 0.40)) / 2, min(w_out, 0.40))])
    nm = 'gear_panel' if sg < 0 else 'right_aux_panel'
    sheet(nm, edge if sg > 0 else [r_[::-1] for r_ in edge], M['panel'], solid=0.02)
    mid = edge[1][1]
    a_up = (edge[0][1] - edge[2][1]).normalized()
    a_n = R.cross(a_up).normalized()
    if a_n.dot(E - mid) < 0:
        a_n = -a_n
    if sg < 0:                           # the gear handle: a wheel-shaped knob on a short lever, outboard
        hb = edge[1][2].lerp(edge[1][1], 0.45)
        cyl('gear_handle_base', hb + a_n * 0.006 * k, a_n, 0.014, 0.006, M['grip'])
        box('gear_handle_lever', hb + a_n * 0.030 * k + a_up * 0.012 * k, (R, a_up, a_n), (0.006, 0.006, 0.024), M['metal'],
            bevel=0.002)
        cyl('gear_handle_knob', hb + a_n * 0.056 * k + a_up * 0.012 * k, R, 0.016, 0.008, M['black'])
    # sill ledge
    box(f'sill_{sg}', P(sg * (min(sill) - 0.02), -0.39, 0.10), (R, U, F), (0.04, 0.012, 0.52), M['tub'])
sheet('floor', [[P(r, -1.12, f) for r in (-0.22, 0.22)] for f in (-0.42, 1.10)], M['floor'], solid=0.01)
# ======================================================================== behind the seat
# the aft bulkhead from the floor up to the foot of the model's seat-back block (kept in the hull, 0.43 m behind the
# eye), the deck from there back under the rear canopy to the canopy hinge, the block's open inside closed off, and
# the canopy actuator on the deck, so nothing open shows through the canopy
F_BH, U_DECK = -0.422, -0.255


def span(u, f, side, cap=0.45, clear=0.03):
    return min(cap, reach(u, side, clear=clear, pt=lambda r: P(r, u, f)))


rows = []
for u in (-1.12, -0.95, -0.80, -0.65, -0.52, -0.40, -0.32, U_DECK):
    w = min(span(u, F_BH, 1), span(u, F_BH, -1))
    rows.append([P(x * w, u, F_BH) for x in (-1, -0.5, 0, 0.5, 1)])
sheet('rear_bulkhead', rows, M['tub'], solid=0.01)
rows = []
for f in (F_BH, -0.52, -0.62, -0.72, -0.82, -0.92):
    w = min(span(U_DECK, f, 1), span(U_DECK, f, -1))
    rows.append([P(x * w, U_DECK, f) for x in (-1, -0.6, -0.2, 0.2, 0.6, 1)])
sheet('aft_deck', rows[::-1], M['tub_d'], solid=0.01)
print('MEASURE aft deck half widths', [round((r_[-1] - E).dot(R) / k, 3) for r_ in rows])
# the seat-back block's open inside: a back plate at its rear face, under its top beam
sheet('headbox_back', [[P(x, u, -0.632) for x in (-0.25, 0.0, 0.25)] for u in (U_DECK, -0.05, 0.135)], M['headbox'],
      solid=0.01)
# the canopy actuator: a strut from the deck behind the seat up and aft to the hinge under the canopy's aft frame
a0, a1 = P(0, U_DECK + 0.02, -0.70), P(0, -0.02, -0.90)
cyl('canopy_actuator', (a0 + a1) / 2, a1 - a0, 0.022, (a1 - a0).length / k / 2, M['metal'])
cyl('canopy_actuator_rod', a1 + (a1 - a0).normalized() * 0.012 * k, a1 - a0, 0.011, 0.015, M['metal'])
box('canopy_actuator_mount', a0, (R, U, F), (0.05, 0.015, 0.04), M['tub_d'], bevel=0.004)

# ======================================================================== side-stick, throttle, pedals, ACES II seat
import f16c_controls
f16c_controls.build(dict(P=P, R=R, U=U, F=F, E=E, k=k, M=M, box=box, cyl=cyl, obj=obj, CON_U=CON_U))

# ======================================================================== keep inside: every vertex inside the fuselage
# (or under the canopy glass), at least MARGIN from the skin; a vertex that is not is moved toward the cockpit's
# centre line at its own height and station until it is
MARGIN = 0.015
TUB_PARTS = ('wall_', 'sill_', 'floor', 'rear_bulkhead', 'aft_deck', 'headbox_back', 'console_side_', 'pedal_')
EDGE_PARTS = ('panel_board', 'left_facet', 'engine_facet', 'gear_panel', 'right_aux_panel',
              'mfd_hood')                                                              # parts that meet the walls


def inside(p):
    """inside the airframe: sideways both ways the skin or the glass, and upward the glass or the skin"""
    for d_ in (R, -R):
        hs, hg = hit(skin, p, d_), hit(gl, p, d_)
        if hs is None and hg is None:
            return False
        if hs is not None and hs < MARGIN * k and (hg is None or hs < hg):
            return False
    if hit(gl, p, U) is None and hit(skin, p, U) is None:
        return False
    hs = hit(skin, p, -U)
    if hs is None or hs < MARGIN * k:
        return False
    return True


moved = 0
bpy.context.view_layer.update()
for o in cockpit:
    for m_ in list(o.modifiers):                 # apply the bevels and thicknesses first
        bpy.context.view_layer.objects.active = o
        bpy.ops.object.modifier_apply(modifier=m_.name)
    # large faces are cut up first so the fit can follow a curving skin (a face spanning a hollow would cross it)
    bm = bmesh.new(); bm.from_mesh(o.data)
    lim = (0.04 if o.name.startswith(TUB_PARTS) else 0.08 if o.name.startswith(EDGE_PARTS + ('glareshield', 'aft_', 'gear_panel', 'right_aux')) else 99) * k
    for _ in range(2):
        long_edges = [e for e in bm.edges if e.calc_length() > lim]
        if long_edges:
            bmesh.ops.subdivide_edges(bm, edges=long_edges, cuts=1, use_grid_fill=True)
    bm.to_mesh(o.data); bm.free()
    marg = MARGIN if o.name.startswith(TUB_PARTS) else 0.045 if o.name.startswith(EDGE_PARTS) else 0.03
    for v in [v for _ in range(2) for v in o.data.vertices]:
        p = o.matrix_world @ v.co
        # line of sight from the cockpit's centre line (at this station, half a metre under the eye): the first
        # surface of the model or the glass on the way to the vertex is the cockpit's boundary there; a vertex
        # beyond it, or within MARGIN of it, comes back to MARGIN inside it
        rel = p - E
        anchor = E + U * (-0.5 * k) + F * rel.dot(F)
        dvec = p - anchor
        L_ = dvec.length
        if L_ < 1e-6:
            continue
        dn = dvec / L_
        GM = max(marg, 0.036)                    # the glass keeps at least 3 cm (and a bit) clear everywhere
        hs = hit(skin, anchor, dn, far=L_ + marg * k)
        hg = hit(gl, anchor, dn, far=L_ + GM * k)
        stops = [h - m_ * k for h, m_ in ((hs, marg), (hg, GM)) if h is not None]
        if stops and min(stops) < L_:
            q = anchor + dn * max(0.0, min(stops))
            v.co = o.matrix_world.inverted() @ q
            moved += 1
print('KEEP_INSIDE moved', moved)
# faces that still cross the hull (the hull bulges in between two fitted vertices, e.g. at the canopy rails):
# their vertices step back toward the centre line until nothing crosses
nudged = 0
for o in cockpit:
    if o.name.startswith(TUB_PARTS):
        continue
    for _ in range(12):
        bpy.context.view_layer.update()
        bad = {a for a, b_ in B.bvh_of([o]).overlap(skin)}
        if not bad:
            break
        vids = {i for f in bad if f < len(o.data.polygons) for i in o.data.polygons[f].vertices}
        for i in vids:
            v = o.data.vertices[i]
            p = o.matrix_world @ v.co
            anchor = E + U * (-0.5 * k) + F * (p - E).dot(F)
            d_ = p - anchor
            if d_.length > 0.02 * k:
                v.co = o.matrix_world.inverted() @ (p - d_.normalized() * 0.015 * k)
                nudged += 1
print('NUDGED', nudged)
# and every vertex at least 3 cm from the canopy glass (nearest distance, any direction)
glass_moved = 0
for o in cockpit:
    for v in o.data.vertices:
        for _ in range(10):
            p = o.matrix_world @ v.co
            loc_, n_, i_, dist_ = gl.find_nearest(p)
            if loc_ is None or dist_ >= 0.032 * k:
                break
            anchor = E + U * (-0.5 * k) + F * (p - E).dot(F)
            d_ = p - anchor
            if d_.length < 0.02 * k:
                break
            v.co = o.matrix_world.inverted() @ (p - d_.normalized() * (0.034 * k - dist_ + 0.002 * k))
            glass_moved += 1
print('GLASS_CLEAR_MOVED', glass_moved)
# tub lining faces that still cross the hull go: the hull's own surface is right behind them
removed = 0
for o in cockpit:
    if not o.name.startswith(TUB_PARTS):
        continue
    bpy.context.view_layer.update()
    ob = B.bvh_of([o])
    bad = {a for a, b_ in ob.overlap(skin)}
    if bad:
        bm = bmesh.new(); bm.from_mesh(o.data); bm.faces.ensure_lookup_table()
        bmesh.ops.delete(bm, geom=[bm.faces[i] for i in bad if i < len(bm.faces)], context='FACES')
        bm.to_mesh(o.data); bm.free()
        removed += len(bad)
print('TUB_FACES_REMOVED', removed)

# ======================================================================== check: no clipping
bpy.context.view_layer.update()
dg = bpy.context.evaluated_depsgraph_get()
problems = []
inside_fail, glass_fail = [], []
clip_parts = {}
model_bvh = {m_.name: B.bvh_of([m_]) for m_ in model}
for o in cockpit:
    ob = B.bvh_of([o])
    n = len(ob.overlap(skin))
    if n:
        clip_parts[o.name] = {mn: len(ob.overlap(mb)) for mn, mb in model_bvh.items() if ob.overlap(mb)}
pairs = [0] * sum(sum(v.values()) for v in clip_parts.values())
for o in cockpit:
    eo = o.evaluated_get(dg); me = eo.to_mesh()
    for v in me.vertices:
        p = o.matrix_world @ v.co
        # inside the fuselage: rays left and right both meet the skin or the canopy glass
        if (hit(skin, p, R) is None and hit(gl, p, R) is None) or (hit(skin, p, -R) is None and hit(gl, p, -R) is None):
            inside_fail.append((o.name, [round(x, 3) for x in p]))
        g = hit(gl, p, U)
        s_up_hit = hit(skin, p, U)
        if g is None and s_up_hit is None and (p - E).dot(U) / k > -0.5:
            glass_fail.append((o.name, [round(x, 3) for x in p]))
    eo.to_mesh_clear()
check = dict(face_pairs_touching_fuselage=len(pairs), clipping_parts=clip_parts, vertices_outside_walls=len(inside_fail),
             vertices_not_under_canopy=len(glass_fail), examples_outside=inside_fail[:8], examples_open=glass_fail[:8],
             parts=sorted({o.name for o in cockpit}))
json.dump(check, open(os.path.join(out, 'check.json'), 'w'), indent=1)
print('CHECK', json.dumps({k_: v for k_, v in check.items() if k_ != 'parts'}))

# ======================================================================== save and render
# cockpit lighting for the previews: a soft area light over the pilot's shoulder and a fill from the panel, so
# the forms read (the game lights by its own rules)
for nm, loc, energy, size in (('key', P(0.15, 0.25, -0.15), 6, 0.6), ('fill', P(0, 0.15, 0.05), 0.6, 0.5)):
    ld = bpy.data.lights.new(nm, 'AREA'); ld.energy = energy * k * k; ld.size = size * k
    lo = bpy.data.objects.new(nm, ld); sc.collection.objects.link(lo)
    lo.location = loc; B.look_at(lo, P(0, -0.5, 0.75))
try:
    sc.eevee.use_gtao = True
except Exception:
    pass
bpy.ops.wm.save_as_mainfile(filepath=os.path.join(out, 'structure.blend'))
views = dict(seat=(E, P(0, -0.35, 1.0), 14), panel=(P(0, -0.05, 0.05), P(0, -0.42, 0.8), 22),
             pilot_eye=(E, P(0, -0.40, 0.80), 24),         # a normal field of view (about 74 degrees across)
             left=(P(0.15, 0.05, -0.05), P(-0.30, -0.76, 0.35), 16), right=(P(-0.15, 0.05, -0.05), P(0.30, -0.76, 0.35), 16),
             panel_front=(P(0, -0.40, 0.30), P(0, -0.45, 0.80), 22),
             seat_back=(P(0, -0.08, 0.45), P(0, -0.45, -0.1), 14), outside=(E + B.g2b([2.0, 1.2, 3.0]),
                                                                           P(0, -0.2, 0.2), 30))
for name, (loc, tgt, lens) in views.items():
    B.render_view(os.path.join(out, f'{name}.png'), loc, tgt, lens=lens, w=1280, h=800)
print('DONE')
