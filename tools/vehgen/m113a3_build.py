#!/usr/bin/env python3
"""M113A3 (m113a3): the supplied M113A3.mtb fitted as a tracked, amphibious APC on the pack's BMD-1 rig.

    python3 tools/vehgen/m113a3_build.py [--write] [--sources DIR] [--dump OUT.json]
    (then m113a3_icon.py, tools/icon_flags/flags.py --write m113a3, obb_armor_cover.py m113a3, exhaust_check.py m113a3)

The source has 571 elements. By part (toolbox frame +x forward, +y down, +z left):

    0   hull (222 boxes): sponsons, glacis, roof, hatches, engine-deck grilles, lamps, antenna, ...
    1   the LOWERED rear ramp (one slab, element 54): dropped, the ramp is shut (owner: hatches and ramp closed)
    2   the upright (shut) rear ramp and its hinges / latches (53, 298-315): hull
    3   the cupola mount: shield plate, seat base, pintle post and cradle (16 elements, rotation point (1, -29, 0))
    4   the M2HB: receiver, barrel, sights, spade grips, belt (67 elements, rotation point (16.5, -33.5, 0))
    9   left running gear: five paired road wheels (24 elements each) and a blocky three-box sprocket (90-92) and idler (93-95)
    10  right running gear: the same (22 elements each), sprocket 559-561 and idler 562-564
    14  four unit-cube track links at the origin: dropped

The left wheels sit 1.8 units outboard of the mirror of the right ones (they poke into the side skirt); the right
side is the one that fits the hull, so the left wheels are its mirror image. The blocky sprocket / idler stacks (and
the hull's inner sprocket blocks, 96-101) are replaced by a built toothed sprocket (front) and a plain idler (rear),
like puma_build.py; the BMD-1's 68-link belt is widened to the T130E1 shoe (381 mm) and fitted round all seven
wheels per side by tools/track_path/belt.py. Not in the source and added: the two A3 external fuel tanks flanking the
rear ramp. The source is blocky art at about 18.5 units per metre; the hull (nose to shut ramp) is 89.9 units and the
Army manual gives 4.864 m, so the uniform scale is 0.952 px per unit at the pack's 17.6 px per metre.

Systems: the BMD-1 data skeleton (tracked, 5 road wheels), no turret, the M2HB of the TUSK as a passenger weapon
station on the hull (Toyota technical precedent: Parent HULL, the station seat is the commander's), War Thunder
it_m113a1_tow on top (the closest WT M113: 11.2 t, 64 km/h, M2HB 575 rpm, 100 round belts, -10/+50, +-120).
"""
import copy
import io
import json
import math
import os
import sys

import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import mtbgeo  # noqa: E402
import vehgen  # noqa: E402
import fit_existing  # noqa: E402
import fit_specs  # noqa: E402

mtb = mtbgeo.mtb
VID = 'm113a3'
TEMPLATE = 'bmd_1'
SOURCE = 'M113A3.mtb'
SCALE = 0.952            # px per toolbox unit: the hull (89.9 units nose to shut ramp) = 4.864 m (TM 55-2350-224-14) at 17.6 px / m
XC = 1.15                # toolbox x of the hull centre (hull x 46.1 .. -43.8)
WHEEL_BOTTOM = 1.0       # px: wheel bottoms over the ground plane (the link plate under them)
TPL_W, TPL_H = 704, 512  # the BMD-1 texture; its track-link texels are cols 512-579, rows 0-63
LINK_CROP = (512, 0, 580, 64)
TEX_W, TEX_H = 1024 + 68, 1024   # the model's 1024 x 1024 plus the 68 link columns, pasted at x 1024
TRACK_WIDTH = 6.7        # px: T130E1 shoe, 381 mm at 17.6 px per m
LINK_THICK = 2.4         # the BMD-1 plate is 0.83 px thick; the T130E1 pad plus end connector is about 2 px (0.11 m)
TRACK_Z = 19.6           # |toolbox z| of the wheel pair centre = the track plane

RIGHT_BLOCKS = [(449, 470), (471, 492), (493, 514), (515, 536), (537, 558)]    # right road wheels, front to rear
ROAD_X = [28.0, 15.0, 3.0, -10.0, -23.0]          # toolbox x of the road wheel centres (hub boxes), front to rear
WHEEL_Y = 3.0                                     # toolbox y of the road wheel axles
SPROCKET = (39.8, -0.8, 5.4)      # toolbox x, y, radius: front, raised (the old three-box stack stood here)
IDLER = (-35.8, 2.0, 5.4)         # rear
OLD_RUNNING_STACKS = list(range(90, 96)) + list(range(559, 565)) + list(range(96, 102))
DROP = {54, 565, 566, 567, 568}                  # lowered ramp, unit cubes at the origin
YAW_PIVOT = (1.0, -29.0, 0.0)                    # part 3 rotation point: the cupola axis
PITCH_PIVOT = (16.5, -33.5, 0.0)                 # part 4 rotation point: the M2 trunnion
MUZZLE = (39.7, -34.8, 0.0)                      # flash hider tip (element 153)
SIGHT_EYE = (1.0, -41.0, 0.0)                    # behind the receiver, 3.8 units over it: the line of sight clears the receiver top
GRIP_L = (3.0, -34.8, 1.5)
GRIP_R = (3.0, -34.8, -1.5)
# A3 external fuel tanks (TM 9-2350-277-10 shows them either side of the ramp; the shape is a drawing estimate):
# overall length 5.318 m = hull 4.864 m + the tank extension (0.454 m = 8.4 units)
TANK = dict(x_in=15.9, x_out=23.9, z_front=43.8, z_rear=52.2, y_bottom=5.0, y_top=23.0)     # geo units
# engine deck grille on the right (-x) side of the front deck (element 322, Box 699): the exhaust outlet
EXHAUST_PANEL = (-22.7, -12.2, 26.0, -28.6, -15.6)    # geo x0, x1, y top, z0, z1 (unit, y_off 0)

SEATS = {         # pelvis points of seated riders, toolbox frame (x forward, y down, z left)
    'driver': (20.0, -12.9, 13.0),
}


class MFrame(mtbgeo.Frame):
    """toolbox -> geo, hull centre on z 0: [z s, -y s + yOff, -(x - XC) s]."""

    def geo(self, p):
        x, y, z = p
        return np.array([z * self.s, -y * self.s + self.y_off, -(x - XC) * self.s])


