#!/usr/bin/env python3
"""SPz Puma (puma_ifv): the supplied SPZpuma.mtb fitted as a tracked IFV on the pack's Marder rig.

    python3 tools/vehgen/puma_build.py [--write] [--sources DIR] [--dump OUT.json]
    (then obb_armor_cover.py puma_ifv, puma_icon.py, tools/icon_flags/flags.py --write puma_ifv, exhaust_check.py puma_ifv)

The source is one part (0) of 517 boxes: hull, turret, gun, MELLS launcher and 12 road wheels, plus the open rear ramp (part
16, dropped) and the closed ramp (part 17, kept), and unit cubes and a track-link template at the origin (dropped).
Elements, by index (toolbox frame +x forward, +y down, +z left):

    0-172        hull (suspension arms, side modules, hatches, periscopes, rear lights ...)
    173-174      open rear ramp (part 16): dropped, the ramp is closed
    175-176      closed rear ramp (part 17): hull
    177-227, 516 turret: roof, sights, the PERI mast (209-227); 199-200 the launcher bracket
    245-257, 270 the MELLS dual launcher on the turret's left side (WT's Puma has no ATGM: model detail, not a weapon)
    228-244, 258-269 gun: mantlet, MK 30-2 tube, coax housing (237)
    272-510      road wheels (16 elements each: 8 bolts and 8 rim pieces), unit cubes among them are dropped

It is built as a new vehicle in two steps:
  1. geometry (this file): the pack's Marder bone hierarchy and its 96-link rubber track; the road wheels get a recessed
     core, the sprocket (front) and idler (rear) the source lacks are built; tools/track_path/belt.py then refits the
     belt round all eight wheels per side;
  2. systems (tools/vehgen/fit_existing.py, spec injected below): Marder data, armour layout and profiles, then the War
     Thunder Puma on top (MK 30-2/ABM belts, MG4 coax, 45 deg/s slew, -10/+45, 43 t, 75 / -10 km/h).
"""
import copy
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
VID = 'puma_ifv'
TEMPLATE = 'marder_1a5'
SOURCE = 'SPZpuma.mtb'
SCALE = 0.95             # px per toolbox unit: uniform; 7.5 m hull / 140 units at the pack's 1.1 x 16 px per m (PSM 7.6 x 3.9 m)
XC = 23.0                # toolbox x of the hull centre (hull -48 .. 92 with the closed ramp)
WHEEL_BOTTOM = 1.0       # px: wheel bottoms over the ground plane (the link plate under them)
TEX_W, TEX_H = 1216, 1024     # the model's 1024 columns plus the 192 the Marder's track-link bones sample (x 1024-1215)
TRACK_WIDTH = 8.17       # px: iDLT 464 E track, 0.464 m at 17.6 px per m

HULL = [i for i in range(0, 177) if i not in (173, 174)]
TURRET = list(range(177, 228)) + [516] + list(range(245, 258)) + [270]
GUN = list(range(228, 245)) + list(range(258, 270))
UNIT_CUBES_FROM = 271      # 271 and every unit cube / template from here on that is not a wheel piece
ROAD_X = [62.5, 48.0, 33.5, 15.5, 1.0, -13.5]       # toolbox x of the road wheel centres, front to rear
WHEEL_Y = 2.5
WHEEL_R = 6.5
RING = (25.5, 27.5)        # |z| of the rim ring
CORE = (24.75, 26.5)       # |z| of the recessed core disc added behind the ring
SPROCKET = (77.0, 0.0, 5.6)     # toolbox x, y, radius: front, raised (the track climbs to it)
IDLER = (-28.0, 0.0, 5.6)       # rear
TURRET_PIVOT = (0.0, -27.5, 6.0)    # turret ring centre (the base plates 201-208 are centred x 0, z 6)
GUN_PIVOT = (10.5, -35.5, -3.0)     # trunnion (the rotation point of the gun elements)
MUZZLE = (69.5, -36.15, -3.0)
COAX_MUZZLE = (38.4, -36.0, -7.5)
GUNNER_EYE = (30.6, -36.0, 14.5)    # ahead of the gunner's sight windows (elements 189-190) on the turret front
COMMANDER_EYE = (8.8, -53.0, 6.0)   # ahead of the PERI head (elements 222-227) on the mast
EXHAUST = (62.0, -13.0, -36.5)      # right hull side forward (engine front right), 0.25 px outside the side face (z -36.25)
SEATS = {          # pelvis points (EE1 seat fit from the PSM layout plan), toolbox frame
    'gunner': (14.0, -5.0, 12.0), 'commander': (14.0, -5.0, -12.0), 'driver': (34.0, -5.0, 12.0),
    'troopR1': (-4.0, -5.0, -12.9), 'troopR2': (-14.0, -5.0, -12.9), 'troopR3': (-24.0, -5.0, -12.9),
    'troopR4': (-34.0, -5.0, -12.9), 'troopL1': (-24.0, -5.0, 12.9), 'troopL2': (-34.0, -5.0, 12.9),
}


