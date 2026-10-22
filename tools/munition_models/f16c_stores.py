#!/usr/bin/env python3
"""Store models and store definitions for the War Thunder F-16C (Block 50) loadout.

Sibling of generate.py (same conventions: 1 block = 1 m, 16 px per block, flat colour cells in a vertical-stripe
texture, nose at -Z, body axis on the origin, one bone 'store'). Builds bodies of revolution with fins, strakes,
lugs and tube mouths, the rack adapters of the multi-carriage stores, the pod/tank/cluster stores, and writes the
store JSON beside them. Anchors (MountAnchor, SideMountAnchors, MountAxis, LaunchOffset) are derived from the
written model with tools/aircraft_attach/munitions.py, the same rules attach.py applies to the whole catalogue.

Usage: python tools/munition_models/f16c_stores.py [--write]
Then:  python tools/munition_models/fix_winding.py   (and --check)

Sources for every dimension are in the report that came with this change; guesses are marked GUESS below.
"""
import json
import math
import os
import shutil
import sys
from collections import OrderedDict

import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, '..', '..'))
sys.path.insert(0, os.path.join(REPO, 'tools', 'aircraft_attach'))
sys.path.insert(0, HERE)
import munitions  # noqa: E402
import generate  # noqa: E402

GEN = os.path.join(REPO, 'bvp', 'src', 'generated', 'resources')
GEO = os.path.join(GEN, 'assets/berts_vehicle_pack/custom_geo/aircraft_stores')
TEX = os.path.join(GEN, 'assets/berts_vehicle_pack/textures/aircraft_stores')
SBW = os.path.join(GEN, 'data/berts_vehicle_pack/sbw')
STORES = os.path.join(SBW, 'aircraft_stores')
SOURCE = os.environ.get('F16C_STORE_SOURCE', r'C:\Users\udstu\Documents\Superb warfare\work\elite_expansion_2\f16c\stores')
NS = 'berts_vehicle_pack'
PX = 16.0
SEG = 16

PAL = OrderedDict([
    ('olive', (84, 92, 58)), ('grey', (150, 155, 158)), ('dgrey', (88, 92, 96)), ('white', (226, 226, 220)),
    ('yellow', (214, 176, 40)), ('black', (32, 32, 34)), ('alu', (176, 180, 184)), ('red', (158, 40, 34)),
    ('glass', (36, 66, 86)), ('tan', (150, 134, 100)), ('blue', (52, 72, 112)), ('rack', (70, 72, 76)),
    ('orange', (200, 110, 40))])
CELLS = list(PAL)


# ------------------------------------------------------------------------------------------------ mesh kit (metres)
class Mesh:
    def __init__(self):
        self.positions, self.normals, self.uvs, self.polys = [], [], [], []

    def uv(self, colour):
        return [round((CELLS.index(colour) + 0.5) / len(CELLS), 5), 0.5]

    def poly(self, pts, colour):
        out = []
        for p in pts:
            p = np.asarray(p, float)
            if not out or np.linalg.norm(p - out[-1]) > 1e-7:
                out.append(p)
        if len(out) > 1 and np.linalg.norm(out[0] - out[-1]) <= 1e-7:
            out.pop()
        if len(out) < 3:
            return
        n = np.cross(out[1] - out[0], out[2] - out[0])
        if np.linalg.norm(n) < 1e-12 and len(out) > 3:
            n = np.cross(out[2] - out[0], out[3] - out[0])
        if np.linalg.norm(n) < 1e-12:
            return
        n = n / np.linalg.norm(n)
        self.normals.append([round(float(c), 4) for c in n]); ni = len(self.normals) - 1
        self.uvs.append(self.uv(colour)); ui = len(self.uvs) - 1
        idx = []
        for p in out:
            self.positions.append([round(float(c) * PX, 4) for c in p])
            idx.append([len(self.positions) - 1, ni, ui])
        self.polys.append(idx)

    def box(self, x0, x1, y0, y1, z0, z1, colour):
        q = [([[x0, y0, z0], [x0, y0, z1], [x0, y1, z1], [x0, y1, z0]]), ([[x1, y0, z1], [x1, y0, z0], [x1, y1, z0], [x1, y1, z1]]),
             ([[x0, y1, z0], [x0, y1, z1], [x1, y1, z1], [x1, y1, z0]]), ([[x0, y0, z1], [x0, y0, z0], [x1, y0, z0], [x1, y0, z1]]),
             ([[x1, y0, z1], [x1, y1, z1], [x0, y1, z1], [x0, y0, z1]]), ([[x0, y0, z0], [x0, y1, z0], [x1, y1, z0], [x1, y0, z0]])]
        for pts in q:
            self.poly(pts, colour)

    def translated(self, dx, dy, dz):
        out = Mesh()
        out.positions = [[p[0] + dx * PX, p[1] + dy * PX, p[2] + dz * PX] for p in self.positions]
        out.normals = [list(n) for n in self.normals]
        out.uvs = [list(u) for u in self.uvs]
        out.polys = [[list(v) for v in poly] for poly in self.polys]
        return out

    def merge(self, other):
        base_p, base_n, base_u = len(self.positions), len(self.normals), len(self.uvs)
        self.positions += other.positions; self.normals += other.normals; self.uvs += other.uvs
        for poly in other.polys:
            self.polys.append([[v[0] + base_p, v[1] + base_n, v[2] + base_u] for v in poly])


def ogive(length, r0, r1, n=8):
    """Nose profile points (s, r): radius r0 at s=0 growing to r1 at s=length (tangent ogive)."""
    return [(length * i / n, r0 + (r1 - r0) * math.sqrt(max(0.0, 1 - (1 - i / n) ** 2))) for i in range(n + 1)]

def refine(profile, cuts):
    """Insert the cut positions into a profile (linear radius) so colours can change exactly there."""
    out = []
    for (s0, r0), (s1, r1) in zip(profile, profile[1:]):
        out.append((s0, r0))
        for c in sorted(cuts):
            if s0 + 1e-6 < c < s1 - 1e-6:
                out.append((c, r0 + (r1 - r0) * (c - s0) / (s1 - s0)))
    out.append(profile[-1])
    return out


def revolve(m, profile, colour_at, cuts=(), ax=0.0, ay=0.0, cap_nose=True, cap_tail=True, caps=('dgrey', 'black')):
    """Surface of revolution about the z axis through (ax, ay); profile = [(s, r)] from the nose."""
    prof = refine(profile, cuts)

    def ring(s, r):
        return [np.array([ax + r * math.cos(2 * math.pi * k / SEG), ay + r * math.sin(2 * math.pi * k / SEG), s]) for k in range(SEG)]
    for (s0, r0), (s1, r1) in zip(prof, prof[1:]):
        a, b = ring(s0, r0), ring(s1, r1)
        col = colour_at((s0 + s1) / 2)
        for k in range(SEG):
            k2 = (k + 1) % SEG
            m.poly([a[k], a[k2], b[k2], b[k]], col)
    if cap_nose and prof[0][1] > 1e-4:
        a = ring(*prof[0]); c = np.array([ax, ay, prof[0][0]])
        for k in range(SEG):
            m.poly([c, a[(k + 1) % SEG], a[k]], caps[0])
    if cap_tail and prof[-1][1] > 1e-4:
        a = ring(*prof[-1]); c = np.array([ax, ay, prof[-1][0]])
        for k in range(SEG):
            m.poly([c, a[k], a[(k + 1) % SEG]], caps[1])