def load(path):
    return vehgen.load(path)


class Model:
    def __init__(self, sources):
        self.src = mtbgeo.Source(os.path.join(sources, SOURCE))
        self.E = self.src.elements
        self.frame = MFrame(SCALE, 8.5 * SCALE + WHEEL_BOTTOM)     # wheel bottoms at toolbox y 8.5
        self.hull = [i for i, f in enumerate(self.E) if int(f[4]) in (0, 2) and i not in OLD_RUNNING_STACKS
                     and i not in DROP]
        self.yaw = [i for i, f in enumerate(self.E) if int(f[4]) == 3]
        self.pitch = [i for i, f in enumerate(self.E) if int(f[4]) == 4]
        self.wheels = [list(range(a, b + 1)) for a, b in RIGHT_BLOCKS]

    def geo(self, p):
        return self.frame.geo(p)

    def mesh(self, ids):
        return mtbgeo.mesh([self.E[i] for i in ids], self.frame, TEX_W, TEX_H)

    def box(self, ids):
        pts = np.array([self.geo(p) for i in ids for pos, _ in mtb.element_polys(self.E[i], 1024, 1024) for p in pos])
        return pts.min(0), pts.max(0)

    def uv_of(self, ids):
        """Texel (normalized, v up) at the centre of the largest face of the elements."""
        best, uv = 0.0, None
        for i in ids:
            for pos, tex in mtb.element_polys(self.E[i], 1024, 1024):
                q = np.array(tex, float)
                area = abs(np.ptp(q[:, 0]) * np.ptp(q[:, 1]))
                if area > best:
                    best, uv = area, (q[:, 0].mean() / TEX_W, 1 - q[:, 1].mean() / TEX_H)
        return uv


# ------------------------------------------------------------------ mesh helpers (as puma_build.py)
def merge(*meshes):
    out = {'normalized_uvs': True, 'positions': [], 'normals': [], 'uvs': [], 'polys': []}
    for m in meshes:
        po, no, uo = len(out['positions']), len(out['normals']), len(out['uvs'])
        out['positions'] += m['positions']
        out['normals'] += m['normals']
        out['uvs'] += m['uvs']
        for poly in m['polys']:
            out['polys'].append([[v[0] + po, v[1] + no, v[2] + uo] for v in poly])
    return out


def mirror_mesh(m):
    """The mesh mirrored in x (left side from the right): positions and normals x negated, faces re-wound."""
    return {'normalized_uvs': True,
            'positions': [[round(-p[0], 5), p[1], p[2]] for p in m['positions']],
            'normals': [[-n[0], n[1], n[2]] for n in m['normals']],
            'uvs': [list(u) for u in m['uvs']],
            'polys': [list(reversed(poly)) for poly in m['polys']]}


def solid(faces, uv, centre):
    """poly_mesh of planar faces (lists of 3D points), outward normals, every vertex on texel `uv`."""
    pm = {'normalized_uvs': True, 'positions': [], 'normals': [], 'uvs': [[round(uv[0], 6), round(uv[1], 6)]],
          'polys': []}
    for q in faces:
        q = [np.asarray(p, float) for p in q]
        n = np.cross(q[1] - q[0], q[2] - q[1])
        if np.linalg.norm(n) < 1e-9 and len(q) > 3:
            n = np.cross(q[2] - q[0], q[3] - q[2])
        ln = np.linalg.norm(n)
        if ln < 1e-9:
            continue
        n = n / ln
        if np.dot(n, np.mean(q, axis=0) - centre) < 0:
            q, n = q[::-1], -n
        pm['normals'].append([round(float(c), 5) for c in n])
        poly = []
        for p in q:
            pm['positions'].append([round(float(c), 5) for c in p])
            poly.append([len(pm['positions']) - 1, len(pm['normals']) - 1, 0])
        pm['polys'].append(poly)
    return pm


def disc(cx, cy, cz, profile, x0, x1, uv):
    """A prism along geo x from x0 to x1 over the (y, z) polygon `profile` (offsets from the axis centre cy, cz)."""
    centre = np.array([(x0 + x1) / 2, cy, cz])
    ring = [(cy + dy, cz + dz) for dy, dz in profile]
    faces = []
    n = len(ring)
    for k in range(n):
        a, b = ring[k], ring[(k + 1) % n]
        faces.append([[x0, a[0], a[1]], [x1, a[0], a[1]], [x1, b[0], b[1]], [x0, b[0], b[1]]])
    for x in (x0, x1):
        for k in range(n):
            a, b = ring[k], ring[(k + 1) % n]
            faces.append([[x, cy, cz], [x, a[0], a[1]], [x, b[0], b[1]]])
    return solid(faces, uv, centre)


def prism_y(profile_xz, y0, y1, uv):
    """A prism along geo y from y0 to y1 over the (x, z) polygon `profile_xz` (absolute geo x, z)."""
    pts = [(x, z) for x, z in profile_xz]
    centre = np.array([np.mean([p[0] for p in pts]), (y0 + y1) / 2, np.mean([p[1] for p in pts])])
    faces = []
    n = len(pts)
    for k in range(n):
        a, b = pts[k], pts[(k + 1) % n]
        faces.append([[a[0], y0, a[1]], [a[0], y1, a[1]], [b[0], y1, b[1]], [b[0], y0, b[1]]])
    for y in (y0, y1):
        for k in range(n):
            a, b = pts[k], pts[(k + 1) % n]
            faces.append([[centre[0], y, centre[2]], [a[0], y, a[1]], [b[0], y, b[1]]])
    return solid(faces, uv, centre)


def polygon(r, n, phase=0.0):
    return [(r * math.cos(math.radians(phase + 360.0 * k / n)), r * math.sin(math.radians(phase + 360.0 * k / n)))
            for k in range(n)]


def sprocket_profile(root, tip, teeth=16):
    out = []
    for k in range(teeth):
        a = 360.0 * k / teeth
        for da, r in ((-6.0, root), (-3.2, tip), (3.2, tip), (6.0, root)):
            out.append((r * math.cos(math.radians(a + da)), r * math.sin(math.radians(a + da))))
    return out