class PFrame(mtbgeo.Frame):
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
        ground = WHEEL_Y + WHEEL_R          # wheel bottoms at toolbox y 9.0
        self.frame = PFrame(SCALE, ground * SCALE + WHEEL_BOTTOM)
        self.wheel_ids = self.find_wheels()

    def geo(self, p):
        return self.frame.geo(p)

    def find_wheels(self):
        """{(side, index front to rear): [element ids]}: the non-cube elements from 272 on that are not the link template."""
        out = {}
        for i in range(272, 511):
            f = self.E[i]
            c = np.array(mtb.box_corners(f))
            if np.ptp(c, axis=0).max() <= 1.0 and abs(c).max() <= 1.0:
                continue                              # unit cube at the origin
            if not (RING[0] - 0.05 <= abs(c[:, 2]).min() and abs(c[:, 2]).max() <= RING[1] + 0.05):
                continue
            cx = (c[:, 0].min() + c[:, 0].max()) / 2
            k = int(np.argmin([abs(cx - x) for x in ROAD_X]))
            side = 'L' if c[:, 2].mean() > 0 else 'R'
            out.setdefault((side, k), []).append(i)
        return out

    def mesh(self, ids):
        return mtbgeo.mesh([self.E[i] for i in ids], self.frame, TEX_W, TEX_H)

    def box(self, ids):
        c = np.array([self.geo(p) for i in ids for p in mtb.box_corners(self.E[i])])
        return c.min(0), c.max(0)

    def corners_box(self, lo, hi):
        """geo box of a toolbox box."""
        c = np.array([self.geo((x, y, z)) for x in (lo[0], hi[0]) for y in (lo[1], hi[1]) for z in (lo[2], hi[2])])
        return c.min(0), c.max(0)


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
    for x in (x0, x1):                      # caps: triangle fans
        for k in range(n):
            a, b = ring[k], ring[(k + 1) % n]
            faces.append([[x, cy, cz], [x, a[0], a[1]], [x, b[0], b[1]]])
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


def tex_uv(model, ids):
    """The texel (normalized, v up) at the centre of the largest face of the elements: the rubber / rim colour."""
    best, uv = 0.0, None
    for i in ids:
        for pos, tex in mtb.element_polys(model.E[i], TEX_W, TEX_H):
            q = np.array(tex, float)
            area = abs(np.ptp(q[:, 0]) * np.ptp(q[:, 1]))
            if area > best:
                best, uv = area, (q[:, 0].mean() / TEX_W, 1 - q[:, 1].mean() / TEX_H)
    return uv


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


def widen_links(bones, side, width):
    """The Marder's link plates (6.82 px wide) widened to the Puma's iDLT 464 E track about the track centre."""
    k = None
    for n, b in bones.items():
        if n.startswith(f'trackRot{side}') and b.get('poly_mesh'):
            P = np.array(b['poly_mesh']['positions'])
            cx = b['pivot'][0]
            if k is None:
                k = width / float(np.ptp(P[:, 0]))
            for p in b['poly_mesh']['positions']:
                p[0] = round(cx + (p[0] - cx) * k, 5)
    return k


def bounds_of(geo):
    pts = [p for b in geo['bones'] if b.get('poly_mesh') for p in b['poly_mesh']['positions']]
    P = np.array(pts)
    return P.min(0), P.max(0)


TRACK_X = {}