def disc(m, cx, cy, s, r, colour, facing, n=8):
    """Flat polygon at s facing -Z (nose, facing=-1) or +Z (tail, facing=+1): a tube mouth."""
    pts = [np.array([cx + r * math.cos(2 * math.pi * k / n), cy + r * math.sin(2 * math.pi * k / n), s]) for k in range(n)]
    m.poly(pts[::-1] if facing < 0 else pts, colour)


def fin(m, root, tip, r_in, r_out, roll, th, colour, ax=0.0, ay=0.0):
    """Swept plate: root chord (s0, s1) at r_in, tip chord (s0, s1) at r_out, radial direction at `roll` degrees."""
    ca, sa = math.cos(math.radians(roll)), math.sin(math.radians(roll))
    radial = np.array([ca, sa, 0.0]); side = np.array([-sa, ca, 0.0]); origin = np.array([ax, ay, 0.0])
    pts = [origin + radial * r_in + [0, 0, root[0]], origin + radial * r_in + [0, 0, root[1]],
           origin + radial * r_out + [0, 0, tip[1]], origin + radial * r_out + [0, 0, tip[0]]]
    top = [p + side * th / 2 for p in pts]; bot = [p - side * th / 2 for p in pts]
    m.poly(top, colour); m.poly(bot[::-1], colour)
    for i in range(4):
        j = (i + 1) % 4
        m.poly([top[i], bot[i], bot[j], top[j]], colour)


def cruciform(m, root, tip, r_in, r_out, th, colour, roll=45, ax=0.0, ay=0.0, count=4):
    for q in range(count):
        fin(m, root, tip, r_in, r_out, roll + 360 / count * q, th, colour, ax, ay)


def lugs(m, zc, spacing, ytop, colour='dgrey', ax=0.0):
    """Two suspension lugs on top of a body whose skin is at ytop (the pylon contact is the lug top)."""
    for z in (zc - spacing / 2, zc + spacing / 2):
        m.box(ax - 0.014, ax + 0.014, ytop - 0.01, ytop + 0.024, z - 0.03, z + 0.03, colour)


def centred(m, length):
    """Shift so the body is centred on z = 0 (nose at -length/2)."""
    return m.translated(0, 0, -length / 2)


# ------------------------------------------------------------------------------------------------ builders
def build_jdam(L, d, nose_len, kit_start, fin_span, strake, seeker=0.0, kit_end_r=0.62, lug_gap=0.356, lug_z=None,
               wing_kit=None, body='olive', band=(0.07, 0.13), er_fins=None):
    """JDAM-type guided bomb: Mk 82/84 body, tail-kit cone with four fins, four strakes, optional seeker nose and ER wing kit."""
    r = d / 2
    m = Mesh()
    prof = [(0.0, 0.10 * r)] + [(s + seeker, rr) for s, rr in ogive(nose_len, 0.12 * r, r)[1:]]
    if seeker:
        prof = [(0.0, 0.55 * r), (seeker * 0.45, 0.80 * r), (seeker, 0.55 * r + 0.0)] + [(s + seeker, rr) for s, rr in ogive(nose_len, 0.55 * r, r)[1:]]
    prof += [(kit_start, r), (L, kit_end_r * r)]
    b0, b1 = nose_len + seeker + band[0], nose_len + seeker + band[1]

    def col(s):
        if s < seeker:
            return 'glass' if s < seeker * 0.7 else 'dgrey'
        if s >= kit_start:
            return 'grey'
        if b0 <= s < b1:
            return 'yellow'
        return body
    revolve(m, prof, col, cuts=[b0, b1, kit_start, seeker] if seeker else [b0, b1, kit_start])
    cruciform(m, (L - 0.30 * fin_span - 0.12, L - 0.02), (L - 0.20 * fin_span - 0.05, L - 0.02), 0.45 * r, fin_span / 2, 0.012 * (d / 0.273) + 0.006, 'grey', 45)
    if strake:
        s0, s1, out = strake
        cruciform(m, (s0, s1), (s0 + 0.3 * (s1 - s0), s1), r * 0.92, r + out, 0.012, 'grey', 0)
    if wing_kit:
        s0, s1, out = wing_kit
        for sx in (-1, 1):
            x0, x1 = (r * 0.8, r + out) if sx > 0 else (-(r + out), -r * 0.8)
            m.box(x0, x1, -0.022, 0.022, s0, s1, 'grey')
    if er_fins:
        cruciform(m, er_fins[0], er_fins[1], 0.5 * r, er_fins[2], 0.014, 'grey', 0)
    lugs(m, lug_z if lug_z is not None else kit_start - 0.55, lug_gap, r)
    return centred(m, L), L


def build_gbu24():
    """EGBU-24B Paveway III: BLU-109 body, nose seeker section with canards, mid wings, tail control fins."""
    L, d = 4.39, 0.368; r = d / 2
    m = Mesh()
    prof = [(0.0, 0.05), (0.18, 0.115), (0.45, 0.158), (0.75, r), (3.80, r), (L, 0.13)]

    def col(s):
        if s < 0.75:
            return 'dgrey' if s < 0.05 else 'grey'
        if s >= 3.80:
            return 'grey'
        if 1.00 <= s < 1.07:
            return 'yellow'
        return 'olive'
    revolve(m, prof, col, cuts=[1.00, 1.07, 3.80])
    cruciform(m, (0.62, 0.82), (0.72, 0.82), 0.12, 0.26, 0.012, 'grey', 45)
    cruciform(m, (2.20, 3.40), (2.90, 3.40), r * 0.9, 0.825, 0.014, 'grey', 0)   # wings, span 1.65 m
    cruciform(m, (L - 0.60, L - 0.05), (L - 0.42, L - 0.05), 0.10, 0.30, 0.012, 'grey', 45)
    lugs(m, 2.10, 0.762, r)
    return centred(m, L), L


def build_blu27():
    """BLU-27/B fire bomb: welded 100 US gal tank, 3.30 m x 0.47 m, rounded ends, small tail stabilisers."""
    L, d = 3.30, 0.47; r = d / 2
    m = Mesh()
    prof = [(0.0, 0.09), (0.05, 0.16), (0.14, 0.205), (0.30, r), (L - 0.40, r), (L - 0.20, 0.205), (L - 0.04, 0.12), (L, 0.09)]

    def col(s):
        if 0.55 <= s < 0.65 or L - 0.75 <= s < L - 0.65:
            return 'red'
        return 'alu'
    revolve(m, prof, col, cuts=[0.55, 0.65, L - 0.75, L - 0.65], caps=('dgrey', 'dgrey'))
    cruciform(m, (L - 0.45, L - 0.02), (L - 0.35, L - 0.02), 0.12, 0.30, 0.012, 'dgrey', 45)
    lugs(m, 1.65, 0.356, r)
    return centred(m, L), L


def build_sdb(L, d, seeker, wing_stow, fins_z, body='grey'):
    """Small-diameter bomb (GBU-39) / StormBreaker (GBU-53): slim body, stowed wing slots, four tail fins."""
    r = d / 2
    m = Mesh()
    nose = 0.34
    prof = [(0.0, 0.10 * r)] + ogive(nose, 0.10 * r, r)[1:] + [(L - 0.50, r), (L, 0.72 * r)]
    if seeker:
        prof = [(0.0, 0.5 * r)] + ogive(nose, 0.5 * r, r)[1:] + [(L - 0.50, r), (L, 0.72 * r)]

    def col(s):
        if seeker and s < seeker:
            return 'glass'
        if s >= L - 0.50:
            return 'dgrey'
        if 0.45 <= s < 0.50:
            return 'yellow'
        return body
    revolve(m, prof, col, cuts=[0.45, 0.50, L - 0.50] + ([seeker] if seeker else []))
    for sx in (-1, 1):
        x0, x1 = (r * 0.85, r + 0.035) if sx > 0 else (-(r + 0.035), -r * 0.85)
        m.box(x0, x1, -0.014, 0.014, wing_stow[0], wing_stow[1], 'dgrey')
    cruciform(m, (fins_z[0], fins_z[1]), (fins_z[0] + 0.12, fins_z[1]), 0.5 * r, 0.19, 0.01, 'dgrey', 45)
    lugs(m, 0.92, 0.356, r)
    return centred(m, L), L