def shift_bone(b, dx=0.0, dy=0.0, dz=0.0):
    d = np.array([dx, dy, dz])
    pm = b.get('poly_mesh')
    if pm:
        for p in pm['positions']:
            p[:] = [round(p[k] + d[k], 5) for k in range(3)]
    for c in b.get('cubes', []):
        c['origin'] = [round(c['origin'][k] + d[k], 5) for k in range(3)]
        if 'pivot' in c:
            c['pivot'] = [round(c['pivot'][k] + d[k], 5) for k in range(3)]
    b['pivot'] = [round(b['pivot'][k] + d[k], 5) for k in range(3)]


def widen_links(bones, side, width, ky=1.0):
    """The BMD-1's link plates widened to the T130E1 shoe about the track centre, and thickened (about the pivot)."""
    k = None
    for n, b in bones.items():
        if n.startswith(f'trackRot{side}') and b.get('poly_mesh'):
            P = np.array(b['poly_mesh']['positions'])
            cx, cy = b['pivot'][0], b['pivot'][1]
            if k is None:
                k = width / float(np.ptp(P[:, 0]))
            for p in b['poly_mesh']['positions']:
                p[0] = round(cx + (p[0] - cx) * k, 5)
                p[1] = round(cy + (p[1] - cy) * ky, 5)
    return k


def bounds_of(geo):
    pts = [p for b in geo['bones'] if b.get('poly_mesh') for p in b['poly_mesh']['positions']]
    P = np.array(pts)
    return P.min(0), P.max(0)


def remap_link_uvs(geo):
    """The BMD-1's link texels (cols 512-579, rows 0-63 of its 704 x 512 texture) now sit at x 1024+ of this texture."""
    names = ('trackRot', 'trackMov', 'brokenTrack', 'crudeTrack', 'TrackL', 'TrackR')
    for b in geo['bones']:
        if not b['name'].startswith(names):
            continue
        pm = b.get('poly_mesh')
        if pm and pm['uvs']:
            new = []
            for u, v in pm['uvs']:
                up, vt = u * TPL_W, (1 - v) * TPL_H
                new.append([round((up - LINK_CROP[0] + 1024) / TEX_W, 6), round(1 - vt / TEX_H, 6)])
            pm['uvs'] = new
        for c in b.get('cubes', []):
            if 'uv' in c:
                c['uv'] = [round(c['uv'][0] - LINK_CROP[0] + 1024, 3), c['uv'][1]]


TRACK_X = {}


def build_geo(model, tpl):
    """The fitted model in final px: the BMD-1's bones and 68-link tracks, this model's meshes."""
    g = copy.deepcopy(tpl)
    geo = g['minecraft:geometry'][0]
    geo['description']['identifier'] = f'geometry.{VID}'
    geo['description']['texture_width'] = TEX_W
    geo['description']['texture_height'] = TEX_H
    geo['bones'] = [b for b in geo['bones'] if 'Return' not in b['name']]
    bones = {b['name']: b for b in geo['bones']}
    keep_mesh = ('trackRot', 'trackMov', 'brokenTrack', 'crudeTrack', 'TrackL', 'TrackR')
    for b in geo['bones']:
        if not b['name'].startswith(keep_mesh):
            b.pop('poly_mesh', None)
            b.pop('cubes', None)
    remap_link_uvs(geo)
    s = model.frame.s
    g_ = model.geo
    uv_tank = model.uv_of([75])
    # --- hull: the source's hull plus the A3 fuel tanks and their fittings
    parts = [model.mesh(model.hull)]
    parts += tanks(model, uv_tank)
    bones['hull']['poly_mesh'] = merge(*parts)
    bones['hull']['pivot'] = [0, round(float(g_((0, WHEEL_Y, 0))[1]), 5), 0]
    # --- no turret: the empty turret / barrel bones stay (the template rig and wreck skeleton), at the origin
    for n in ('turret', 'barell'):
        bones[n]['pivot'] = [0, 0, 0]
    # --- the commander's M2HB station: the cupola mount turns (yaw), the gun elevates about its trunnion (pitch)
    yaw_piv = [round(float(v), 5) for v in g_(YAW_PIVOT)]
    pit_piv = [round(float(v), 5) for v in g_(PITCH_PIVOT)]
    i = [k for k, b in enumerate(geo['bones']) if b['name'] == 'barell'][0]
    geo['bones'][i + 1:i + 1] = [
        {'name': 'passengerWeaponStation', 'parent': 'hull', 'pivot': list(yaw_piv)},
        {'name': 'passengerWeaponStationYaw', 'parent': 'passengerWeaponStation', 'pivot': list(yaw_piv),
         'poly_mesh': model.mesh(model.yaw)},
        {'name': 'passengerWeaponStationPitch', 'parent': 'passengerWeaponStationYaw', 'pivot': list(pit_piv),
         'poly_mesh': model.mesh(model.pitch)}]
    bones = {b['name']: b for b in geo['bones']}

    # --- running gear: five road wheels (right side as modelled, left = its mirror), built sprocket and idler
    rim_uv = model.uv_of(model.wheels[0])
    cy_sprocket = float(g_((0, SPROCKET[1], 0))[1])
    for k in range(5):
        ring = model.mesh(model.wheels[k])
        P = np.array(ring['positions'])
        piv = [round(float((P[:, a].min() + P[:, a].max()) / 2), 5) for a in range(3)]
        for side, mesh in (('R', ring), ('L', mirror_mesh(ring))):
            b = bones[f'wheel{side}{k}']
            b['poly_mesh'] = mesh
            b['pivot'] = [piv[0] if side == 'R' else -piv[0], piv[1], piv[2]]
    xc_wheel = abs(piv[0])                                       # the last wheel's |x| (same for all five)
    for side, sign in (('L', 1), ('R', -1)):
        for name, (tx, ty, tr) in (('Front', SPROCKET), ('Rear', IDLER)):
            cy = float(g_((0, ty, 0))[1])
            cz = float(g_((tx, 0, 0))[2])
            r = tr * s
            xo = [sign * (TRACK_Z - 3.2) * s, sign * (TRACK_Z - 1.2) * s]       # inner disc
            xp = [sign * (TRACK_Z + 1.2) * s, sign * (TRACK_Z + 3.2) * s]       # outer disc
            hub = sorted([sign * (TRACK_Z - 3.2) * s, sign * (TRACK_Z + 3.2) * s])
            if name == 'Front':
                prof = lambda sc: sprocket_profile(0.86 * r * sc, r * sc)
            else:
                prof = lambda sc: polygon(r * sc, 24)
            parts = [disc(0, cy, cz, prof(1.0), *sorted(xo), rim_uv), disc(0, cy, cz, prof(1.0), *sorted(xp), rim_uv),
                     disc(0, cy, cz, polygon(0.55 * r, 16), *hub, rim_uv)]
            b = bones[f'wheel{side}{name}']
            b['poly_mesh'] = merge(*parts)
            b['pivot'] = [round(sign * TRACK_Z * s, 5), round(cy, 5), round(cz, 5)]

    # --- the track: the template's links widened to the T130E1 shoe and moved onto the wheel pair centre line
    tx = TRACK_Z * s
    for side, sign in (('L', 1), ('R', -1)):
        widen_links(bones, side, TRACK_WIDTH, LINK_THICK)
        ref = bones[f'trackRot{side}0']['pivot'][0]
        dx = sign * tx - ref
        for name, b in bones.items():
            if name.startswith((f'trackRot{side}', f'trackMov{side}', f'brokenTrack{side}', f'crudeTrack{side}',
                                f'Track{side}')):
                shift_bone(b, dx=dx)
        TRACK_X[side] = round(sign * tx, 5)
    lo, hi = bounds_of(geo)
    size = hi - lo
    geo['description']['visible_bounds_width'] = round(float(max(size[0], size[2]) / 16 + 1), 2)
    geo['description']['visible_bounds_height'] = round(float(size[1] / 16 + 1), 2)
    geo['description']['visible_bounds_offset'] = [0, round(float((lo[1] + hi[1]) / 32), 2), 0]
    return g