def build_geo(model, tpl):
    """The fitted model in final px: the Marder's bones and 96-link tracks, the Puma's meshes."""
    g = copy.deepcopy(tpl)
    geo = g['minecraft:geometry'][0]
    geo['description']['identifier'] = f'geometry.{VID}'
    geo['description']['texture_width'] = TEX_W
    geo['description']['texture_height'] = TEX_H
    geo['bones'] = [b for b in geo['bones'] if not b['name'].startswith('milan_')]
    bones = {b['name']: b for b in geo['bones']}
    keep_mesh = ('trackRot', 'trackMov', 'brokenTrack', 'crudeTrack', 'TrackL', 'TrackR')
    for b in geo['bones']:
        if not b['name'].startswith(keep_mesh):
            b.pop('poly_mesh', None)
    s, y_off = model.frame.s, model.frame.y_off
    g_ = model.geo

    bones['hull']['poly_mesh'] = model.mesh(HULL)
    bones['turret']['poly_mesh'] = model.mesh(TURRET)
    bones['turret']['pivot'] = [round(float(v), 5) for v in g_(TURRET_PIVOT)]
    bones['barell']['poly_mesh'] = model.mesh(GUN)
    bones['barell']['pivot'] = [round(float(v), 5) for v in g_(GUN_PIVOT)]
    lo, hi = model.box(HULL)
    bones['hull']['pivot'] = [0, round(float(g_((0, WHEEL_Y, 0))[1]), 5), 0]

    rim_uv = tex_uv(model, model.wheel_ids[('L', 0)])
    road_core = [(r * math.cos(math.radians(22.5 + 45 * k)), r * math.sin(math.radians(22.5 + 45 * k)))
                 for k in range(8) for r in (5.61 / math.cos(math.radians(22.5)) * s,)]
    centres = {}
    for side, sign in (('L', 1), ('R', -1)):
        for k in range(6):
            ids = model.wheel_ids[(side, k)]
            assert len(ids) == 16, (side, k, len(ids))
            ring = model.mesh(ids)
            cy = float(g_((0, WHEEL_Y, 0))[1])
            cz = float(g_((ROAD_X[k], 0, 0))[2])
            core = disc(0, cy, cz, road_core, *sorted((sign * CORE[0] * s, sign * CORE[1] * s)), rim_uv)
            b = bones[f'wheel{side}{k}']
            b['poly_mesh'] = merge(ring, core)
            xp = sign * (RING[0] + RING[1]) / 2 * s
            b['pivot'] = [round(xp, 5), round(cy, 5), round(cz, 5)]
            centres[(side, k)] = (xp, cy, cz)
        # sprocket (front) and idler (rear)
        xp = sign * (RING[0] + RING[1]) / 2 * s
        for name, (tx, ty, tr) in (('Front', SPROCKET), ('Rear', IDLER)):
            cy = float(g_((0, ty, 0))[1])
            cz = float(g_((tx, 0, 0))[2])
            r = tr * s
            x0, x1 = sorted((sign * RING[0] * s, sign * RING[1] * s))
            if name == 'Front':
                parts = [disc(0, cy, cz, sprocket_profile(0.86 * r, r), x0 - sign * 0.2 * s, x1 + sign * 0.2 * s, rim_uv),
                         disc(0, cy, cz, polygon(0.62 * r, 16), *sorted((sign * CORE[0] * s, sign * (RING[1] + 0.9) * s)),
                              rim_uv)]
            else:
                parts = [disc(0, cy, cz, polygon(r, 24), x0 - sign * 0.2 * s, x1 + sign * 0.2 * s, rim_uv),
                         disc(0, cy, cz, polygon(0.6 * r, 16), *sorted((sign * CORE[0] * s, sign * (RING[1] + 0.9) * s)),
                              rim_uv)]
            b = bones[f'wheel{side}{name}']
            b['poly_mesh'] = merge(*parts)
            b['pivot'] = [round(xp, 5), round(cy, 5), round(cz, 5)]
            centres[(side, name)] = (xp, cy, cz)

    # the track: the Marder's links widened to 8.17 px and moved onto the Puma's track plane (|x| = the wheel ring centre)
    tx = (RING[0] + RING[1]) / 2 * s
    for side, sign in (('L', 1), ('R', -1)):
        widen_links(bones, side, TRACK_WIDTH)
        ref = bones[f'trackRot{side}0']['pivot'][0]
        dx = sign * tx - ref
        for name, b in bones.items():
            if name.startswith((f'trackRot{side}', f'trackMov{side}', f'brokenTrack{side}', f'crudeTrack{side}',
                                f'Track{side}')):
                shift_bone(b, dx=dx)
        TRACK_X[side] = round(sign * tx, 5)
    # fallback track (crude cubes, broken track): the Marder's boxes mapped onto the Puma's belt envelope afterwards
    lo, hi = bounds_of(geo)
    size = hi - lo
    geo['description']['visible_bounds_width'] = round(float(max(size[0], size[2]) / 16 + 1), 2)
    geo['description']['visible_bounds_height'] = round(float(size[1] / 16 + 1), 2)
    geo['description']['visible_bounds_offset'] = [0, round(float((lo[1] + hi[1]) / 32), 2), 0]
    return g


def fit_fallback_tracks(geo_path, tpl):
    """crudeTrack cubes and brokenTrack mesh of both sides: the Marder's envelope (z, y) mapped to the Puma's belt."""
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
                new['cubes'] = cubes
            if old.get('poly_mesh'):
                pm = copy.deepcopy(old['poly_mesh'])
                pm['positions'] = [[round(float(v), 5) for v in (np.array(p) - c_old) * k + c_new] for p in pm['positions']]
                new['poly_mesh'] = pm
    with open(geo_path, 'w') as fh:
        json.dump(g, fh, separators=(',', ':'))