def tubes(n):
    """Hexagonal tube-centre lattice (19 = 1+6+12, 7 = 1+6) in units of the tube pitch."""
    pts = [(0.0, 0.0)]
    for ring_i in range(1, 3 if n == 19 else 2):
        for k in range(6 * ring_i):
            # walk the hexagon ring of radius ring_i
            side, step = divmod(k, ring_i)
            a0 = math.radians(60 * side); a1 = math.radians(60 * (side + 1))
            c0 = np.array([ring_i * math.cos(a0), ring_i * math.sin(a0)]); c1 = np.array([ring_i * math.cos(a1), ring_i * math.sin(a1)])
            p = c0 + (c1 - c0) * step / ring_i
            pts.append((float(p[0]), float(p[1])))
    return pts[:n]


def build_pod(L, d, n_tubes, pitch, body='olive', ax=0.0, ay=0.0, lug_gap=0.356, with_lugs=True, m=None):
    """Rocket launcher pod: tube barrel with frangible nose and tail fairing cones and the tube mouths drawn aft."""
    r = d / 2
    m = m or Mesh()
    nose, tail = 0.16, 0.20
    prof = [(0.0, 0.62 * r), (nose, r), (L - tail, r), (L, 0.80 * r)]

    def col(s):
        return 'dgrey' if (s < nose or s > L - tail) else body
    revolve(m, prof, col, cuts=[nose, L - tail], ax=ax, ay=ay, caps=('grey', 'grey'))
    for (px, py) in tubes(n_tubes):
        disc(m, ax + px * pitch, ay + py * pitch, L + 0.001, pitch * 0.40, 'black', +1)
    if with_lugs:
        lugs(m, L * 0.5, lug_gap, ay + r, ax=ax)
    return m, L


def build_cluster(L, d, n_tubes, pitch, spacing):
    """Three pods on a triple carriage (two shoulders and one below): the 57-FFAR / 21-APKWS station loads."""
    m = Mesh()
    top = 0.0
    centres = [(-spacing / 2, 0.0), (spacing / 2, 0.0), (0.0, -spacing * math.sqrt(3) / 2)]
    for cx, cy in centres:
        build_pod(L, d, n_tubes, pitch, 'olive', cx, cy, with_lugs=False, m=m)
    r = d / 2
    # carriage bar (one box at the top) and a lug pair on it
    m.box(-spacing / 2 - r, spacing / 2 + r, r - 0.05, r - 0.01, L * 0.1, L * 0.9, 'rack')
    lugs(m, L * 0.5, 0.356, r - 0.01)
    return centred(m, L), L, centres


def build_sniper():
    """AN/AAQ-33 Sniper ATP: 2.49 m x 0.30 m, faceted nose turret with the window, no fins."""
    L, d = 2.494, 0.30; r = d / 2
    m = Mesh()
    prof = [(0.0, 0.085), (0.04, 0.115), (0.10, 0.135), (0.30, r), (L - 0.40, r), (L, 0.10)]

    def col(s):
        if s < 0.04:
            return 'glass'
        return 'grey' if s < L - 0.40 else 'dgrey'
    revolve(m, prof, col, cuts=[0.04, L - 0.40], caps=('glass', 'dgrey'))
    # chin sensor window: a dark facet low on the nose
    m.box(-0.07, 0.07, -0.145, -0.125, 0.08, 0.34, 'glass')
    lugs(m, L * 0.5, 0.356, r)
    return centred(m, L), L


def build_hts():
    """AN/ASQ-213 HTS: 1.42 m x 0.20 m, blunt nose, antenna blades around the forward section."""
    L, d = 1.42, 0.20; r = d / 2
    m = Mesh()
    prof = [(0.0, 0.05), (0.06, 0.085), (0.18, r), (L - 0.25, r), (L, 0.07)]

    def col(s):
        return 'white' if s < 0.18 else ('grey' if s < L - 0.25 else 'dgrey')
    revolve(m, prof, col, cuts=[0.18, L - 0.25], caps=('white', 'dgrey'))
    cruciform(m, (0.20, 0.45), (0.28, 0.45), r * 0.9, r + 0.035, 0.008, 'dgrey', 0)
    lugs(m, L * 0.5, 0.356, r)
    return centred(m, L), L


def build_apkws_rocket():
    """AGR-20A APKWS II: 1.87 m x 70 mm, four fixed wings and four tail fins."""
    L, d = 1.87, 0.07; r = d / 2
    m = Mesh()
    prof = [(0.0, 0.01)] + ogive(0.20, 0.01, r)[1:] + [(L - 0.1, r), (L, r * 0.8)]
    revolve(m, prof, lambda s: 'glass' if s < 0.05 else ('grey' if s < 0.55 else 'olive'), cuts=[0.05, 0.55])
    cruciform(m, (1.10, 1.40), (1.20, 1.40), r, 0.11, 0.004, 'dgrey', 45)
    cruciform(m, (L - 0.22, L - 0.01), (L - 0.15, L - 0.01), r, 0.10, 0.004, 'dgrey', 45)
    return centred(m, L), L


# ------------------------------------------------------------------------------------------------ files
def geo_json(m, ident, tex_w=None):
    return {'format_version': '1.12.0', 'minecraft:geometry': [{
        'description': {'identifier': 'geometry.aircraft_store.' + ident, 'texture_width': tex_w or 16 * len(CELLS),
                        'texture_height': 16, 'visible_bounds_width': 8, 'visible_bounds_height': 8,
                        'visible_bounds_offset': [0, 0, 0]},
        'bones': [{'name': 'store', 'pivot': [0, 0, 0], 'poly_mesh': {
            'normalized_uvs': True, 'positions': m.positions, 'normals': m.normals, 'uvs': m.uvs, 'polys': m.polys}}]}]}


def flight_geo(g, ident):
    """Store frame (nose -Z) -> flight frame (+Y forward, nozzle at y = 0), as generate.geo does."""
    out = json.loads(json.dumps(g))
    desc = out['minecraft:geometry'][0]
    desc['description']['identifier'] = 'geometry.aircraft_store.' + ident
    pm = desc['bones'][0]['poly_mesh']
    P = np.array(pm['positions'])
    zmax = P[:, 2].max(); zmin = P[:, 2].min()
    pm['positions'] = [[round(p[0], 4), round(zmax - p[2], 4), round(p[1], 4)] for p in pm['positions']]
    pm['normals'] = [[n[0], -n[2], n[1]] for n in pm['normals']]
    return out


def palette_png():
    im = Image.new('RGBA', (16 * len(CELLS), 16))
    for i, name in enumerate(CELLS):
        im.paste(PAL[name] + (255,), (16 * i, 0, 16 * (i + 1), 16))
    return im