def tanks(model, uv):
    """The two A3 external fuel tanks: chamfered rectangular tanks either side of the shut ramp (geo units: x left,
    z = -toolbox x, rear plane 43.8), each with a filler cap on the top and two strap bands. Returns poly meshes in px."""
    out = []
    s, yo = model.frame.s, model.frame.y_off
    P = lambda x, z: (x * s, (z + XC) * s)
    c = 1.3                                                  # vertical edge chamfer
    for sign in (1, -1):
        x0, x1 = sorted((sign * TANK['x_in'], sign * TANK['x_out']))
        z0, z1 = TANK['z_front'], TANK['z_rear']
        yb, yt = TANK['y_bottom'] * s + yo, TANK['y_top'] * s + yo
        def ring(g):
            return [P(x0 + c - g, z0 - g), P(x1 - c + g, z0 - g), P(x1 + g, z0 + c - g), P(x1 + g, z1 - c + g),
                    P(x1 - c + g, z1 + g), P(x0 + c - g, z1 + g), P(x0 - g, z1 - c + g), P(x0 - g, z0 + c - g)]
        out.append(prism_y(ring(0.0), yb, yt, uv))
        for f in (0.25, 0.70):
            ys = yb + (yt - yb) * f
            out.append(prism_y(ring(0.4), ys - 0.5 * s, ys + 0.5 * s, uv))
        cx, cz = P(x1 - 2.6 if sign > 0 else x0 + 2.6, z1 - 2.6)
        cap = [(cx + 1.4 * s * math.cos(math.radians(a)), cz + 1.4 * s * math.sin(math.radians(a)))
               for a in range(0, 360, 45)]
        out.append(prism_y(cap, yt, yt + 1.0 * s, uv))
    return out


def fit_fallback_tracks(geo_path, tpl):
    """crudeTrack cubes and brokenTrack mesh of both sides: the template's envelope (z, y) mapped to the new belt."""
    g = load(geo_path)
    bones = {b['name']: b for b in g['minecraft:geometry'][0]['bones']}
    tb = {b['name']: b for b in tpl['minecraft:geometry'][0]['bones']}

    def env(bs, side):
        P = np.array([p for n, b in bs.items() if n.startswith(f'trackRot{side}') and b.get('poly_mesh')
                      for p in b['poly_mesh']['positions']])
        return P.min(0), P.max(0)
    for side in 'LR':
        (tlo, thi), (nlo, nhi) = env(tb, side), env(bones, side)
        k = (nhi - nlo) / (thi - tlo)
        c_new = (nlo + nhi) / 2
        c_old = (tlo + thi) / 2
        for name in (f'crudeTrack{side}', f'brokenTrack{side}'):
            old, new = tb[name], bones[name]
            if old.get('cubes'):
                cubes = copy.deepcopy(old['cubes'])
                for c in cubes:
                    o, sz = np.array(c['origin']), np.array(c['size'])
                    no = (o - c_old) * k + c_new
                    ns = sz * np.abs(k)
                    if 'pivot' in c:
                        c['pivot'] = [round(float(v), 5) for v in (np.array(c['pivot']) - c_old) * k + c_new]
                    c['origin'] = [round(float(v), 5) for v in no]
                    c['size'] = [round(float(v), 5) for v in ns]
                    if 'uv' in c:
                        c['uv'] = [round(c['uv'][0] - LINK_CROP[0] + 1024, 3), c['uv'][1]]
                new['cubes'] = cubes
            if old.get('poly_mesh'):
                pm = copy.deepcopy(old['poly_mesh'])
                pm['positions'] = [[round(float(v), 5) for v in (np.array(p) - c_old) * k + c_new] for p in pm['positions']]
                pm['uvs'] = [[round(((u * TPL_W) - LINK_CROP[0] + 1024) / TEX_W, 6),
                              round(1 - ((1 - v) * TPL_H) / TEX_H, 6)] for u, v in pm['uvs']]
                new['poly_mesh'] = pm
    with open(geo_path, 'w') as fh:
        json.dump(g, fh, separators=(',', ':'))


def px(model, p):
    """Toolbox point -> final model px [x, y, z] (+X left, +Y up, nose -Z)."""
    return [round(float(v), 4) for v in model.geo(p)]


def box_px(model, lo_hi):
    return [[round(float(v), 4) for v in lo_hi[0]], [round(float(v), 4) for v in lo_hi[1]]]