def px(model, p):
    """Toolbox point -> final model px [x, y, z] (+X left, +Y up, nose -Z)."""
    return [round(float(v), 4) for v in model.geo(p)]


def make_spec(model):
    t = lambda *p: px(model, p)
    hull_lo, hull_hi = model.box(HULL)
    tur_body = [i for i in range(177, 228) if i not in (199, 200)]
    # turret body without the PERI mast (209-227) and the launcher bracket
    body = [i for i in tur_body if i < 209] + [516]
    tb_lo, tb_hi = model.box(body)
    mast = list(range(209, 228))
    m_lo, m_hi = model.box(mast)
    lau = list(range(245, 258)) + [270] + [199, 200]
    l_lo, l_hi = model.box(lau)
    eng_lo, eng_hi = model.corners_box((41.0, -26.0, -34.0), (86.0, 2.0, 4.0))
    gun_lo, gun_hi = model.box(GUN)
    seat = lambda name: t(SEATS[name][0], SEATS[name][1], SEATS[name][2])
    spec_seats = []
    for name in ('gunner', 'commander', 'driver', 'troopR1', 'troopR2', 'troopR3', 'troopR4', 'troopL1', 'troopL2'):
        p = seat(name)
        p[1] = round(p[1] - 12.0, 4)           # entity feet: the hips (cushion contact) sit 12 px over them
        spec_seats.append({'frame': 'Vehicle', 'at': p, 'fromTemplate': 0 if name == 'gunner' else 1})
    return {
        'id': VID,
        'template': TEMPLATE,
        'hitbox': (4.2, 3.1),
        'geometry': {'scale': 1.0},
        'obb': [
            {'box': [list(hull_lo), list(hull_hi)]},
            {'box': [list(eng_lo), list(eng_hi)], 'part': 'MainEngine'},
            {'box': [[float(v) for v in tb_lo], [float(v) for v in tb_hi]], 'transform': 'Turret', 'part': 'Turret'},
            {'box': [[float(v) for v in l_lo], [float(v) for v in l_hi]], 'transform': 'Turret', 'part': 'Turret'},
            {'box': [[float(v) for v in m_lo], [float(v) for v in m_hi]], 'transform': 'Turret', 'part': 'Turret'},
            {'box': [[float(v) for v in gun_lo], [float(v) for v in gun_hi]], 'transform': 'Barrel', 'part': 'Turret'},
        ],
        'attachments': {
            'gunnerCamera': {'parent': 'Turret', 'at': t(*GUNNER_EYE)},
            'commanderCamera': {'parent': 'Turret', 'at': t(*COMMANDER_EYE)},
            'mainMuzzle': {'parent': 'Barrel', 'at': t(*MUZZLE)},
            'coaxMuzzle': {'parent': 'Barrel', 'at': t(*COAX_MUZZLE)},
        },
        'seats': spec_seats,
        'dropWeapons': ['Missile'],
    }


# ------------------------------------------------------------------ War Thunder data
# WT germ_schutzenpanzer_puma (datamine gamedata/units/tankmodels + weapons/groundmodels_weapons):
#   30mm_Rheinmetall_MK_30_2_ABM_user_cannon: shotFreq 3.334 (200 rpm), reloadTime 0.2 (basic 0.3), bulletsCartridge 100,
#     rounds 30mm_mk320 APDS 1480 m/s, 30mm_pmc287 APDS-FS 1405 m/s, 30mm_mk_266 HEI 1080 m/s hexal 0.032 kg,
#     30mm_pmc308 AHEAD 1100 m/s; 400 rounds; wiki penetration at 10 m 87 / 121 / 9 / 44 mm
#   5_56mm_MG4_user_machinegun: 2,000 rounds, belt 1,000, 890 rpm, reload 8 s aced (10.4 basic), 5 mm
#   turret 45 / 45 deg/s (aced), pitch -10..+45, mass 43 t (VehiclePhys TakeOff), 75 / -10 km/h, MTU 10V 890 1,088 hp
#   no ATGM (the model's MELLS is the real S1/VJTF hardware, WT's Puma has none)
ROUNDS = {      # id: (tracer, m/s, TNT kg or None, penetration at 10 m mm, donor round, WT name, ammo item shell type)
    'pmc287_apfsds': ('RED', 1405, None, 121, '3ubr11_apfsds', 'PMC287 APFSDS', 'AP'),
    'mk320_apds': ('RED', 1480, None, 87, '3ubr8_apds', 'MK320 APDS', 'AP'),
    'mk266_hei_t': ('RED', 1080, 0.0544, 9, '3uor6_hef_t', 'MK266 HEI-T', 'HE'),        # 0.032 kg hexal x 1.70
    'pmc308_ahead': ('NONE', 1100, 0.0009, 44, '3uor6_hef_t', 'PMC308 AHEAD', 'HE'),     # 0.5 g CLX-663 (estimate x 1.8)
}
MG_ROUNDS = {      # 5.56 mm MG4: ball and tracer
    'mg4_556_ball': ('NONE', 920, 'western_762_ap'), 'mg4_556_t': ('RED', 880, 'western_762_t'),
}
CANNON_BELTS = [('PMC287 APFSDS', [('pmc287_apfsds', 1)]), ('MK320 APDS', [('mk320_apds', 1)]),
                ('MK266 HEI-T', [('mk266_hei_t', 1)]), ('PMC308 AHEAD', [('pmc308_ahead', 1)])]
