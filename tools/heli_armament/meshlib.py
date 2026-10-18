#!/usr/bin/env python3
"""Small procedural mesh kit for the helicopter stores: launchers, pods, rails and munitions built from lathed
bodies, tubes, boxes and fins at their real dimensions, textured from one flat-colour palette.

Frame: the store's own vehicle-local frame in blocks (1 block = 1 m): x left, y up, z forward (nose +z), origin at
the point that touches the pylon (lug top). Faces are wound counter-clockwise seen from outside, which the mesh
loader (it mirrors X: loader = (-x, y, -z), a pure rotation) reads as outward (tools/munition_models/fix_winding.py).

A model file stores positions in pixels as (x, y, -z) * 16; the store JSON then uses ModelForward "-Z" with
MountAnchor [0, 0, 0], and a point q of this frame is written in the anchor frame as (-q.x, q.y, -q.z)
(see anchor()). Flight models (nose up the +Y axis, tail at the origin) come from flight_geo().
"""
import json, math, os
import numpy as np

# ------------------------------------------------------------------------------------------------ palette
# 8 x 8 cells of 8 px on a 64 x 64 texture; every face samples the centre of its colour's cell.
PALETTE = {
    'olive': (78, 84, 52), 'olive_dark': (58, 63, 40), 'us_green': (66, 76, 58), 'us_gray': (110, 116, 112),
    'ru_green': (88, 102, 78), 'ru_green_dark': (64, 76, 58), 'ru_gray': (120, 124, 118), 'ru_blue_gray': (106, 116, 124),
    'fr_green': (82, 90, 70), 'fr_gray': (100, 106, 104), 'sand': (176, 156, 112), 'tan': (150, 132, 96),
    'black': (24, 24, 26), 'dark': (44, 46, 48), 'steel': (96, 98, 102), 'aluminium': (168, 170, 172),
    'white': (226, 226, 220), 'off_white': (200, 198, 186), 'light_gray': (160, 162, 160), 'gray': (128, 130, 128),
    'yellow': (214, 178, 42), 'brown': (120, 78, 42), 'red': (168, 38, 30), 'orange': (204, 108, 32),
    'blue': (40, 64, 120), 'glass': (40, 52, 66), 'seeker': (70, 30, 24), 'copper': (150, 96, 60),
    'bomb_green': (84, 96, 70), 'bomb_gray': (112, 118, 116), 'soot': (34, 32, 30), 'tube_inner': (30, 30, 30),
}
TEXTURE_SIZE = 64
CELL = 8
_NAMES = list(PALETTE)