def seat_points(model):
    """Feet points (model px) of the 13 seats: driver, commander, 5 + 5 bench troops, the centre jump seat. A seated
    rider's hips are 12 px over the feet; the pelvis y is where the rider sits (toolbox y, negative up)."""
    t = lambda p, dy=0.0: [round(v, 4) for v in (np.array(px(model, p)) + [0, dy, 0])]
    seats = []
    seats.append(('Vehicle', t(SEATS['driver'], -12.0)))                                  # driver, front left
    # the commander: feet on the compartment floor under the cupola, standing, hands on the M2 spade grips
    feet_y_g = 11.4                                                                       # geo unit
    seats.append(('WeaponStation', [0.0, round(feet_y_g * model.frame.s + model.frame.y_off, 4),
                                    round(float(model.geo((1.0, 0, 0))[2]) + 0.0, 4)]))
    z_g = [-5.0, 4.0, 13.0, 22.0, 31.0]
    for sgn in (1, -1):                                                                   # left bench, right bench
        for zg in z_g:
            seats.append(('Vehicle', t((-zg - 0.0, -17.4, sgn * 19.4), -12.0)))
    seats.append(('Vehicle', t((-12.0, -12.5, 0.0), -12.0)))                              # centre jump seat, aft-facing
    return seats


def make_spec(model):
    t = lambda p: px(model, p)
    s, yo = model.frame.s, model.frame.y_off
    hull_ids = [i for i in model.hull if i not in (59, 60, 61, 62)]      # the whip antenna does not make collision
    hull_lo, hull_hi = model.box(hull_ids)
    hull_hi = np.array(hull_hi)
    hull_hi[2] = max(hull_hi[2], (TANK['z_rear'] + XC) * s)                 # over the A3 tanks
    # engine compartment: front right (-x), forward of the cargo bay, under the deck grilles
    e_lo = np.array([-22.7 * s, 8.4 * s + yo, (-45.5 + XC) * s])
    e_hi = np.array([1.5 * s, 24.9 * s + yo, (-11.0 + XC) * s])
    yaw_lo, yaw_hi = model.box(model.yaw)
    gun_lo, gun_hi = model.box(model.pitch)
    spec_seats = [{'frame': frame, 'at': at, 'fromTemplate': 1} for frame, at in seat_points(model)]
    return {
        'id': VID,
        'template': TEMPLATE,
        'hitbox': (2.9, 2.5),
        'geometry': {'scale': 1.0},
        'obb': [
            {'box': box_px(model, (hull_lo, hull_hi))},
            {'box': box_px(model, (e_lo, e_hi)), 'part': 'MainEngine'},
            {'box': box_px(model, (yaw_lo, yaw_hi)), 'transform': 'WeaponStation'},
            {'box': box_px(model, (gun_lo, gun_hi)), 'transform': 'WeaponStationBarrel'},
        ],
        'attachments': {
            'gunner2_camera': {'parent': 'WeaponStationBarrel', 'at': t(SIGHT_EYE)},
            'hmg_muzzle': {'parent': 'WeaponStationBarrel', 'at': t(MUZZLE)},
            'passenger_station_left_grip': {'parent': 'WeaponStationBarrel', 'at': t(GRIP_L)},
            'passenger_station_right_grip': {'parent': 'WeaponStationBarrel', 'at': t(GRIP_R)},
        },
        'seats': spec_seats,
        'dropWeapons': ['Cannon', 'Missile', 'MachineGun'],
        'weaponFrom': {'PassengerMachineGun': ('m1a2_abrams_sep_v2', 'PassengerMachineGun')},
    }


# ---------------------------------------------------------------------------- War Thunder data
# WT it_m113a1_tow (wiki.warthunder.com/unit/it_m113a1_tow, read 2026-10-23; the closest WT M113: the M113A1 chassis, this
# model carries a plain M2HB mount): 11.2 t, 64 / 15 km/h (Realistic), hull armour 38 / 44 / 38 mm (front / side / rear),
# 12.7 mm M2HB: 1,000 rounds in 100-round belts, 575 rpm, belt change 10.4 s basic / 8 s aced, pitch -10 .. +50,
# traverse -120 .. +120; M2HB belt default API-T / I / AP / API-T, penetration 31 / 29 / 21 / 14 / 9 / 6 mm at 10 / 100 /
# 500 / 1000 / 1500 / 2000 m. Turret rate for the station: the owner-approved WT M2HB reference 90 deg/s (aced) both axes.
BELT_ROUNDS = {
    # WT 12.7 mm M2HB: M20 API-T 929 m/s, M1 I 944 m/s, M2 AP 894 m/s
    'western_127_m20_api_t_1': ('RED', 929, None), 'western_127_m20_api_t_2': ('RED', 929, None),
    'western_127_m1_i': ('NONE', 944, None), 'western_127_m2_ap': ('NONE', 894, None),
}
# the TUSK's recipe: AP belt (M2 AP, M2 AP, M20 API-T) default, Universal (M20, M1 I, M2 AP, M20), APIT
M2_BELTS = [('AP Belt', [('western_127_m2_ap', 2, 'NONE'), ('western_127_m20_api_t_1', 1, 'T')]),
            ('Universal Belt', [('western_127_m20_api_t_2', 1, 'T'), ('western_127_m1_i', 1, 'NONE'),
                                ('western_127_m2_ap', 1, 'NONE'), ('western_127_m20_api_t_1', 1, 'T')]),
            ('APIT Belt', [('western_127_m20_api_t_1', 1, 'T')])]
KMH = 0.01088                          # MaxForwardSpeedRate per km/h (the M1A1 / TUSK / Puma scale: 0.816 = 75 km/h)
WT = {
    'Mass': 12.34,                     # 27,200 lb gross loaded (TM 9-2350-277-10); WT's A1 is 11.2 t
    'MaxForwardSpeedRate': round(64 * KMH, 4),      # TM 40 mph = 64 km/h = WT 64
    'MaxBackwardSpeedRate': round(15 * KMH, 4),     # WT 15 km/h reverse (TM: 6 mph)
    'StationTurnSpeed': [90.0, 90.0],
    'StationYaw': [-120, 120],
    'StationPitch': [-10, 50],
    'Buoyancy': 0.052,                 # the pack's float value (BMP-2, BTR-80A, PT-76B)
}
NATIVE_RATE = 1.15                     # VehicleEntity.turretTurnXSpeed / YSpeed multiply the data by 1.15