MG_BELTS = [('Ball/T Belt', [('mg4_556_ball', 3, 'NONE'), ('mg4_556_t', 1, 'T')])]
WT = {
    'Mass': 43.0,
    'TurretTurnSpeed': [39.13, 39.13],   # [pitch, yaw]; VehicleEntity multiplies by 1.15: WT 45 / 45 deg/s
    'TurretPitchRange': [-10.0, 45.0],
    'MaxForwardSpeedRate': 0.816,        # WT maxFwdSpeed 75 (the M1A1 / TUSK scale: 0.816 = 75 km/h)
    'MaxBackwardSpeedRate': 0.1088,      # WT maxRevSpeed 10
}
SOUND = {'Cannon': 'm242_25', 'MainMachineGun': 'mg4'}
RADIUS = {'m242_25': 24, 'mg4': 12}
LANG = {'entity.berts_vehicle_pack.puma_ifv': 'SPz Puma',
        'weapon.berts_vehicle_pack.puma_ifv_cannon': '30 mm MK 30-2/ABM',
        'weapon.berts_vehicle_pack.puma_ifv_mainmachinegun': '5.56 mm MG4 coax'}


def scale_curve(c, pen):
    k = pen / c['PenetrationMm'][0]
    return {'DistancesMetres': c['DistancesMetres'], 'PenetrationMm': [round(v * k) for v in c['PenetrationMm']]}


def write_profiles(w_name, rounds, base_dir, wt_belts, cat):
    """New round profiles of a weapon from donor rounds, with this vehicle's weapon / round ids and WT penetration."""
    entries = []
    for n, (rid, spec) in enumerate(rounds.items()):
        tracer, ms, tnt, pen, donor, name, shell = spec
        if shell == 'HE':
            pen = max(pen, 21)      # owner rule (DamageNormalizationDataTest): autocannon HE penetrates >= 0.7 x calibre (WT: 9 mm)
        dvid, dwn, dentry = cat[donor]
        e = copy.deepcopy(dentry)
        for k in wt_belts.ENTRY_KEYS_DROP:
            e.get('Override', {}).pop(k, None)
        src = wt_belts.profile_path(e['Override']['Projectile']['Profile'])
        p = json.load(open(src))
        c = p['Combat']
        c['WeaponId'] = f'berts_vehicle_pack:{VID}/{w_name.lower()}'
        c['RoundId'] = f'berts_vehicle_pack:{rid}'
        c['PenetrationMm'] = pen
        c['PenetrationCurve'] = scale_curve(c['PenetrationCurve'], pen)
        if rid == 'pmc287_apfsds':
            c['HullDamageClass'] = 'APFSDS'
        if rid == 'pmc308_ahead':
            c['HullDamage'] = max(1, int(c.get('HullDamage', 4)))
        pid = f'ammo_{n:02d}_{rid}'
        dst = os.path.join(base_dir, pid + '.json')
        with open(dst, 'w') as fh:
            fh.write(json.dumps(p, indent=2) + '\n')
        o = e['Override']
        o['Projectile']['Profile'] = f'berts_vehicle_pack:{VID}/{w_name.lower()}/{pid}'
        o['Name'] = name
        o['ApDurability'] = pen
        o['ShellType'] = shell
        e['Ammo'] = None
        entries.append((rid, e, p))
    return entries


