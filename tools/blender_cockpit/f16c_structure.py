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
         hud=mat('hud', (40, 42, 44), 0.5), glassplate=mat('glassplate', (120, 160, 150), 0.1))


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
rows = []
for u in (-0.64, -0.56, -0.48, -0.40, -0.33, U_TOP):
    fu = F_PANEL + (u + 0.45) * math.tan(lean)
    w = min(wall(u, fu, 1), wall(u, fu, -1)) - 0.035
    w = min(w, 0.38 if u > -0.58 else 0.30)              # the board narrows over the knees (ref. 1)
    rows.append([panel_pt(r * w, u) for r in (-1.0, -0.62, -0.30, 0.30, 0.62, 1.0)])
sheet('panel_board', rows[::-1], M['panel'], solid=0.025)


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


# the two MFD bays (ref. 1, 2): square bezels 0.20 m with the screen opening, each under its own black hood
for sg in (-1, 1):
    housing(f'mfd_bezel_{sg}', sg * 0.162, -0.50, 0.20, 0.20, 0.022, 0.13, 0.13)
    hood_c = panel_pt(sg * 0.162, -0.50 + 0.10) + pn * 0.035 * k
    box(f'mfd_hood_{sg}', hood_c, (R, up15, pn), (0.104, 0.004, 0.035), M['coaming'], bevel=0.002)
    for s2 in (-1, 1):
        p0 = panel_pt(sg * 0.162 + s2 * 0.102, -0.50 - 0.09); p1 = panel_pt(sg * 0.162 + s2 * 0.102, -0.50 + 0.10)
        bm = bmesh.new()
        vs = [bm.verts.new(q) for q in (p0, p1, p1 + pn * 0.07 * k, p0 + pn * 0.012 * k)]
        bm.faces.new(vs)
        obj(f'mfd_hood_side_{sg}_{s2}', bm, M['coaming'], solid=0.004)
# upper left: the RWR scope housing and the threat-warning lamp strip beside it; upper right: the DED bezel
housing('rwr_bezel', -0.165, -0.36, 0.105, 0.105, 0.018, 0.075, 0.075)
housing('twp_box', -0.250, -0.36, 0.045, 0.085, 0.015)
housing('ded_bezel', 0.158, -0.336, 0.125, 0.065, 0.016, 0.105, 0.046)
# the centre instrument tower: from under the ICP down to the pedestal, 4 cm proud, its sides chamfered
col = [[panel_pt(r, u, 0.04) for r in (-0.09, -0.07, 0.07, 0.09)] for u in (-0.37, -0.64)]
for row in col:
    row[0] = row[0] - pn * 0.03 * k; row[3] = row[3] - pn * 0.03 * k
sheet('centre_tower', col, M['panel'], solid=0.035)
# the pedestal: leaning back 50 degrees between the knees, from the tower's foot toward the floor
ped_top = panel_pt(0, -0.64, 0.04)
ped_ax_u = (U * math.cos(math.radians(50)) + F * math.sin(math.radians(50))).normalized()
ped_rows = [[ped_top + R * r * k for r in (-0.13, 0.13)],
            [ped_top - ped_ax_u * 0.16 * k + R * r * k for r in (-0.11, 0.11)]]
sheet('pedestal', ped_rows, M['panel'], solid=0.05)
# the right engine column and the left arming-switch column stand as shallow strips on the board's edges
for sg, nm in ((1, 'engine_column'), (-1, 'arming_column')):
    housing(nm, sg * 0.30, -0.47, 0.075, 0.30, 0.012)

# ======================================================================== glareshield
# from the panel's top edge forward to the windscreen base, a crowned surface under the glass; its lip stands
# 5 cm toward the pilot over the panel
lip, front = [], []
for r in [x / 10 for x in range(-4, 5)]:
    w = min(wall(U_TOP + 0.02, F_PANEL - 0.05, 1 if r >= 0 else -1) - 0.035, 0.40)
    rr = max(-w, min(w, r))
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
            u = min(u, g - 0.025)
        w = wall(u, f, 1 if r >= 0 else -1) - 0.035
        row.append(P(max(-w, min(w, r)), u, f))
    rows.append(row)
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
box('icp_housing', icp_c - icp_n * 0.05 * k, (R, icp_u, icp_n), (0.106, 0.058, 0.05), M['panel'], bevel=0.008)
box('icp_face', icp_c + icp_n * 0.002 * k, (R, icp_u, icp_n), (0.098, 0.052, 0.003), M['console'], bevel=0.002)
for sg in (-1, 1):                     # the rocker-wheel housings either side of the keypad
    box(f'icp_rocker_{sg}', icp_c + R * sg * 0.083 * k + icp_n * 0.006 * k, (R, icp_u, icp_n), (0.008, 0.04, 0.006),
        M['grip'], bevel=0.003)