def finish(model, spec):
    """War Thunder and model-specific data on top of what fit_existing wrote."""
    for sub in ('ballistics', 'damage'):
        sys.path.insert(0, os.path.join(HERE, '..', sub))
    import wt_belts
    import wt_reloads
    import tracer_nations
    import balance
    import driver_cameras
    paths = fit_existing.files_of(VID)
    d = load(paths['data'])
    d['VehicleIcon'] = f'berts_vehicle_pack:textures/vehicle_icon/{VID}.png'
    d['Mass'] = WT['Mass']
    d['Type'] = 'APC'
    d['MaxEnergy'] = 7000000
    d['HUDColor'] = '0x66FF00'
    d['EngineInfo']['Buoyancy'] = WT['Buoyancy']
    d['EngineInfo']['MaxForwardSpeedRate'] = WT['MaxForwardSpeedRate']
    d['EngineInfo']['MaxBackwardSpeedRate'] = WT['MaxBackwardSpeedRate']
    d.pop('EngineSound', None)                           # the vehicle_audio profile (Lizardpyle) is the engine sound
    for k in ('TurretPos', 'BarrelPos', 'TurretControllerIndex', 'TurretTurnSpeed', 'TurretPitchRange'):
        d.pop(k, None)
    t, cls = balance.GROUND[VID]
    d['MaxHealth'] = balance.ground_hp(VID)
    d['DeathChargeKg'] = balance.death_charge_kg(VID)
    d['DamageClass'] = cls
    d['DamageModifiers'] = balance.modifiers(cls)

    # station data: the commander's M2HB on the hull (Toyota technical precedent, Parent HULL)
    d['PassengerWeaponStationControllerIndex'] = 1
    d['PassengerWeaponStationBinding'] = {'Schema': 'berts_vehicle_pack:passenger_bed_weapon_station/v1',
                                          'Parent': 'HULL', 'WeaponId': 'PassengerMachineGun',
                                          'WeaponKind': 'HEAVY_MACHINE_GUN', 'CameraAttachment': 'gunner2_camera',
                                          'MuzzleAttachment': 'hmg_muzzle'}
    d['PassengerWeaponStationTurnSpeed'] = [round(v / NATIVE_RATE, 4) for v in WT['StationTurnSpeed']]
    d['PassengerWeaponStationYawRange'] = WT['StationYaw']
    d['PassengerWeaponStationPitchRange'] = WT['StationPitch']

    # seats: 0 driver (hull, looks out of the driver's periscopes), 1 commander at the M2HB, 2-11 the bench troops
    # (five a side), 12 the aft-facing jump seat; rear dismount
    hull_lo, hull_hi = model.box(model.hull)
    rear = -(float(hull_hi[2]) / 16 + 1.3)
    dismount = {'Transform': 'Vehicle', 'Position': [0, 0.45, round(rear, 5)]}
    toy = load(os.path.join(vehgen.DATA, 'sbw', 'vehicles', 'toyota_jihad_dshk.json'))
    station_seat = copy.deepcopy(toy['Seats'][2])
    for i, seat in enumerate(d['Seats']):
        pos = seat['Position']
        if i == 1:
            keep = copy.deepcopy(station_seat)
            keep['Transform'] = 'WeaponStation'
            keep['BodyAttachment'] = 'WeaponStation'
            keep['Position'] = pos
            keep['MinPitch'], keep['MaxPitch'] = WT['StationPitch']
            keep['Weapons'] = ['PassengerMachineGun']
            keep['CameraPos'].update({'EyeAttachment': 'gunner2_camera', 'ZoomEyeAttachment': 'gunner2_camera',
                                      'DirectionAttachment': 'hmg_muzzle', 'ZoomDirectionAttachment': 'hmg_muzzle'})
            keep['OperatorPose'] = {'LeftHandAttachment': 'passenger_station_left_grip',
                                    'RightHandAttachment': 'passenger_station_right_grip',
                                    'MaxForwardLeanDegrees': 60, 'MaxBackwardLeanDegrees': 15}
            keep['MinYaw'], keep['MaxYaw'] = -360, 360
            d['Seats'][i] = keep
            continue
        new = {'HidePassenger': True, 'IsEnclosed': True, 'Transform': 'Vehicle', 'Position': pos,
               'BodyAttachment': 'Vehicle', 'Weapons': [], 'CanRotateHead': True, 'DismountInfo': dict(dismount)}
        if i == 0:
            new.pop('Weapons')
            new['CameraPos'] = driver_cameras.camera()
        d['Seats'][i] = new
    eye = model_driver_eye(model)
    d = driver_cameras.apply(d, {'eye': [round(float(v), 5) for v in eye], 'seat': 0})   # the pack's driver-camera rule
    # attachments the bmd_1 skeleton carried (turret camera, seats, muzzles) are gone: spec['attachments'] replaced them
    d.pop('WeaponPairs', None)

    # weapon: the TUSK's WT M2HB belts on the hull station
    w = d['Weapons']['PassengerMachineGun']
    shoot = fit_existing.vehgen.mtbgeo.geo_to_data(np.array(spec['attachments']['hmg_muzzle']['at']))
    gun_origin = fit_existing.frame_origin(d, 'WeaponStationBarrel')
    muzzle = shoot - gun_origin
    sp = w['ShootPos']
    z = [round(float(muzzle[0]), 5), round(float(muzzle[1]), 5), round(float(-muzzle[2]), 5)]
    sp.update({'Transform': 'WeaponStationBarrel', 'Directions': ['WeaponStationBarrel'], 'Positions': [z],
               'ViewPosition': z})
    w['Name'] = f'weapon.berts_vehicle_pack.{VID}_hmg'
    wt_belts.ROUNDS.update(BELT_ROUNDS)
    notes = []
    wt_belts.rebuild(VID, 'PassengerMachineGun', w, 'WESTERN_127', M2_BELTS, {}, {}, notes)
    w['Magazine'] = 100
    for e in w.get('ProjectileBeltAmmoType', []):
        rid = os.path.basename(e['Override']['Projectile']['Profile'])
        e['Override']['Name'] = {'belt_ammo_00_western_127_m1_i': 'I'}.get(rid, e['Override']['Name'])
    tracer_nations.fix(d, False, [])
    prof_dir = os.path.join(vehgen.DATA, 'sbw', 'projectile_profiles')
    default = w['Projectile']['Profile'].split(':', 1)[1]
    first = w['AmmoType'][0]['Override']['Projectile']['Profile'].split(':', 1)[1]
    if default != first:
        with open(os.path.join(prof_dir, default + '.json'), 'w') as fh:
            fh.write(json.dumps(load(os.path.join(prof_dir, first + '.json')), indent=2) + '\n')
    lang = load(os.path.join(vehgen.ASSETS, 'lang', 'en_us.json'))
    lang['weapon.berts_vehicle_pack.m113a3_hmg'] = '12.7 mm M2HB'
    wcls, changes = wt_reloads.plan(VID, 'PassengerMachineGun', w, '12.7 mm M2HB', d.get('Type'))
    w.update(changes)
    si = w.setdefault('SoundInfo', {})
    for k, new in wt_reloads.sound_plan(VID, 'PassengerMachineGun', wcls, w).items():
        if new is None:
            si.pop(k, None)
        else:
            si[k] = new
    if notes:
        print('belts:', notes)
    with open(paths['data'], 'w') as fh:
        fh.write(json.dumps(d, indent=2) + '\n')

    # exhaust: the right front engine-deck grille (model frame px / 16, +Z = rear)
    c = load(paths['client'])
    c['EngineExhaust']['Origins'] = [{'Id': 'engine_exhaust_group_origin_000',
                                      'Position': [round(v / 16, 5) for v in exhaust_origin(model)]}]
    with open(paths['client'], 'w') as fh:
        fh.write(json.dumps(c, indent=2) + '\n')
    return [round(float(v), 5) for v in eye]