def finish(model, spec):
    """War Thunder and model-specific data on top of what fit_existing wrote."""
    sys.path.insert(0, os.path.join(HERE, '..', 'ballistics'))
    sys.path.insert(0, os.path.join(HERE, '..', 'damage'))
    import wt_belts
    import tracer_nations
    import balance
    import driver_cameras
    paths = fit_existing.files_of(VID)
    d = load(paths['data'])
    d['VehicleIcon'] = f'berts_vehicle_pack:textures/vehicle_icon/{VID}.png'
    d['Mass'] = WT['Mass']
    d['TurretTurnSpeed'] = WT['TurretTurnSpeed']
    d['TurretPitchRange'] = WT['TurretPitchRange']
    d['EngineInfo']['MaxForwardSpeedRate'] = WT['MaxForwardSpeedRate']
    d['EngineInfo']['MaxBackwardSpeedRate'] = WT['MaxBackwardSpeedRate']
    t, cls = balance.GROUND[VID]
    d['MaxHealth'] = balance.ground_hp(VID)
    d['DeathChargeKg'] = balance.death_charge_kg(VID)
    d['DamageClass'] = cls
    d['DamageModifiers'] = balance.modifiers(cls)

    # seats: 0 gunner (the pack's controller seat: drives and fires from the gunner's sight), 1 commander (PERI), 2 driver
    # (the same sight point, fixed to the hull: the owner's rule), 3-8 the six dismounts. All in the hull: the turret is
    # unmanned. Dismount through the rear ramp.
    hull_lo, hull_hi = model.box(HULL)
    rear = -(float(hull_hi[2]) / 16 + 1.3)
    dismount = {'Transform': 'Vehicle', 'Position': [0, 0.45, round(rear, 5)]}
    sight = spec['attachments']['gunnerCamera']['at']
    eye = mtbgeo.geo_to_data(np.array(sight))
    cmd_eye = mtbgeo.geo_to_data(np.array(spec['attachments']['commanderCamera']['at']))
    for i, seat in enumerate(d['Seats']):
        seat['Transform'] = 'Vehicle'
        seat['BodyAttachment'] = 'Vehicle'
        seat['HidePassenger'] = True
        seat['IsEnclosed'] = True
        seat['DismountInfo'] = dict(dismount)
        if i == 0:
            seat['Weapons'] = ['Cannon', 'MainMachineGun']
            seat['MinPitch'], seat['MaxPitch'] = -10, 45
            seat['Sensitivity'] = [0.45, 0.52, 0.8]
            seat['HasThermalImaging'] = True
            seat['CanRotateHead'] = False
        elif i == 1:                        # commander: the PERI-R17 head on the mast, aims where the gun does
            seat.pop('Weapons', None)
            seat['CameraPos'] = {'Transform': 'Turret', 'UseFixedCameraPos': True, 'Position': [0, 0, 0],
                                 'ZoomPosition': [0, 0, 0], 'Direction': 'Turret', 'EyeAttachment': 'commanderCamera',
                                 'ZoomEyeAttachment': 'commanderCamera', 'DirectionAttachment': 'mainMuzzle',
                                 'ZoomDirectionAttachment': 'mainMuzzle', 'CameraMode': 'PLAYER_LOOK_AIM',
                                 'AimCameraMode': 'PLAYER_LOOK_AIM'}
            seat['MinPitch'], seat['MaxPitch'] = -10, 45
            seat['Sensitivity'] = [0.57, 0.75, 0.9]
            seat['HasThermalImaging'] = True
            seat['CanRotateHead'] = False
        elif i == 2:                        # driver: looks out of the gunner's sight point, fixed to the hull
            seat.pop('Weapons', None)
            seat['CameraPos'] = driver_cameras.camera()
            seat['CanRotateHead'] = True
        else:
            seat['Weapons'] = []
            seat['CanRotateHead'] = True
    d['Attachments']['driver_camera'] = {'Parent': 'VehicleCustomPitch', 'Position': [round(float(v), 5) for v in eye],
                                         'Direction': [0, 0, 1]}
    d.pop('WeaponPairs', None)

    # weapons ---------------------------------------------------------------------------------------------------------
    cat = wt_belts.catalogue()
    base = os.path.join(vehgen.DATA, 'sbw', 'projectile_profiles', VID)
    for sub in ('cannon', 'mainmachinegun'):                   # the template's copied rounds are replaced
        folder = os.path.join(base, sub)
        if os.path.isdir(folder):
            for name in os.listdir(folder):
                os.remove(os.path.join(folder, name))
    os.makedirs(os.path.join(base, 'cannon'), exist_ok=True)
    os.makedirs(os.path.join(base, 'mainmachinegun'), exist_ok=True)
    wt_belts.ROUNDS.update({k: (v[0], v[1], v[2]) for k, v in ROUNDS.items()})
    wt_belts.HE_AT_APDS.update({'mk266_hei_t': 'mk320_apds', 'pmc308_ahead': 'mk320_apds'})
    notes = []
    w = d['Weapons']['Cannon']
    entries = write_profiles('Cannon', ROUNDS, os.path.join(base, 'cannon'), wt_belts, cat)
    w['AmmoType'] = [e for _, e, _ in entries]
    w['ProjectileBeltAmmoType'] = []
    wt_belts.rebuild(VID, 'Cannon', w, 'GENERIC', CANNON_BELTS, {}, {}, notes)
    first = w['AmmoType'][0]['Override']['Projectile']['Profile'].split(':', 1)[1]
    shutil_copy(os.path.join(vehgen.DATA, 'sbw', 'projectile_profiles', first + '.json'), os.path.join(base, 'cannon', 'default.json'))
    w['Projectile']['Profile'] = f'berts_vehicle_pack:{VID}/cannon/default'
    w['NominalBallistics']['ProjectileProfile'] = f'berts_vehicle_pack:{VID}/cannon/default'
    w.pop('DepletionReload', None)
    w.update({'RPM': 200, 'Magazine': 100, 'NormalReloadTime': 4, 'EmptyReloadTime': 4,
              'Name': 'weapon.berts_vehicle_pack.puma_ifv_cannon',
              'Icon': 'superbwarfare:textures/overlay/vehicle/weapon/icons/cannon_30mm_ap.png'})
    w['ApDurability'] = w['AmmoType'][0]['Override']['ApDurability']
    si = w['SoundInfo']
    si.pop('VehicleReload', None)
    si.pop('VehicleReloadClipDurationTicks', None)

    mg = d['Weapons']['MainMachineGun']
    mg_entries = []
    for n, (rid, (tracer, ms, donor)) in enumerate(MG_ROUNDS.items()):
        dvid, dwn, dentry = cat[donor]
        e = copy.deepcopy(dentry)
        for k in wt_belts.ENTRY_KEYS_DROP:
            e.get('Override', {}).pop(k, None)
        p = json.load(open(wt_belts.profile_path(e['Override']['Projectile']['Profile'])))
        c = p['Combat']
        c['WeaponId'] = f'berts_vehicle_pack:{VID}/mainmachinegun'
        c['RoundId'] = f'berts_vehicle_pack:{rid}'
        c['CaliberMm'] = c['DiameterMm'] = 5.56
        c['PenetrationMm'] = 5
        c['PenetrationCurve'] = {'DistancesMetres': [10, 100, 500, 1000, 1500, 2000], 'PenetrationMm': [5, 4, 2, 1, 0, 0]}
        pid = f'{"ammo" if n == 0 else "belt_ammo"}_{n:02d}_{rid}'
        with open(os.path.join(base, 'mainmachinegun', pid + '.json'), 'w') as fh:
            fh.write(json.dumps(p, indent=2) + '\n')
        e['Override']['Projectile']['Profile'] = f'berts_vehicle_pack:{VID}/mainmachinegun/{pid}'
        e['Override']['Name'] = 'Ball' if n == 0 else 'T'
        e['Override']['ApDurability'] = 5
        e['Ammo'] = None
        mg_entries.append(e)
        wt_belts.ROUNDS[rid] = (tracer, ms, None)
    mg['AmmoType'] = mg_entries
    mg['ProjectileBeltAmmoType'] = []
    wt_belts.rebuild(VID, 'MainMachineGun', mg, 'GENERIC', MG_BELTS, {}, {}, notes)
    first = mg['AmmoType'][0]['Override']['Projectile']['Profile'].split(':', 1)[1]
    shutil_copy(os.path.join(vehgen.DATA, 'sbw', 'projectile_profiles', first + '.json'),
                os.path.join(base, 'mainmachinegun', 'default.json'))
    mg['Projectile']['Profile'] = f'berts_vehicle_pack:{VID}/mainmachinegun/default'
    mg['NominalBallistics']['ProjectileProfile'] = f'berts_vehicle_pack:{VID}/mainmachinegun/default'
    mg.update({'RPM': 890, 'Magazine': 1000, 'NormalReloadTime': 160, 'EmptyReloadTime': 160, 'Damage': 6,
               'Name': 'weapon.berts_vehicle_pack.puma_ifv_mainmachinegun', 'ApDurability': 5})
    mg['SoundInfo']['VehicleReload'] = 'superbwarfare:m_60_reload_empty'
    mg['SoundInfo']['VehicleReloadClipDurationTicks'] = 127
    # weapon sounds (what tools/audio/apply_weapon_sounds.py writes for a mapped family)
    for wn, fam in SOUND.items():
        info = d['Weapons'][wn]['SoundInfo']
        info['Fire1P'] = f'bvp_audio:weapon/{fam}_1p'
        info['Fire3P'] = f'bvp_audio:weapon/{fam}_3p'
        info['Fire3PFar'] = f'bvp_audio:weapon/{fam}_far'
        info['Fire3PVeryFar'] = f'bvp_audio:weapon/{fam}_veryfar' if fam == 'm242_25' else f'bvp_audio:weapon/{fam}_far'
        for g in ('Fire1PGain', 'Fire3PGain', 'Fire3PFarGain', 'Fire3PVeryFarGain'):
            info[g] = 1.0
        d['Weapons'][wn]['SoundRadius'] = RADIUS[fam]
    tracer_nations.fix(d, False, [])
    if notes:
        print('belts:', notes)

    # engine sound (Lizardpyle puma_power, the MTU 10V 890): vehicle_audio/puma_ifv.json is written by lizard_import,
    # the vehicle needs no sound keys of its own (as the Marders)
    with open(paths['data'], 'w') as fh:
        fh.write(json.dumps(d, indent=2) + '\n')

    # exhaust: the engine bay is front right; the exhaust leaves on the rear right deck
    c = load(paths['client'])
    c.pop('FittedGroundRig', None)
    # EngineExhaust is in the MODEL frame: final px / 16, +Z = rear (the renderer negates Z); not the attachment frame
    c['EngineExhaust']['Origins'] = [{'Id': 'puma_exhaust_000', 'Position': [round(v / 16, 5) for v in
                                                                         px(model, EXHAUST)]}]
    with open(paths['client'], 'w') as fh:
        fh.write(json.dumps(c, indent=2) + '\n')
    return [round(float(v), 5) for v in eye]