hud_base = P(0, U_TOP + 0.045, 0.74)
box('hud_body', hud_base, (R, U, F), (0.07, 0.045, 0.09), M['hud'], bevel=0.02)
# combiner: two side posts leaning back 20 degrees and the combiner plate between them, top at eye height
g_lean = math.radians(20)
g_up = (U * math.cos(g_lean) - F * math.sin(g_lean)).normalized()
g_bot = P(0, U_TOP + 0.10, 0.70)
g_h = 0.15
gtop = glass_over(0, 0.66, U_TOP)
if gtop is not None:
    g_h = min(g_h, (gtop - 0.03 - (U_TOP + 0.10)) / g_up.dot(U))
for sg in (-1, 1):
    box(f'hud_post_{sg}', g_bot + R * sg * 0.072 * k + g_up * g_h / 2 * k, (R, g_up, R.cross(g_up)), (0.006, g_h / 2, 0.008),
        M['hud'], bevel=0.002)
box('hud_combiner', g_bot + g_up * g_h / 2 * k, (R, g_up, R.cross(g_up)), (0.066, g_h / 2, 0.002), M['glassplate'],
    bevel=0.0)
# eyebrow light housings on the glareshield corners (ref. 2)
for sg, w in ((-1, 0.05), (1, 0.08)):
    box(f'eyebrow_{sg}', P(sg * 0.238, -0.262, 0.73), (R, U, F), (w / 2 + 0.008, 0.035, 0.03), M['coaming'])

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
    a_c = P(sg * 0.30, -0.70, 0.56)
    a_up = (U * math.cos(math.radians(45)) + F * math.sin(math.radians(45))).normalized()
    a_r = (R * math.cos(math.radians(10)) + F * sg * math.sin(math.radians(10))).normalized()
    box(f'aux_console_{sg}', a_c, (a_r, a_up, a_r.cross(a_up)), (0.09, 0.10, 0.025), M['panel'], bevel=0.004)
    # sill ledge
    box(f'sill_{sg}', P(sg * (min(sill) - 0.02), -0.39, 0.10), (R, U, F), (0.04, 0.012, 0.52), M['tub'])
sheet('floor', [[P(r, -1.12, f) for r in (-0.22, 0.22)] for f in (-0.42, 1.10)], M['floor'], solid=0.01)
sheet('rear_bulkhead', [[P(r, u, -0.44) for r in (-0.40, 0.40)] for u in (-1.12, -0.40)], M['tub'], solid=0.01)

# ======================================================================== side-stick, throttle, pedals
box('stick_armrest', P(0.29, CON_U + 0.045, 0.28), (R, U, F), (0.045, 0.045, 0.15), M['tub_d'], bevel=0.008)
box('stick_pad', P(0.29, CON_U + 0.092, 0.26), (R, U, F), (0.04, 0.006, 0.12), M['cushion'], bevel=0.004)
s_ax = (U * math.cos(math.radians(13)) + F * math.sin(math.radians(13))).normalized()
box('stick_base', P(0.29, CON_U + 0.06, 0.46), (R, U, F), (0.03, 0.012, 0.035), M['grip'])
box('stick_grip', P(0.29, CON_U + 0.06, 0.46) + s_ax * 0.075 * k, (R, s_ax, R.cross(s_ax)), (0.019, 0.058, 0.026),
    M['grip'], bevel=0.008)
box('throttle_quadrant', P(-0.30, CON_U + 0.012, 0.26), (R, U, F), (0.03, 0.012, 0.11), M['grip'])
t_ax = (U * math.cos(math.radians(15)) + F * math.sin(math.radians(15))).normalized()
box('throttle_grip', P(-0.30, CON_U + 0.10, 0.295), (R, t_ax, R.cross(t_ax)), (0.032, 0.035, 0.055), M['grip'],
    bevel=0.01)
for sg in (-1, 1):
    p_ax = (U * math.cos(math.radians(-25)) + F * math.sin(math.radians(-25))).normalized()
    box(f'pedal_{sg}', P(sg * 0.10, -0.92, 0.96), (R, p_ax, R.cross(p_ax)), (0.040, 0.065, 0.012), M['metal'])