def exhaust_box(model):
    """The right front grille (element 322's face) in model px, +Z = rear: (x0, x1, y0, y1, z0, z1)."""
    x0, x1, ytop, z0, z1 = EXHAUST_PANEL
    s, yo = model.frame.s, model.frame.y_off
    return (x0 * s, x1 * s, ytop * s + yo, ytop * s + yo, (z0 + XC) * s, (z1 + XC) * s)


def exhaust_origin(model):
    x0, x1, y0, y1, z0, z1 = exhaust_box(model)
    return [(x0 + x1) / 2, y1 + 0.25, (z0 + z1) / 2]


def model_driver_eye(model):
    """The driver's eye in data blocks: over the front edge of the driver's hatch, front left."""
    p = model.geo((DRIVER_EYE_TB[0], DRIVER_EYE_TB[1], DRIVER_EYE_TB[2]))
    return mtbgeo.geo_to_data(p)


DRIVER_EYE_TB = (28.0, -28.0, 13.0)       # hatch (elements 15-17) x 7..19 geo, z -26..-14 geo: 2 units ahead of its front edge, 2 over


def write_textures(model):
    """Entity texture (the model's own PNG plus the BMD-1's link texels at x 1024), wreck texture (x0.178)."""
    assets = vehgen.ASSETS
    own = Image.open(io.BytesIO(model.src.png)).convert('RGBA')
    tpl = Image.open(os.path.join(assets, 'textures', 'entity', f'{TEMPLATE}.png')).convert('RGBA')
    img = Image.new('RGBA', (TEX_W, TEX_H), (0, 0, 0, 0))
    img.paste(own, (0, 0))
    img.paste(tpl.crop(LINK_CROP), (1024, 0))
    img.save(os.path.join(assets, 'textures', 'entity', f'{VID}.png'))
    a = np.asarray(img).astype(float)
    dead = a.copy()
    dead[..., :3] = np.clip(np.round(a[..., :3] * vehgen.DEAD), 0, 255)
    Image.fromarray(dead.astype(np.uint8)).save(os.path.join(assets, 'textures', 'entity_dead', f'{VID}.png'))


# ---------------------------------------------------------------------------- armour
PLATES_MM = {'front': 38, 'side': 44, 'rear': 38, 'roof': 28, 'belly': 28}     # WT hull 38 / 44 / 38; roof, belly estimated
LINER_MM = 6                                                                    # the A3's spall liner (internal)


def plate(name, lo_hi_px, mm, rot=(0, 0, 0)):
    """An armour plate from a geo-px box: centre and half size in the armour frame (-x/16, y/16, z/16)."""
    lo, hi = np.array(lo_hi_px[0], float), np.array(lo_hi_px[1], float)
    c, h = (lo + hi) / 2, (hi - lo) / 2
    return {'name': name, 'center': [round(float(-c[0] / 16), 5), round(float(c[1] / 16), 5), round(float(c[2] / 16), 5)],
            'half_size': [round(float(max(h[0], 0.5) / 16), 5), round(float(max(h[1], 0.5) / 16), 5),
                          round(float(max(h[2], 0.5) / 16), 5)],
            'rotation': list(rot), 'frame': 'hull', 'armor_mm': mm}