def write_json_compact(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', encoding='utf-8', newline='\n') as fh:
        json.dump(obj, fh, separators=(',', ':'))


def write_json(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', encoding='utf-8', newline='\n') as fh:
        fh.write(json.dumps(obj, indent=2, ensure_ascii=False) + '\n')


def save_model(name, m, flight=False):
    g = geo_json(m, name)
    write_json_compact(os.path.join(GEO, name + '.geo.json'), g)
    palette_png().save(os.path.join(TEX, name + '.png'))
    if flight:
        write_json_compact(os.path.join(GEO, name + '_flight.geo.json'), flight_geo(g, name + '_flight'))
    return name


def import_source(src, name, flight=False):
    """Copy a ready-made F-16C source model (geo + png), re-centred on its body axis."""
    g = json.load(open(os.path.join(SOURCE, src, src + '.geo.json'), encoding='utf-8'))
    pm = g['minecraft:geometry'][0]['bones'][0]['poly_mesh']
    P = np.array(pm['positions'])
    g['minecraft:geometry'][0]['description']['identifier'] = 'geometry.aircraft_store.' + name
    write_json_compact(os.path.join(GEO, name + '.geo.json'), g)
    shutil.copyfile(os.path.join(SOURCE, src, src + '.png'), os.path.join(TEX, name + '.png'))
    # body-axis recentre is done after anchors are known (see recentre)
    return name


def recentre(name, flight=False):
    """Move the body axis (x, y) of a written model onto the origin; write the flight model from it."""
    path = os.path.join(GEO, name + '.geo.json')
    g = json.load(open(path, encoding='utf-8'))
    geo = munitions.MunitionGeometry(name)
    dx, dy = geo.x0 * PX, geo.y0 * PX
    desc = g['minecraft:geometry'][0]
    pm = desc['bones'][0]['poly_mesh']
    pm['positions'] = [[round(p[0] - dx, 4), round(p[1] - dy, 4), p[2]] for p in pm['positions']]
    write_json_compact(path, g)
    if flight:
        write_json_compact(os.path.join(GEO, name + '_flight.geo.json'), flight_geo(g, name + '_flight'))


# ------------------------------------------------------------------------------------------------ racks
THK = 0.045


def rack_model(name, boxes):
    """Rack adapter geo (hull offsets from the top anchor, left-wing orientation) -> MountAnchor-frame geo."""
    m = Mesh()
    uv = m.uv('rack')
    positions, normals, uvs, polys = [], [], [], []
    for (x0, x1, y0, y1, z0, z1) in boxes:
        quads = [([[x0, y0, z0], [x0, y0, z1], [x0, y1, z1], [x0, y1, z0]], [-1, 0, 0]),
                 ([[x1, y0, z1], [x1, y0, z0], [x1, y1, z0], [x1, y1, z1]], [1, 0, 0]),
                 ([[x0, y1, z0], [x0, y1, z1], [x1, y1, z1], [x1, y1, z0]], [0, 1, 0]),
                 ([[x0, y0, z1], [x0, y0, z0], [x1, y0, z0], [x1, y0, z1]], [0, -1, 0]),
                 ([[x1, y0, z1], [x1, y1, z1], [x0, y1, z1], [x0, y0, z1]], [0, 0, 1]),
                 ([[x0, y0, z0], [x0, y1, z0], [x1, y1, z0], [x1, y0, z0]], [0, 0, -1])]
        for quad, normal in quads:
            g = [[p[0] * 16.0, p[1] * 16.0, -p[2] * 16.0] for p in quad][::-1]
            gn = [normal[0], normal[1], -normal[2]]
            poly = []
            for p in g:
                positions.append([round(c, 4) for c in p]); normals.append([float(c) for c in gn]); uvs.append(uv)
                k = len(positions) - 1
                poly.append([k, k, k])
            polys.append(poly)
    out = {'format_version': '1.12.0', 'minecraft:geometry': [{
        'description': {'identifier': 'geometry.aircraft_store.' + name, 'texture_width': 16 * len(CELLS), 'texture_height': 16,
                        'visible_bounds_width': 4, 'visible_bounds_height': 4, 'visible_bounds_offset': [0, 0, 0]},
        'bones': [{'name': 'store', 'pivot': [0, 0, 0], 'poly_mesh': {
            'normalized_uvs': True, 'positions': positions, 'normals': normals, 'uvs': uvs, 'polys': polys}}]}]}
    write_json(os.path.join(GEO, name + '.geo.json'), out)
    return name


def vec(v):
    return [round(float(c), 4) + 0.0 for c in v]


# ------------------------------------------------------------------------------------------------ store JSON
BOMB_BASE = {'Gravity': 0.08, 'DragMultiplier': 1}


def anchors_for(model, forward='-Z', missile=False, scale=1.0, manual=None):
    g = munitions.MunitionGeometry(model)
    if manual:
        top, left, right, axis = [np.array(manual[k], float) for k in ('top', 'left', 'right', 'axis')]
    else:
        a = g.anchors(forward, missile=missile)
        top, left, right, axis = [munitions.to_anchor_frame(a[k]) for k in ('top', 'left', 'right', 'axis')]
    launch = munitions.hull_offset(axis - top, forward, scale)
    return OrderedDict([('MountAnchor', vec(top)), ('SideMountAnchors', OrderedDict([('Left', vec(left)), ('Right', vec(right))])),
                        ('MountAxis', vec(axis))]), vec(launch), g


class Catalogue:
    def __init__(self):
        self.items = OrderedDict()

    def add(self, rel, d):
        self.items[rel] = d
        write_json(os.path.join(STORES, rel + '.json'), d)


def store(name, category, model, anchors, **fields):
    d = OrderedDict([('Schema', 1), ('Name', name), ('Category', category)])
    if 'Item' in fields:
        d['Item'] = fields.pop('Item')
    d['Model'] = f'{NS}:custom_geo/aircraft_stores/{model}.geo.json'
    d['ModelForward'] = '-Z'
    d.update(anchors)
    d['Texture'] = f'{NS}:textures/aircraft_stores/{fields.pop("texture", model)}.png'
    d.update(fields)
    return d


def main(argv):
    write = '--write' in argv
    if not write:
        print('dry run: pass --write to generate files')
        return
    os.makedirs(GEO, exist_ok=True); os.makedirs(TEX, exist_ok=True)
    cat = Catalogue()
    prof_dir = os.path.join(SBW, 'projectile_profiles')

    def bomb_profile(pid, model):
        write_json(os.path.join(prof_dir, 'aircraft_bombs', pid + '.json'), OrderedDict([
            ('VisualProfile', f'{NS}:authored_bomb'), ('MotionSync', 'INHERIT'), ('TrailMode', 'SUPPRESS'), ('RenderScale', 1),
            ('Extensions', OrderedDict([(f'{NS}:projectile_mesh_v1', OrderedDict([
                ('Model', f'{NS}:custom_geo/aircraft_stores/{model}.geo.json'),
                ('Texture', f'{NS}:textures/aircraft_stores/{model}.png'), ('ForwardYaw', 0), ('SpinDegreesPerTick', 0)]))]))]))

    # ================================================================== models
    models = {}
    # JDAM family. GUESS marks: lengths of the laser/ER kits (no public figure found), strake/fin sizes from drawings.
    m, L = build_jdam(2.352, 0.273, 0.55, 1.75, 0.356, (1.05, 1.70, 0.04), lug_z=1.15)            # GBU-38 (USAF 92.6 in, span 14 in)
    models['gbu38'] = save_model('gbu38', m)
    m, L = build_jdam(2.352 + 0.12, 0.273, 0.55, 1.87, 0.356, (1.12, 1.82, 0.04), seeker=0.12, lug_z=1.27)   # GBU-54 LJDAM: +0.12 m laser seeker (GUESS)
    models['gbu54'] = save_model('gbu54', m)
    m, L = build_jdam(2.352 + 0.10, 0.273, 0.55, 1.85, 0.356, (1.05, 1.70, 0.04), wing_kit=(0.95, 1.65, 0.07), er_fins=((1.55, 1.95), (1.70, 1.95), 0.18), lug_z=1.20)
    models['gbu62'] = save_model('gbu62', m)                                                           # GBU-62 JDAM-ER: wing kit stowed (GUESS size)
    m, L = build_jdam(2.352 + 0.10 + 0.12, 0.273, 0.55, 1.97, 0.356, (1.17, 1.82, 0.04), seeker=0.12, wing_kit=(1.07, 1.77, 0.07), er_fins=((1.67, 2.07), (1.82, 2.07), 0.18), lug_z=1.32)
    models['gbu62l'] = save_model('gbu62l', m)                                                         # GBU-62 LJDAM-ER
    m, L = build_jdam(3.879, 0.458, 0.85, 2.88, 0.635, (1.60, 2.80, 0.07), lug_gap=0.762, lug_z=1.95)     # GBU-31(V)1/B (USAF 152.7 in, span 25 in)
    models['gbu31'] = save_model('gbu31', m)
    m, L = build_jdam(3.879 + 0.15, 0.458, 0.85, 3.03, 0.635, (1.75, 2.95, 0.07), wing_kit=(1.25, 2.55, 0.11), er_fins=((2.80, 3.50), (3.00, 3.50), 0.30), lug_gap=0.762, lug_z=2.10)
    models['gbu64'] = save_model('gbu64', m)                                                           # GBU-64 JDAM-ER (Mk 84): wing kit (GUESS size)
    m, L = build_gbu24(); models['gbu24'] = save_model('gbu24', m)
    m, L = build_sdb(1.80, 0.19, 0.0, (0.55, 1.20), (1.42, 1.78)); models['gbu39'] = save_model('gbu39', m)
    m, L = build_sdb(1.75, 0.178, 0.16, (0.60, 1.15), (1.40, 1.73)); models['gbu53'] = save_model('gbu53', m)
    m, L = build_blu27(); models['blu27'] = save_model('blu27', m)
    # pods and clusters
    m, L = build_pod(1.90, 0.249, 7, 0.0825); m = centred(m, L); models['lau68_hydra'] = save_model('lau68_hydra', m)
    m, L = build_pod(1.78, 0.41, 19, 0.0825); m = centred(m, L); models['lau61_hydra'] = save_model('lau61_hydra', m)
    m, L, c3a = build_cluster(1.40, 0.40, 19, 0.0795, 0.42); models['lau3a_ffar'] = save_model('lau3a_ffar', m)
    m, L, c131 = build_cluster(1.806, 0.257, 7, 0.0825, 0.27); models['lau131_apkws'] = save_model('lau131_apkws', m)
    m, L = build_apkws_rocket(); models['apkws'] = save_model('apkws', m)
    m, L = build_sniper(); models['sniper_pod'] = save_model('sniper_pod', m)
    m, L = build_hts(); models['asq213_hts'] = save_model('asq213_hts', m)
    # AGM-84D Harpoon: generate.py's body-of-revolution builder (3.84 m, 0.343 m, wings span 0.914 m, tail fins 0.914 m)
    spec = dict(length=3.84, diameter=0.343, nose=0.80, boattail=0.12, tail_r=0.8,
                bands=[(0.20, 0.215, 'yellow'), (0.225, 0.24, 'brown')], radome=0.20,
                wings=[dict(root=(1.70, 2.45), tip=(2.00, 2.45), span=0.914 / 2, roll=45)],
                fins=[dict(root=(3.20, 3.78), tip=(3.45, 3.78), span=0.914 / 2, roll=45)])
    mesh, Lpx, rpx = generate.build(spec)
    # ventral turbojet inlet scoop (air-launched Harpoon) and a lug pair
    z0 = -Lpx / 2
    Mesh.box(mesh, -0.9, 0.9, -rpx - 1.6, -rpx * 0.9, z0 + 2.45 * PX, z0 + 2.95 * PX, 'dark')
    g = generate.geo(mesh, 'geometry.aircraft_store.agm84d', False, Lpx)
    write_json_compact(os.path.join(GEO, 'agm84d.geo.json'), g)
    write_json_compact(os.path.join(GEO, 'agm84d_flight.geo.json'), generate.geo(mesh, 'geometry.aircraft_store.agm84d_flight', True, Lpx))
    generate.texture().save(os.path.join(TEX, 'agm84d.png'))
    models['agm84d'] = 'agm84d'
    # ready-made F-16C source models
    for src, name, flt in (('agm65d', 'agm65g', True), ('tank_370gal', 'tank_370gal', False), ('tank_300gal', 'tank_300gal', False)):
        import_source(src, name)
        recentre(name, flt)
        models[name] = name

    # ================================================================== racks (hull offsets, left-wing orientation)
    def rack_two(name, r, fin_r, d_extra=0.0):
        W = max(0.10, 2 * fin_r - 2 * r + 0.05)
        D = r - THK / 2 + 0.03 + d_extra
        ymid = -D - THK / 2
        boxes = [(-0.03, 0.03, -D, 0.0, -0.35, 0.35), (-W / 2, W / 2, -D - THK, -D, -0.45, 0.45)]
        rack_model(name, boxes)
        return [OrderedDict([('Point', vec([-W / 2, ymid, 0.0])), ('Face', 'left')]),
                OrderedDict([('Point', vec([W / 2, ymid, 0.0])), ('Face', 'right')])], (W, D)

    def rack_four(name, r, fin_r):
        """BRU-61/A: two crossbars one above the other, a store on each end (GUESS: layout from photographs)."""
        W = max(0.10, 2 * fin_r - 2 * r + 0.04)
        D = r - THK / 2 + 0.03
        vsep = 2 * fin_r + 0.02
        y1 = -D - THK / 2; y2 = y1 - vsep
        rack_model(name, [(-0.03, 0.03, -D, 0.0, -0.45, 0.45), (-W / 2, W / 2, -D - THK, -D, -0.55, 0.55),
                          (-0.03, 0.03, y2 + THK / 2, -D - THK, -0.30, 0.30), (-W / 2, W / 2, y2 - THK / 2, y2 + THK / 2, -0.55, 0.55)])
        return [OrderedDict([('Point', vec([-W / 2, y1, 0.0])), ('Face', 'left')]),
                OrderedDict([('Point', vec([W / 2, y1, 0.0])), ('Face', 'right')]),
                OrderedDict([('Point', vec([-W / 2, y2, 0.0])), ('Face', 'left')]),
                OrderedDict([('Point', vec([W / 2, y2, 0.0])), ('Face', 'right')])], (W, D)
    def rack_six(name, r, fin_r):
        W = max(0.10, 2 * fin_r - 2 * r + 0.04)
        D = r - THK / 2 + 0.03
        vsep = 2 * fin_r + 0.02
        ys = [-D - THK / 2 - i * vsep for i in range(3)]
        boxes = [(-0.04, 0.04, -D, 0.0, -0.55, 0.55)]
        for i, y in enumerate(ys):
            boxes.append((-W / 2, W / 2, y - THK / 2, y + THK / 2, -0.60, 0.60))
            if i:
                boxes.append((-0.04, 0.04, y + THK / 2, ys[i - 1] - THK / 2, -0.45, 0.45))
        rack_model(name, boxes)
        st = []
        for y in ys:
            st += [OrderedDict([('Point', vec([-W / 2, y, 0.0])), ('Face', 'left')]),
                   OrderedDict([('Point', vec([W / 2, y, 0.0])), ('Face', 'right')])]
        return st, (W, D)

    def rack_three_lau88(name, r, fin_r):
        # LAU-88: one missile hangs below the beam, one on each upper side (GUESS: dimensions from the Maverick, not a drawing)
        sep = 2 * fin_r * 1.0 + 0.03
        D = r - THK / 2 + 0.03
        ymid = -D - THK / 2
        need = max(sep * sep - (r + THK / 2) ** 2, 0.0)
        W = max(0.10, 2 * (math.sqrt(need) - r))
        boxes = [(-0.04, 0.04, -D, 0.0, -0.50, 0.50), (-W / 2, W / 2, -D - THK, -D, -0.60, 0.60)]
        rack_model(name, boxes)
        return [OrderedDict([('Point', vec([0.0, -D - THK, 0.0])), ('Face', 'bottom')]),
                OrderedDict([('Point', vec([-W / 2, ymid, 0.0])), ('Face', 'left')]),
                OrderedDict([('Point', vec([W / 2, ymid, 0.0])), ('Face', 'right')])], (W, D)

    st_bru57, _ = rack_two('rack_bru57_x2', 0.1365, 0.142)
    st_blu27, _ = rack_two('rack_blu27_x2', 0.235, 0.24, 0.02)
    st_bru61, _ = rack_four('rack_bru61_x4', 0.095, 0.15)
    st_mer, _ = rack_six('rack_mer_x6', 0.1365, 0.158)
    st_lau88, _ = rack_three_lau88('rack_lau88_x3', 0.1525, 0.20)
    shutil.copyfile(os.path.join(TEX, 'gbu38.png'), os.path.join(TEX, 'f16c_rack.png'))

    def adapter(model_rack, stations):
        return OrderedDict([('Model', f'{NS}:custom_geo/aircraft_stores/{model_rack}.geo.json'),
                            ('Texture', f'{NS}:textures/aircraft_stores/f16c_rack.png'),
                            ('MountAnchor', [0.0, 0.0, 0.0]), ('Stations', stations)])

    # ================================================================== stores
    # ---- air-to-air: AIM-7M (reuses the AIM-7E body and its flight model; same airframe class)
    src = json.load(open(os.path.join(STORES, 'munition/aim7e.json'), encoding='utf-8'), object_pairs_hook=OrderedDict)
    d = OrderedDict()
    for k, v in src.items():
        d[k] = v
    d['Name'] = 'AIM-7M Sparrow · semi-active radar'
    d['MassKg'] = 231.3
    d['Guidance'] = OrderedDict([('Mode', 'SEMI_ACTIVE_RADAR'), ('LockTicks', 0), ('Range', 3500), ('ConeDegrees', 25),
                                 ('CountermeasureVulnerability', 0.85)])
    d['Flight'] = OrderedDict([('InitialSpeed', 0.5), ('MaxSpeed', 12), ('AccelerationPerTick', 0.1), ('TurnDegreesPerSecond', 40),
                               ('Damage', 55), ('BlastRadius', 5), ('TntEquivalentKg', 14.148)])
    cat.add('munition/aim7m', d)

    # ---- AGM-65G / AGM-65D x3 (LAU-88)
    a, launch, g = anchors_for('agm65g', missile=True)
    flight = OrderedDict([('InitialSpeed', 2), ('MaxSpeed', 10), ('AccelerationPerTick', 0.2), ('TurnDegreesPerSecond', 135),
                          ('Damage', 1400), ('BlastRadius', 1.25), ('TntEquivalentKg', 49.8), ('MaxLoadFactorG', 18.75), ('BodyTurnLimitScale', 3)])
    guid = OrderedDict([('Mode', 'GROUND_INFRARED'), ('Presentation', 'FIRE_AND_FORGET'), ('LockTicks', 60), ('Range', 600),
                        ('ConeDegrees', 30), ('CountermeasureVulnerability', 1)])
    cat.add('munition/agm65g', store('AGM-65G Maverick · imaging IR fire-and-forget', 'AIR_TO_GROUND', 'agm65g', a, Capacity=1, MassKg=303.5,
                                     Guidance=guid, Flight=flight, AmmoItem='superbwarfare:medium_air_to_ground_missile',
                                     LaunchOffset=launch))
    flight_d = OrderedDict(flight); flight_d['Damage'] = 1200; flight_d['TntEquivalentKg'] = 51.1948
    cat.add('munition/agm65d_x3', store('AGM-65D Maverick · LAU-88 triple rack', 'AIR_TO_GROUND', 'agm65g', a, Capacity=1, MassKg=226.8,
                                        Guidance=guid, Flight=flight_d, AmmoItem='superbwarfare:medium_air_to_ground_missile',
                                        MaxPerPylon=3, FixedRackCount=3, RackAdapter=adapter('rack_lau88_x3', st_lau88),
                                        RackSpacing=[0.60, 0.40, 0], RackMassKg=90.0, LaunchOffset=launch))

    # ---- AGM-84D Harpoon (CRUISE, active surface radar like kh32)
    a, launch, g = anchors_for('agm84d', missile=True)
    cat.add('munition/agm84d', store('AGM-84D Harpoon · active-radar anti-ship', 'CRUISE', 'agm84d', a, Capacity=1, MassKg=549.2,
                                     Guidance=OrderedDict([('Mode', 'ACTIVE_SURFACE_RADAR'), ('LockTicks', 60), ('Range', 820),
                                                           ('ConeDegrees', 30), ('CountermeasureVulnerability', 0.5)]),
                                     Flight=OrderedDict([('InitialSpeed', 0.3), ('MaxSpeed', 2.5), ('AccelerationPerTick', 0.025),
                                                         ('TurnDegreesPerSecond', 30), ('Damage', 75), ('BlastRadius', 7),
                                                         ('TntEquivalentKg', 97.52), ('Range', 820)]),
                                     AmmoItem='superbwarfare:cruise_missile', LaunchOffset=launch))

    # ---- AGM-88C (the pack's AGM-88 body and flight model; WT mass)
    src = json.load(open(os.path.join(STORES, 'fa18e/agm88.json'), encoding='utf-8'), object_pairs_hook=OrderedDict)
    d = OrderedDict((k, v) for k, v in src.items())
    d['Name'] = 'AGM-88C HARM · anti-radiation'
    d['MassKg'] = 362.9
    cat.add('munition/agm88c', d)

    # ---- bombs
    def bomb(rel, name, model, mass, mode, blast, tnt, dmg, ammo, profile, *, turn=2, glide=None, copies=1, rack=None,
             rack_mass=None, spacing=None, pen=None, manual=None):
        a, launch, g = anchors_for(model)
        b = OrderedDict([('Mode', mode), ('Gravity', 0.08), ('DragMultiplier', 1), ('TurnDegreesPerTick', turn),
                         ('BlastRadius', blast), ('TntEquivalentKg', tnt), ('BlastDamage', dmg)])
        if glide:
            b['LiftToDrag'], b['BestGlideSpeed'], b['MaxG'] = glide
        if pen:
            b['Penetrator'] = pen
        extra = OrderedDict()
        if copies > 1:
            extra['MaxPerPylon'] = copies; extra['FixedRackCount'] = copies; extra['RackAdapter'] = rack
            extra['RackSpacing'] = spacing
            if rack_mass:
                extra['RackMassKg'] = rack_mass
        cat.add(rel, store(name, 'BOMB', model, a, Capacity=1, MassKg=mass, Bomb=b, AmmoItem=ammo,
                           ProjectileProfile=f'{NS}:aircraft_bombs/{profile}', **extra, LaunchOffset=launch))

    H6_R, H6_T, H6_D = 7, 117.585, 450          # 87.1 kg H6 x1.35: same as the pack's GBU-12 / Mk 82
    MK84_R, MK84_T, MK84_D = 14, 578.61, 900   # 428.6 kg H6 x1.35: same as the pack's Mk 84
    PEN = OrderedDict([('MaxDepthBlocks', 4), ('MaxBlockHardness', 12), ('FuzeDelayTicks', 6)])
    bomb_profile('gbu24', 'gbu24'); bomb_profile('gbu31', 'gbu31'); bomb_profile('gbu38', 'gbu38'); bomb_profile('gbu54', 'gbu54')
    bomb_profile('gbu62', 'gbu62'); bomb_profile('gbu62l', 'gbu62l'); bomb_profile('gbu64', 'gbu64'); bomb_profile('gbu39', 'gbu39')
    bomb_profile('gbu53', 'gbu53'); bomb_profile('blu27', 'blu27')
    SG, LG, SF, MF = 'superbwarfare:small_guided_bomb', 'superbwarfare:large_guided_bomb', 'superbwarfare:small_freefall_bomb', 'superbwarfare:large_freefall_bomb'
    bomb('munition/gbu24', 'EGBU-24B Paveway III · BLU-109 laser', 'gbu24', 1079.6, 'LASER', 11, 286.386, 900, LG, 'gbu24', pen=PEN)
    bomb('munition/gbu31', 'GBU-31 JDAM · Mk 84 GPS', 'gbu31', 893.6, 'GPS', MK84_R, MK84_T, MK84_D, LG, 'gbu31')
    bomb('munition/gbu38', 'GBU-38 JDAM · 500 lb GPS', 'gbu38', 253.1, 'GPS', H6_R, H6_T, H6_D, SG, 'gbu38')
    bomb('munition/gbu38_x2', 'GBU-38 JDAM · BRU-57 twin rack', 'gbu38', 253.1, 'GPS', H6_R, H6_T, H6_D, SG, 'gbu38',
         copies=2, rack=adapter('rack_bru57_x2', st_bru57), spacing=[0.36, 0.4, 0])
    bomb('munition/gbu39_x4', 'GBU-39/B SDB · BRU-61 quad rack', 'gbu39', 121.6, 'GPS', 4, 19.6, 185, SG, 'gbu39', glide=(7, 5, 2.5),
         copies=4, rack=adapter('rack_bru61_x4', st_bru61), spacing=[0.30, 0.30, 0], rack_mass=147.1)
    bomb('munition/gbu53', 'GBU-53/B StormBreaker · GPS/INS', 'gbu53', 92.5, 'GPS', 3.5, 12.43, 145, SG, 'gbu53', glide=(7, 5, 2.5))
    bomb('munition/gbu53_x4', 'GBU-53/B StormBreaker · BRU-61 quad rack', 'gbu53', 92.5, 'GPS', 3.5, 12.43, 145, SG, 'gbu53', glide=(7, 5, 2.5),
         copies=4, rack=adapter('rack_bru61_x4', st_bru61), spacing=[0.30, 0.30, 0], rack_mass=147.1)
    bomb('munition/gbu54', 'GBU-54B LJDAM · 500 lb laser', 'gbu54', 260.8, 'LASER', H6_R, H6_T, H6_D, SG, 'gbu54')
    bomb('munition/gbu54_x2', 'GBU-54B LJDAM · BRU-57 twin rack', 'gbu54', 260.8, 'LASER', H6_R, H6_T, H6_D, SG, 'gbu54',
         copies=2, rack=adapter('rack_bru57_x2', st_bru57), spacing=[0.36, 0.4, 0])
    bomb('munition/gbu62', 'GBU-62 JDAM-ER · 500 lb GPS', 'gbu62', 289.7, 'GPS', H6_R, H6_T, H6_D, SG, 'gbu62', glide=(6, 6, 3))
    bomb('munition/gbu62_x2', 'GBU-62 JDAM-ER · BRU-55 twin rack', 'gbu62', 289.7, 'GPS', H6_R, H6_T, H6_D, SG, 'gbu62', glide=(6, 6, 3),
         copies=2, rack=adapter('rack_bru57_x2', st_bru57), spacing=[0.36, 0.4, 0])
    bomb('munition/gbu62l', 'GBU-62 LJDAM-ER · 500 lb laser', 'gbu62l', 289.7, 'LASER', H6_R, H6_T, H6_D, SG, 'gbu62l', glide=(6, 6, 3))
    bomb('munition/gbu62l_x2', 'GBU-62 LJDAM-ER · BRU-55 twin rack', 'gbu62l', 289.7, 'LASER', H6_R, H6_T, H6_D, SG, 'gbu62l', glide=(6, 6, 3),
         copies=2, rack=adapter('rack_bru57_x2', st_bru57), spacing=[0.36, 0.4, 0])
    bomb('munition/gbu64', 'GBU-64 JDAM-ER · Mk 84 GPS', 'gbu64', 987.0, 'GPS', MK84_R, MK84_T, MK84_D, LG, 'gbu64', glide=(6, 6, 3))
    bomb('munition/blu27', 'BLU-27/B · 750 lb fire bomb', 'blu27', 401.4, 'DUMB', 9, 70, 300, MF.replace('large', 'medium'), 'blu27', turn=0)
    bomb('munition/blu27_x2', 'BLU-27/B · 750 lb fire bomb · BRU-57 twin rack', 'blu27', 401.4, 'DUMB', 9, 70, 300,
         MF.replace('large', 'medium'), 'blu27', turn=0, copies=2, rack=adapter('rack_blu27_x2', st_blu27), spacing=[0.55, 0.4, 0])
    # Mk 82 MER: reuses the pack's Mk 82 body and projectile profile
    src = json.load(open(os.path.join(STORES, 'munition/mk82_x3.json'), encoding='utf-8'), object_pairs_hook=OrderedDict)
    d = OrderedDict()
    for k, v in src.items():
        if k in ('MaxPerPylon', 'FixedRackCount', 'RackAdapter', 'RackSpacing'):
            continue
        d[k] = v
    d['Name'] = 'Mk 82 · 500 lb freefall · MER six-bomb rack'
    la = d.pop('LaunchOffset')
    d['MaxPerPylon'] = 6; d['FixedRackCount'] = 6; d['RackAdapter'] = adapter('rack_mer_x6', st_mer)
    d['RackSpacing'] = [0.47, 0.45, 0]; d['RackColumns'] = 2; d['RackMassKg'] = 83.4
    d['LaunchOffset'] = la
    cat.add('munition/mk82_x6', d)

    # ---- rocket pods (unguided: the aircraft's own rocket weapon channel fires them, like the pack's M260/M261)
    def pod(rel, name, model, rounds, kg_per_round, anchors_manual=None):
        a, launch, g = anchors_for(model, manual=anchors_manual)
        cat.add(rel, store(name, 'ROCKET_POD', model, a, Capacity=rounds, Scale=1, MassKg=kg_per_round,
                           AmmoItem='superbwarfare:small_unguided_rocket', LaunchOffset=launch))

    def cluster_anchor(centres, L, d):
        r = d / 2
        ytop = r - 0.01 + 0.024 - 0.0     # lug top on the carriage bar
        ymin = min(c[1] for c in centres) - r
        ymid = (ytop + ymin) / 2
        xs = max(abs(c[0]) for c in centres) + r
        top = [0.0, ytop, 0.0]
        return dict(top=top, left=[xs, ymid, 0.0], right=[-xs, ymid, 0.0], axis=[0.0, ymid, 0.0])
    pod('munition/lau3a_ffar', 'LAU-3/A · 3 x 19 FFAR 2.75 in', 'lau3a_ffar', 57, 12.09, cluster_anchor(c3a, 1.40, 0.40))
    pod('munition/lau68_hydra', 'LAU-68 · 7 x Hydra 70', 'lau68_hydra', 7, 14.77)
    pod('munition/lau61_hydra', 'LAU-61 · 19 x Hydra 70', 'lau61_hydra', 19, 13.48)

    # ---- APKWS: laser-guided rockets (LASER_GUIDED, 3 x LAU-131 = 21 rounds)
    write_json(os.path.join(SBW, 'projectile_profiles/aircraft_stores/apkws.json'), apkws_profile())
    write_json(os.path.join(SBW, 'guns/aircraft_stores/apkws.json'), apkws_gun())
    a, launch, g = anchors_for('lau131_apkws', manual=cluster_anchor(c131, 1.806, 0.257))
    cat.add('munition/lau131_apkws', store('LAU-131 · 3 x 7 APKWS II laser rockets', 'LASER_GUIDED', 'lau131_apkws', a, Capacity=21, Scale=1,
                                           ProjectileProfile=f'{NS}:aircraft_stores/apkws', LaunchGunProfile=f'{NS}:aircraft_stores/apkws',
                                           MassKg=20.66, AmmoItem='superbwarfare:small_atgm', LaunchOffset=launch))

    # ---- fuel tanks
    for tid, name, mass in (('tank_370gal', '370 US gal wing fuel tank', 1206.0), ('tank_300gal', '300 US gal centreline fuel tank', 980.0)):
        a, launch, g = anchors_for(tid)
        cat.add('munition/' + tid, store(name, 'VISUAL_ONLY', tid, a, Capacity=1, MassKg=mass, LaunchOffset=launch))

    # ---- sensors
    a, launch, g = anchors_for('sniper_pod')
    eye = munitions.to_anchor_frame([g.x0, g.y0, g.zmin + 0.02])
    cat.add('sensor/sniper_pod', store('AN/AAQ-33 Sniper ATP · targeting pod', 'TARGETING_POD', 'sniper_pod', a, Item='minecraft:spyglass',
                                       Capacity=1, MassKg=202.0,
                                       TargetingPod=OrderedDict([('Eye', vec(eye)), ('YawLimit', 150), ('PitchMin', -15), ('PitchMax', 90),
                                                                 ('MaxZoom', 24), ('Range', 8192)])))
    a, launch, g = anchors_for('asq213_hts')
    cat.add('sensor/asq213_hts', store('AN/ASQ-213 HARM targeting system pod', 'VISUAL_ONLY', 'asq213_hts', a, Capacity=1, MassKg=45.0))
    print('written', len(cat.items), 'stores')


def apkws_profile():
    w = OrderedDict
    return w([('Combat', w([('WeaponId', f'{NS}:aircraft_stores/apkws'), ('RoundId', f'{NS}:munition/lau131_apkws'),
                            ('MunitionType', f'{NS}:atgm'), ('DamageType', f'{NS}:chemical'), ('CaliberMm', 70), ('DiameterMm', 70),
                            ('HullDamageClass', 'HE'), ('HullDamage', 60), ('ModuleDamage', 40), ('AmmoRackDamage', 100),
                            ('PenetrationMm', 40), ('Tandem', False)])),
              ('VisualProfile', f'{NS}:authored_missile'), ('ImpactVisualProfile', f'{NS}:atgm'), ('MotionSync', 'INHERIT'),
              ('Collision', w([('Width', 0.08), ('Height', 0.08)])), ('Luminance', 9), ('TrailMode', 'REPLACE'), ('RenderScale', 1),
              ('GuidedPropulsion', w([('InitialSpeed', 2), ('MaxSpeed', 9), ('AccelerationPerTick', 0.14), ('ThrustDurationTicks', 60),
                                      ('MaxTurnRateDegreesPerSecond', 60), ('GuidanceLookAheadTicks', 10), ('IgnitionDelayTicks', 4),
                                      ('EjectionSpeed', 2), ('EjectionGravityPerTick', 0.0125)])),
              ('Extensions', w([
                  (f'{NS}:projectile_mesh_v1', w([('Model', f'{NS}:custom_geo/aircraft_stores/apkws.geo.json'),
                                                  ('Texture', f'{NS}:textures/aircraft_stores/apkws.png'), ('ForwardYaw', 0),
                                                  ('SpinDegreesPerTick', 0)])),
                  ('superbwarfare:guided_maneuver_v1', w([('MaxLoadFactorG', 10), ('SeekerConeDegrees', 30),
                                                          ('SeekerRateDegreesPerSecond', 32), ('BodyTurnLimitScale', 2)])),
                  ('superbwarfare:warhead_fragments_v1', w([('Count', 24), ('Range', 1), ('Damage', 3), ('MaxHitsPerTarget', 2)])),
                  ('superbwarfare:heavy_warhead_blast_v1', w([('InnerRadius', 0.4), ('OuterRadius', 0.9), ('HeavyDamageFraction', 0.1),
                                                              ('LightDamageFraction', 0.2)])),
                  (f'{NS}:projectile_effect_v1', w([('Schema', 1), ('Trail', w([
                      ('Emitter', w([('Flame', f'{NS}:rocket_flame'), ('Smoke', f'{NS}:rocket_smoke')])),
                      ('Phases', [w([('Name', 'BOOST'), ('StartTick', 0), ('EndTickExclusive', 40), ('Flame', True), ('Smoke', True),
                                     ('Scale', 0.2), ('Orbit', None)]),
                                  w([('Name', 'COAST'), ('StartTick', 40), ('EndTickExclusive', 2147483647), ('Flame', False), ('Smoke', True),
                                     ('Scale', 0.8), ('Orbit', None)])]),
                      ('SpacingBlocks', 0.25), ('RatePerTick', None), ('LifetimeTicks', w([('Flame', 6), ('Smoke', 20)])),
                      ('WidthBlocks', 0.1), ('AttachmentOffset', [0, 0, 0]), ('VelocityInheritance', 0), ('RandomScale', [0.8, 1.3])])),
                      ('Impact', w([('Explosion', 'medium'), ('WaterExplosion', 'same')]))]))]))])


def apkws_gun():
    w = OrderedDict
    return w([('Name', 'APKWS II laser rocket'), ('AmmoType', 'superbwarfare:small_atgm'), ('DefaultFireMode', 'Semi'),
              ('AvailableFireModes', 'Semi'), ('Magazine', 1), ('EmptyReloadTime', 400), ('RPM', 30),
              ('Projectile', w([('Type', 'superbwarfare:wire_guide_missile'), ('Profile', f'{NS}:aircraft_stores/apkws')])),
              ('ProjectileAmount', 1), ('AddShooterDeltaMovement', True), ('Velocity', 2), ('Gravity', 0.0125), ('ProjectileLife', 800),
              ('Spread', 0), ('Damage', 120), ('ExplosionDamage', 40), ('ExplosionRadius', 0.5), ('TntEquivalentKg', 5.9),
              ('ShellType', 'HE'), ('ApDurability', 0), ('Crosshair', '@Empty'), ('SoundRadius', 24),
              ('SoundInfo', w([('Fire1P', 'superbwarfare:bmp_2_missile_fire_1p'), ('Fire3P', 'superbwarfare:bmp_2_missile_fire_3p')]))])


if __name__ == '__main__':
    main(sys.argv[1:])