# ======================================================================== ACES II seat, reclined 30 degrees
rec = math.radians(30)
s_up = (U * math.cos(rec) - F * math.sin(rec)).normalized()
s_fw = s_up.cross(R).normalized() * -1
if s_fw.dot(F) < 0:
    s_fw = -s_fw
hip = P(0, -0.72, 0.20)
box('seat_bucket', hip + F * 0.20 * k - U * 0.08 * k, (R, U, F), (0.20, 0.07, 0.22), M['seat'], bevel=0.01)
box('seat_pan', hip + F * 0.20 * k, (R, U, F), (0.19, 0.03, 0.21), M['cushion'], bevel=0.012)
back = hip + s_up * 0.36 * k - s_fw * 0.06 * k
box('seat_back_cushion', back, (R, s_up, s_fw), (0.19, 0.34, 0.035), M['cushion'], bevel=0.012)
box('seat_back_shell', back - s_fw * 0.06 * k, (R, s_up, s_fw), (0.21, 0.38, 0.03), M['seat'], bevel=0.008)
for sg in (-1, 1):
    box(f'seat_rail_{sg}', back - s_fw * 0.09 * k + R * sg * 0.16 * k + s_up * 0.12 * k, (R, s_up, s_fw),
        (0.02, 0.56, 0.025), M['metal'], bevel=0.004)
    box(f'seat_side_{sg}', hip + R * sg * 0.215 * k + F * 0.16 * k + U * 0.04 * k, (R, U, F), (0.012, 0.12, 0.20),
        M['seat'], bevel=0.004)
head = hip + s_up * 0.82 * k - s_fw * 0.09 * k
box('seat_headbox', head, (R, s_up, s_fw), (0.15, 0.13, 0.09), M['headbox'], bevel=0.02)
box('seat_headpad', head + s_fw * 0.095 * k, (R, s_up, s_fw), (0.10, 0.09, 0.012), M['cushion'], bevel=0.008)
box('seat_handle', hip + F * 0.43 * k + U * 0.06 * k, (R, U, F), (0.058, 0.03, 0.008), M['yellow'], bevel=0.004)

# ======================================================================== keep inside: every vertex inside the fuselage
# (or under the canopy glass), at least MARGIN from the skin; a vertex that is not is moved toward the cockpit's
# centre line at its own height and station until it is
MARGIN = 0.015
TUB_PARTS = ('wall_', 'sill_', 'floor', 'rear_bulkhead', 'console_side_', 'pedal_')
EDGE_PARTS = ('panel_board', 'engine_column', 'arming_column', 'glareshield_cheek')   # parts that meet the walls


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
    lim = (0.04 if o.name.startswith(TUB_PARTS) else 0.08) * k
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
        hs = hit(skin, anchor, dn, far=L_ + marg * k)
        hg = hit(gl, anchor, dn, far=L_ + marg * k)
        hmin = min([h for h in (hs, hg) if h is not None], default=None)
        if hmin is not None and hmin < L_ + marg * k:
            q = anchor + dn * max(0.0, hmin - marg * k)
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
for nm, loc, energy, size in (('key', P(0.15, 0.25, -0.15), 6, 0.6), ('fill', P(0, -0.2, 0.45), 1.5, 0.4)):
    ld = bpy.data.lights.new(nm, 'AREA'); ld.energy = energy * k * k; ld.size = size * k
    lo = bpy.data.objects.new(nm, ld); sc.collection.objects.link(lo)
    lo.location = loc; B.look_at(lo, P(0, -0.5, 0.75))
try:
    sc.eevee.use_gtao = True
except Exception:
    pass
bpy.ops.wm.save_as_mainfile(filepath=os.path.join(out, 'structure.blend'))
views = dict(seat=(E, P(0, -0.35, 1.0), 14), panel=(P(0, -0.05, 0.05), P(0, -0.42, 0.8), 22),
             left=(E, P(-0.6, -0.7, 0.4), 14), right=(E, P(0.6, -0.7, 0.4), 14),
             seat_back=(P(0, -0.08, 0.45), P(0, -0.45, -0.1), 14), outside=(E + B.g2b([2.0, 1.2, 3.0]),
                                                                           P(0, -0.2, 0.2), 30))
for name, (loc, tgt, lens) in views.items():
    B.render_view(os.path.join(out, f'{name}.png'), loc, tgt, lens=lens, w=1280, h=800)
print('DONE')