def cell_uv(colour):
    i = _NAMES.index(colour)
    cx, cy = (i % 8) * CELL + CELL / 2, (i // 8) * CELL + CELL / 2
    return (cx / TEXTURE_SIZE, 1.0 - cy / TEXTURE_SIZE)          # normalized, v up


def palette_image():
    from PIL import Image
    im = Image.new('RGBA', (TEXTURE_SIZE, TEXTURE_SIZE), (0, 0, 0, 0))
    px = im.load()
    for i, name in enumerate(_NAMES):
        r, g, b = PALETTE[name]
        for y in range(CELL):
            for x in range(CELL):
                px[(i % 8) * CELL + x, (i // 8) * CELL + y] = (r, g, b, 255)
    return im


# ------------------------------------------------------------------------------------------------ mesh
class Mesh:
    def __init__(self):
        self.v = []          # [x, y, z] local blocks
        self.f = []          # (indices, colour)

    def vert(self, p):
        self.v.append([float(p[0]), float(p[1]), float(p[2])])
        return len(self.v) - 1

    def face(self, pts, colour):
        """pts counter-clockwise seen from outside"""
        self.f.append(([self.vert(p) for p in pts], colour))

    def add(self, other):
        base = len(self.v)
        self.v += [list(p) for p in other.v]
        self.f += [([i + base for i in idx], c) for idx, c in other.f]
        return self

    def copy(self):
        return Mesh().add(self)

    def transformed(self, R=None, t=(0, 0, 0), mirror_x=False):
        out = self.copy()
        P = np.array(out.v) if out.v else np.zeros((0, 3))
        if R is not None:
            P = P @ np.array(R, float).T
        if mirror_x:
            P[:, 0] = -P[:, 0]
            out.f = [(list(reversed(idx)), c) for idx, c in out.f]
        P = P + np.array(t, float)
        out.v = P.tolist()
        return out

    def moved(self, t):
        return self.transformed(t=t)

    def bounds(self):
        P = np.array(self.v)
        return P.min(0), P.max(0)

    def signed_volume(self):
        P = np.array(self.v); total = 0.0
        for idx, _ in self.f:
            a = P[idx[0]]
            for k in range(1, len(idx) - 1):
                total += np.dot(a, np.cross(P[idx[k]], P[idx[k + 1]]))
        return total / 6.0


def rot_x(deg):
    c, s = math.cos(math.radians(deg)), math.sin(math.radians(deg))
    return [[1, 0, 0], [0, c, -s], [0, s, c]]


def rot_y(deg):
    c, s = math.cos(math.radians(deg)), math.sin(math.radians(deg))
    return [[c, 0, s], [0, 1, 0], [-s, 0, c]]


def rot_z(deg):
    c, s = math.cos(math.radians(deg)), math.sin(math.radians(deg))
    return [[c, -s, 0], [s, c, 0], [0, 0, 1]]


# ------------------------------------------------------------------------------------------------ primitives
def box(lo, hi, colour, m=None):
    m = m or Mesh()
    (x0, y0, z0), (x1, y1, z1) = lo, hi
    c = lambda x, y, z: (x, y, z)
    # each face CCW seen from outside (x left, y up, z forward is right-handed)
    m.face([c(x1, y0, z0), c(x1, y1, z0), c(x1, y1, z1), c(x1, y0, z1)], colour)   # +x
    m.face([c(x0, y0, z0), c(x0, y0, z1), c(x0, y1, z1), c(x0, y1, z0)], colour)   # -x
    m.face([c(x0, y1, z0), c(x0, y1, z1), c(x1, y1, z1), c(x1, y1, z0)], colour)   # +y
    m.face([c(x0, y0, z0), c(x1, y0, z0), c(x1, y0, z1), c(x0, y0, z1)], colour)   # -y
    m.face([c(x0, y0, z1), c(x1, y0, z1), c(x1, y1, z1), c(x0, y1, z1)], colour)   # +z
    m.face([c(x0, y0, z0), c(x0, y1, z0), c(x1, y1, z0), c(x1, y0, z0)], colour)   # -z
    return m


def cbox(centre, size, colour, m=None):
    c, s = np.array(centre, float), np.array(size, float) / 2
    return box(c - s, c + s, colour, m)


def _ring(cx, cy, z, r, n, phase):
    return [(cx + r * math.cos(phase + 2 * math.pi * k / n), cy + r * math.sin(phase + 2 * math.pi * k / n), z)
            for k in range(n)]


def lathe(profile, colours=None, centre=(0.0, 0.0), n=12, phase=None, m=None, cap_front=True, cap_back=True):
    """Body of revolution about the z axis through centre. profile: [(z, r)] from tail to nose; a point with r = 0
    closes to a tip. colours: one colour per segment (or a single colour)."""
    m = m or Mesh()
    phase = math.pi / n if phase is None else phase
    cx, cy = centre
    segs = len(profile) - 1
    if isinstance(colours, str) or colours is None:
        colours = [colours or 'gray'] * segs
    rings = [_ring(cx, cy, z, r, n, phase) if r > 0 else None for z, r in profile]
    for s in range(segs):
        (z0, r0), (z1, r1) = profile[s], profile[s + 1]
        col = colours[s]
        if r0 <= 0 and r1 <= 0:
            continue
        for k in range(n):
            k1 = (k + 1) % n
            if r0 <= 0:
                m.face([(cx, cy, z0), rings[s + 1][k1], rings[s + 1][k]], col)
            elif r1 <= 0:
                m.face([rings[s][k], rings[s][k1], (cx, cy, z1)], col)
            else:
                # outward for z increasing: (k, k1) around +z is CCW seen from +z, so walk k -> k1 at z0 first
                m.face([rings[s][k], rings[s][k1], rings[s + 1][k1], rings[s + 1][k]], col)
    if cap_back and profile[0][1] > 0:
        m.face(list(reversed(rings[0])), colours[0])
    if cap_front and profile[-1][1] > 0:
        m.face(rings[-1], colours[-1])
    return m


def cylinder(z0, z1, r, colour, centre=(0.0, 0.0), n=12, m=None, phase=None):
    return lathe([(z0, r), (z1, r)], colour, centre, n, phase, m)


def tube(z0, z1, r_out, r_in, colour, inner='tube_inner', centre=(0.0, 0.0), n=12, m=None, depth=None,
         back_closed=True):
    """open-fronted launch tube: outer wall, front annulus, inner wall to `depth` behind the mouth, back wall."""
    m = m or Mesh()
    cx, cy = centre
    ph = math.pi / n
    zi = z0 if depth is None else max(z0, z1 - depth)
    o0, o1 = _ring(cx, cy, z0, r_out, n, ph), _ring(cx, cy, z1, r_out, n, ph)
    i1, i0 = _ring(cx, cy, z1, r_in, n, ph), _ring(cx, cy, zi, r_in, n, ph)
    for k in range(n):
        k1 = (k + 1) % n
        m.face([o0[k], o0[k1], o1[k1], o1[k]], colour)                 # outer wall
        m.face([o1[k], o1[k1], i1[k1], i1[k]], colour)                 # front annulus (faces +z)
        m.face([i1[k], i1[k1], i0[k1], i0[k]], inner)                  # inner wall (faces the axis)
    m.face(i0, inner)                                                  # bottom of the bore, faces +z
    if back_closed:
        m.face(list(reversed(o0)), colour)
    return m


def fin(root_z0, root_z1, tip_z0, tip_z1, span, thickness, colour, angle_deg=0.0, r0=0.0, centre=(0.0, 0.0), m=None):
    """flat trapezoid fin: root chord [root_z0, root_z1] at radius r0, tip chord at r0 + span, rolled to angle
    (0 = +x/left, 90 = up) about the z axis through centre."""
    m = m or Mesh()
    t = thickness / 2
    pts = [(r0, root_z0), (r0, root_z1), (r0 + span, tip_z1), (r0 + span, tip_z0)]
    a = math.radians(angle_deg)
    ca, sa = math.cos(a), math.sin(a)
    cx, cy = centre

    def P(r, z, side):   # radial r, normal offset side
        return (cx + r * ca - side * sa, cy + r * sa + side * ca, z)
    top = [P(r, z, t) for r, z in pts]
    bot = [P(r, z, -t) for r, z in pts]
    # polygon order (root tail, root nose, tip nose, tip tail) is counter-clockwise seen from the +side normal
    m.face(top, colour)
    m.face(list(reversed(bot)), colour)
    for k in range(4):
        k1 = (k + 1) % 4
        m.face([top[k1], top[k], bot[k], bot[k1]], colour)
    return m


def fins(count, *args, start=45.0, **kw):
    m = kw.pop('m', None) or Mesh()
    for k in range(count):
        fin(*args, angle_deg=start + 360.0 * k / count, m=m, **kw)
    return m


def strut(p0, p1, w, colour, m=None):
    """square bar from p0 to p1 (a pylon arm, a rail hanger)"""
    m = m or Mesh()
    p0, p1 = np.array(p0, float), np.array(p1, float)
    d = p1 - p0; L = np.linalg.norm(d); d /= L
    ref = np.array([0, 1.0, 0]) if abs(d[1]) < 0.9 else np.array([1.0, 0, 0])
    u = np.cross(ref, d); u /= np.linalg.norm(u); v = np.cross(d, u)
    h = w / 2
    corners = [h * (su * u + sv * v) for su, sv in ((-1, -1), (1, -1), (1, 1), (-1, 1))]
    a = [p0 + c for c in corners]; b = [p1 + c for c in corners]
    for k in range(4):
        k1 = (k + 1) % 4
        m.face([a[k], a[k1], b[k1], b[k]], colour)
    m.face(list(reversed(a)), colour)
    m.face(b, colour)
    return m


# ------------------------------------------------------------------------------------------------ files
def _geo(mesh, identifier, frame):
    P = np.array(mesh.v, float)
    positions = (frame(P) * 16.0).round(5)
    polys, normals, uvs = [], [], []
    uv_index = {}
    for idx, colour in mesh.f:
        q = P[idx]
        n = np.zeros(3)
        for k in range(1, len(idx) - 1):
            n += np.cross(q[k] - q[0], q[k + 1] - q[0])
        L = np.linalg.norm(n)
        n = n / L if L > 1e-12 else np.array([0, 1.0, 0])
        nf = frame(n[None, :], True)[0]
        normals.append([round(float(x), 5) for x in nf])
        if colour not in uv_index:
            uv_index[colour] = len(uvs); uvs.append([round(c, 6) for c in cell_uv(colour)])
        polys.append([[i, len(normals) - 1, uv_index[colour]] for i in idx])
    lo, hi = positions.min(0) / 16, positions.max(0) / 16
    size = float(max(hi - lo)) + 1
    return {
        'format_version': '1.12.0',
        'minecraft:geometry': [{
            'description': {'identifier': identifier, 'texture_width': TEXTURE_SIZE, 'texture_height': TEXTURE_SIZE,
                            'visible_bounds_width': math.ceil(size), 'visible_bounds_height': math.ceil(size),
                            'visible_bounds_offset': [0, 0, 0]},
            'bones': [{'name': 'store', 'pivot': [0, 0, 0], 'poly_mesh': {
                'normalized_uvs': True, 'positions': positions.tolist(), 'normals': normals, 'uvs': uvs,
                'polys': polys}}]}]}


def store_frame(P, direction=False):
    """local blocks -> model file pixels / 16: (x, y, -z)"""
    return P * np.array([1.0, 1.0, -1.0])


def check_outward(mesh, what):
    # loader frame (-x, y, -z) of the local frame is a rotation, so the local signed volume is the loader's
    vol = mesh.signed_volume()
    if vol <= 0:
        raise ValueError(f'{what}: mesh is inside out (signed volume {vol:.4f})')
    return vol


def write_store(mesh, path, identifier):
    check_outward(mesh, path)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', encoding='utf-8', newline='\n') as f:
        json.dump(_geo(mesh, identifier, store_frame), f, separators=(',', ':'))
        f.write('\n')


def write_flight(mesh, path, identifier):
    """flight model: nose up +Y, tail at y = 0 (projectile_mesh_v1 ForwardAxis Y, Origin TAIL), as the existing
    <name>_flight models are made from their stores: file = (x, z - z_tail, y). Like the store file this is a
    mirror of the local frame, so faces keep their order."""
    check_outward(mesh, path)
    z0 = float(np.array(mesh.v)[:, 2].min())
    geo = _geo(mesh, identifier, lambda P, d=False: np.stack([P[:, 0], P[:, 2] - (0.0 if d else z0), P[:, 1]], -1))
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', encoding='utf-8', newline='\n') as f:
        json.dump(geo, f, separators=(',', ':'))
        f.write('\n')


def anchor(q):
    """a point of the local frame as a store JSON anchor/axis/offset (MountAnchor frame of ModelForward -Z)"""
    return [round(-q[0] + 0.0, 4), round(q[1] + 0.0, 4), round(-q[2] + 0.0, 4)]