def build_armor_m113(model):
    """Hull armour as 5083 aluminium plates at the War Thunder thicknesses plus the A3's spall liner and the engine."""
    s, yo = model.frame.s, model.frame.y_off
    G = lambda x, yg, zg: np.array([x * s, yg * s + yo, (zg + XC) * s])        # geo units (z = -toolbox x) -> px

    def B(x0, x1, y0, y1, z0, z1):
        return (G(x0, y0, z0), G(x1, y1, z1))
    plates = []
    # glacis: the 42 deg slab from the nose (y 8.4, z -46.1) up to the deck (y 22, z -31)
    glo, ghi = B(-15.7, 15.7, 8.4, 22.0, -46.1, -31.0)
    ang = math.degrees(math.atan2(22.0 - 8.4, 15.1))
    gl = plate('glacis', ((glo[0], (glo[1] + ghi[1]) / 2 - 0.5, (glo[2] + ghi[2]) / 2 - 0.5),
                          (ghi[0], (glo[1] + ghi[1]) / 2 + 0.5, (glo[2] + ghi[2]) / 2 + 0.5)), PLATES_MM['front'])
    gl['half_size'] = [round(float(15.7 * s / 16), 5), round(0.5 / 16, 5), round(float(math.hypot(13.6, 15.1) * s / 2 / 16), 5)]
    gl['rotation'] = [round(-ang, 2), 0, 0]
    plates.append(gl)
    plates.append(plate('front_lower', B(-16.3, 16.3, 4.8, 8.4, -46.1, -41.1), PLATES_MM['front']))
    plates.append(plate('front_block_left', B(23.0, 24.3, 8.4, 25.0, -46.1, -27.1), PLATES_MM['side']))
    plates.append(plate('front_block_right', B(-24.3, -23.0, 8.4, 25.0, -46.1, -27.1), PLATES_MM['side']))
    plates.append(plate('side_left', B(22.7, 24.3, 8.4, 25.0, -27.1, 38.0), PLATES_MM['side']))
    plates.append(plate('side_right', B(-24.3, -22.7, 8.4, 25.0, -27.1, 38.0), PLATES_MM['side']))
    plates.append(plate('rear_ramp', B(-15.5, 15.5, 0.0, 23.9, 35.8, 43.2), PLATES_MM['rear']))
    plates.append(plate('rear_post_left', B(15.2, 22.7, 4.6, 25.0, 37.7, 42.4), PLATES_MM['rear']))
    plates.append(plate('rear_post_right', B(-22.7, -15.2, 4.6, 25.0, 37.7, 42.4), PLATES_MM['rear']))
    plates.append(plate('roof_front', B(-22.7, 22.8, 23.4, 25.0, -27.1, -7.6), PLATES_MM['roof']))
    plates.append(plate('roof_rear', B(-22.7, 22.8, 23.4, 25.0, 4.9, 42.4), PLATES_MM['roof']))
    plates.append(plate('belly', B(-16.2, 16.3, -1.8, 4.8, -44.6, 39.1), PLATES_MM['belly']))
    plates.append(plate('sponson_floor_left', B(16.3, 22.8, 10.9, 11.4, -42.6, 39.4), PLATES_MM['belly']))
    plates.append(plate('sponson_floor_right', B(-22.7, -16.2, 10.9, 11.4, -42.6, 39.4), PLATES_MM['belly']))
    # the A3 spall liner (internal): a thin lining 1 unit inside the sides, the rear ramp and under the roof
    plates.append(plate('spall_liner_left', B(21.3, 22.0, 11.4, 23.0, -27.1, 36.0), LINER_MM))
    plates.append(plate('spall_liner_right', B(-22.0, -21.3, 11.4, 23.0, -27.1, 36.0), LINER_MM))
    plates.append(plate('spall_liner_rear', B(-21.0, 21.0, 4.8, 23.0, 34.0, 34.7), LINER_MM))
    plates.append(plate('spall_liner_roof', B(-21.0, 21.0, 22.2, 22.9, -7.6, 34.0), LINER_MM))
    # engine (Detroit 6V53T): front right, under the deck grilles
    engine = plate('engine_core_00', B(-22.0, 1.5, 11.0, 23.0, -43.0, -27.0), 0)
    engine.pop('armor_mm')
    return plates, [engine]


def my_build_armor(spec, tpl, geo, tpl_geo):
    a = copy.deepcopy(tpl)
    a['id'] = spec['id']
    plates, engines = build_armor_m113(MODEL)
    a['plates'], a['engines'] = plates, engines
    for k in ('sensitive_internals', 'ammo_racks', 'tracks', 'modules', 'explosive_reactive_armor'):
        a[k] = []
    a.pop('authoring_state', None)
    return a


MODEL = None


def client_skeleton(files, model):
    """The BMD-1 client resource with this vehicle's ids and five road wheels, no return rollers."""
    client = json.loads(json.dumps(load(files['client'])).replace(TEMPLATE, VID))
    rg = client['RunningGear']
    rg['RoadWheelCount'] = 5
    rg['TrackRender']['LinkHalfThickness'] = round(rg['TrackRender']['LinkHalfThickness'] * LINK_THICK, 5)
    rg['WheelBones'] = {'Left': [f'wheelL{k}' for k in range(5)] + ['wheelLFront', 'wheelLRear'],
                        'Right': [f'wheelR{k}' for k in range(5)] + ['wheelRFront', 'wheelRRear']}
    for side in rg['TrackRender']['Sides']:
        side['XCenter'] = TRACK_X[side['Side']]
        side['RoadCenters'] = [round(float(model.geo((x, 0, 0))[2]), 5) for x in ROAD_X]
    return client


def main(argv):
    global MODEL
    write = '--write' in argv
    sources = os.path.join(HERE, '..', '..', '..', 'source', 'mtb', 'elite_expansion')
    if '--sources' in argv:
        sources = argv[argv.index('--sources') + 1]
    sources = os.path.abspath(sources)
    model = MODEL = Model(sources)
    tpl = load(fit_existing.files_of(TEMPLATE)['geo'])
    geo = build_geo(model, tpl)
    if '--dump' in argv:
        with open(argv[argv.index('--dump') + 1], 'w') as fh:
            json.dump(geo, fh, separators=(',', ':'))
        return 0
    spec = make_spec(model)
    fit_specs.SPECS[VID] = spec
    files = fit_existing.files_of(TEMPLATE)
    client = client_skeleton(files, model)
    orig = {'geo': geo, 'wreck': load(files['wreck']), 'client': client, 'data': {}, 'armor': {}}
    fit_existing.originals = lambda vid, w: orig
    own_box = fit_existing.own_box

    def safe_box(g, bone):
        try:
            return own_box(g, bone)
        except (KeyError, ValueError):
            return own_box(g, 'hull')
    fit_existing.own_box = safe_box
    fit_existing.build_armor = my_build_armor
    fit_existing.register = lambda spec, out: None            # registration is done by hand-anchored insertions
    rc = fit_existing.main([VID] + (['--write'] if write else []))
    if write and rc == 0:
        sys.path.insert(0, os.path.join(HERE, '..', 'track_path'))
        import belt
        # the sprocket and the idler are real bones here: the template's authored links (BMD-1 positions) must not
        # invent a phantom end wheel past the idler, and nothing is cached in tools/track_path/fitted_wheels.json
        belt.link_end_circles = lambda bones, side, circles, h, vid=None: []
        _, msg = belt.rebuild(VID, write=True)
        print('belt:', msg)
        fit_fallback_tracks(fit_existing.files_of(VID)['geo'], tpl)
        eye = finish(model, spec)
        write_textures(model)
        print('driver_camera (data frame):', eye)
        print('EXITS box (model px):', [round(float(v), 3) for v in exhaust_box(model)], 'origin',
              [round(float(v), 3) for v in exhaust_origin(model)])
    return rc


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