def shutil_copy(src, dst):
    import shutil
    shutil.copyfile(src, dst)


def write_textures(model, geo):
    """Entity texture (the model's own PNG plus the Marder's track-link columns), wreck texture (x0.178)."""
    assets = vehgen.ASSETS
    own = Image.open(__import__('io').BytesIO(model.src.png)).convert('RGBA')
    tpl = Image.open(os.path.join(assets, 'textures', 'entity', f'{TEMPLATE}.png')).convert('RGBA')
    img = Image.new('RGBA', (TEX_W, TEX_H), (0, 0, 0, 0))
    img.paste(own, (0, 0))
    img.paste(tpl.crop((own.width, 0, TEX_W, TEX_H)), (own.width, 0))      # the track-link texture
    img.save(os.path.join(assets, 'textures', 'entity', f'{VID}.png'))
    a = np.asarray(img).astype(float)
    dead = a.copy()
    dead[..., :3] = np.clip(np.round(a[..., :3] * vehgen.DEAD), 0, 255)
    Image.fromarray(dead.astype(np.uint8)).save(os.path.join(assets, 'textures', 'entity_dead', f'{VID}.png'))


def main(argv):
    write = '--write' in argv
    sources = os.path.join(HERE, '..', '..', '..', 'source', 'mtb', 'elite_expansion')
    if '--sources' in argv:
        sources = argv[argv.index('--sources') + 1]
    sources = os.path.abspath(sources)
    model = Model(sources)
    tpl = load(fit_existing.files_of(TEMPLATE)['geo'])
    geo = build_geo(model, tpl)
    if '--dump' in argv:                       # the fitted geometry only, for a look in Blender
        with open(argv[argv.index('--dump') + 1], 'w') as fh:
            json.dump(geo, fh, separators=(',', ':'))
        return 0
    spec = make_spec(model)
    fit_specs.SPECS[VID] = spec
    files = fit_existing.files_of(TEMPLATE)
    client = json.loads(json.dumps(load(files['client'])).replace(TEMPLATE, VID))
    client.pop('FittedGroundRig', None)
    for side in client['RunningGear']['TrackRender']['Sides']:
        side['XCenter'] = TRACK_X[side['Side']]
        side['RoadCenters'] = [round(float(model.geo((x, 0, 0))[2]), 5) for x in ROAD_X]
    orig = {'geo': geo, 'wreck': load(files['wreck']), 'client': client, 'data': {}, 'armor': {}}
    fit_existing.originals = lambda vid, w: orig
    rc = fit_existing.main([VID] + (['--write'] if write else []))
    if write and rc == 0:
        sys.path.insert(0, os.path.join(HERE, '..', 'track_path'))
        import belt                                   # the link belt round this model's wheels (tools/track_path)
        _, msg = belt.rebuild(VID, write=True)
        print('belt:', msg)
        fit_fallback_tracks(files_of_geo(), tpl)
        eye = finish(model, spec)
        write_textures(model, geo)
        print('driver_camera (data frame):', eye)
    return rc


def files_of_geo():
    return fit_existing.files_of(VID)['geo']


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
