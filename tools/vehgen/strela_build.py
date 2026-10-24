#!/usr/bin/env python3
"""9K35 Strela-10M2 (strela_10m2): the supplied Strela-10M2.mtb fitted as a tracked MT-LB SAM carrier on the pack's
9P149 Shturm rig (the same MT-LB chassis).

    python3 tools/vehgen/strela_build.py [--write] [--sources DIR] [--dump OUT.json]
    (then strela_icon.py, tools/icon_flags/flags.py --write strela_10m2, exhaust_check.py strela_10m2)

The source is 419 elements in six parts (toolbox frame +x forward, +y down, +z left):

    0   (245)  hull, roof hatches (all closed and flush), engine louvres (224-225: left roof deck), nose trim vane, lights
    3   (44)   the launcher pedestal: turntable plate (265-268), the box and sloped arm (269-306, 413-414)
    4   (106)  the launcher head: four 2.0 m transport-launch containers (314-315 right, 340-341 left, front caps 330-339 /
               356-365), the sight and IR head between them (398-412), the frame, the rear bracing
    9 / 10     left / right running gear: six road wheels (245-250 / 255-260), the sprocket (251-253 / 261-263: two discs and
               the hub) at the front, the idler (254 / 264) at the rear. The wheels are Shape elements; tools/mtb_wheels/mtb.py
               extrudes them to +z, the toolbox (and tools/mtb_export) to -z, so the Shape rule is overridden here.
    13  (4)    unit pieces at the origin (dropped)

It is built as a new vehicle in two steps, like the SPz Puma and the T-64BV:
  1. geometry (this file): the 9P149's bone hierarchy and its 72-link belt; the model lands on the 9P149's wheel centres
     to the pixel (12 toolbox units between wheel centres = 15.289 px), so the wheels, sprocket and idler are the model's
     own; the sprocket gets teeth; tools/track_path/belt.py then refits the belt round all eight wheels per side;
  2. systems (tools/vehgen/fit_existing.py, spec injected below): 9P149 data, armour layout and profiles, then the
     War Thunder ussr_9a35_m2 on top (9M37M IR missiles through the pack's ground SAM mechanism, 100 / 50 deg/s slew,
     -5 / +80, 12.3 t, 75 / -10 km/h, amphibious).
"""
import copy
import json
import math
import os
import re
import sys

import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import mtbgeo  # noqa: E402
import vehgen  # noqa: E402
import fit_existing  # noqa: E402
import fit_specs  # noqa: E402
import puma_build as PB  # noqa: E402  (solid / disc / sprocket helpers, fallback tracks)

mtb = mtbgeo.mtb
VID = 'strela_10m2'
TEMPLATE = '9p149_shturm'
SOURCE = 'Strela-10M2.mtb'
SCALE = 15.28876 / 12.0     # px per toolbox unit: the 9P149's road wheel spacing (15.28876 px) over the model's 12 units
WHEEL_BOTTOM_Y = 1.7497     # px: road wheel bottoms over the ground plane (the 9P149's 1.75; the link plate sits under them)
TEX_W, TEX_H = 1024, 640    # the model's 1024 x 512 plus the 128 rows the 9P149's track-link bones sample (y 512-639)
GROUND_Y = 9.0              # toolbox y of the road wheel bottoms
PB.TEX_W, PB.TEX_H = TEX_W, TEX_H

HULL = list(range(0, 245))
TURRET = [i for i in range(265, 307)] + [413, 414]
GUN = list(range(307, 413))
ROAD = {'L': {5: 245, 4: 246, 3: 247, 2: 248, 1: 249, 0: 250}, 'R': {5: 255, 4: 256, 3: 257, 2: 258, 1: 259, 0: 260}}
SPROCKET = {'L': [251, 252, 253], 'R': [261, 262, 263]}
IDLER = {'L': [254], 'R': [264]}
ROAD_X = {5: 30.0, 4: 18.0, 3: 6.0, 2: -6.0, 1: -18.0, 0: -30.0}     # toolbox x of the road wheel centres
WHEEL_Y = 4.0                # toolbox y of the road wheel and sprocket axes
SPR_X, IDL_X = 42.0, -40.5   # toolbox x of the sprocket (front) and idler (rear) axes
IDL_Y = 1.5
TRACK_Z = 19.5               # |z| of the track plane (the road wheel rim centre)
TEETH_Z = (18.3, 20.7)       # |z| of the added toothed ring (between the sprocket discs, in the track plane)
TURRET_PIVOT = (-16.0, -16.5, 0.0)     # the turntable plate's centre (plate 265-268: x -26..-6, z +-10)
GUN_PIVOT = (-17.0, -36.5, 0.25)       # the elevation trunnion (the head's rotation point)
# the operator's sight: the IR / optical head between the containers (front plates x -1.8, y -38.2..-31.8): the eye on its
# axis, one unit ahead of the plates, between the container fronts (x 1.3 .. 2.2, |z| >= 5.2), so the view ahead is open
SIGHT_EYE = (-0.8, -35.0, 0.25)
# muzzles: the front face of each container (right: z -11.2 and -7.2, left: 7.8 and 11.8; y mid of the 4 unit tall box),
# alternating sides in firing order
MUZZLES = [(2.6, -33.5, -7.2), (2.6, -33.5, 7.8), (2.6, -33.5, -11.2), (2.6, -33.5, 11.8)]
# the model's engine louvres: the pair of 24 x 1 x 9 slabs on the left of the roof deck (elements 224-225: x -3.8 .. 17.8,
# z 12 .. 18.5, top face y -16.1). The only grille the model has, so it is the outlet.
LOUVRE_BOX = (-3.7, -16.1, 12.1, 17.7, -16.1, 18.4)      # x0, y, z0, x1, ..., z1 (toolbox, top face)
SEATS = {            # pelvis points, toolbox frame (the entity's feet are 12 px under them)
    'operator': (-16.0, -4.0, 0.0),      # in the turret frame (a hull-frame seat with a fixed head cannot slew the launcher)
    'driver': (38.0, -4.0, 10.0),        # front left
    'commander': (38.0, -4.0, -10.0),    # front right: the pintle PKMB station is his (seat 2, below)
}
# WT's pintle PKMB (bone_mg_aa) is not in the supplied model (the real 9K35 has none): a hand-built PKM on a roof pintle,
# right side of the deck. Toolbox frame; the gun axis is +x at rest.
MG_YAW = (26.0, -20.5, -9.5)             # yaw axis of the pintle (a point on it, at the cradle)
MG_PITCH = (26.0, -21.4, -9.5)           # trunnion
MG_MUZZLE = (43.4, -21.4, -9.5)
MG_EYE = (11.0, -24.0, -9.5)             # behind the shoulder plate, 5 cm over the rear sight
MG_GRIPS = ((17.6, -21.0, -8.2), (17.6, -21.0, -10.8))     # left, right
MG_SEAT = (10.5, -13.5, -9.5)            # pelvis of the gunner: behind the butt, the gun at his chest (feet 1.15 blocks up)
MG_BASE = [((23.9, -17.5, -11.6), (28.1, -16.4, -7.4)),         # roof plate
           ((25.4, -20.6, -10.1), (26.6, -17.5, -8.9))]         # post
MG_CRADLE = [((24.7, -21.8, -10.8), (27.3, -20.4, -10.1)), ((24.7, -21.8, -8.9), (27.3, -20.4, -8.2)),     # fork cheeks
             ((24.7, -20.6, -10.8), (27.3, -20.1, -8.2))]                                                   # fork floor
MG_GUN = [((21.0, -22.4, -10.2), (31.0, -20.4, -8.8)),           # receiver
          ((23.0, -23.2, -9.9), (27.0, -22.4, -9.1)),            # feed cover
          ((31.0, -22.0, -9.9), (36.0, -21.0, -9.1)),            # barrel jacket and gas tube
          ((36.0, -21.7, -9.7), (41.6, -21.1, -9.3)),            # barrel
          ((41.6, -21.9, -9.9), (43.4, -20.9, -9.1)),            # flash hider
          ((15.0, -22.0, -9.9), (21.0, -20.8, -9.1)),            # butt
          ((13.8, -22.4, -10.1), (15.0, -20.4, -8.9)),           # shoulder plate
          ((22.4, -20.8, -8.8), (27.6, -18.6, -6.9)),            # belt box on the left
          ((28.0, -23.2, -9.8), (29.0, -22.4, -9.2)),            # front sight
          ((18.4, -22.8, -9.8), (19.6, -22.4, -9.2))]            # rear sight
MG_TURN = 90.0               # WT PKMB speedYaw / speedPitch deg/s
MG_YAW_RANGE = [-60.0, 60.0]
MG_PITCH_RANGE = [-5.0, 20.0]


def shape_polys(f, tw, th):
    """ModelRendererTurbo.addShape3D as tools/mtb_export/export_smp_mtb_obj.mjs (shapeFaces) builds it: the outline lies in the
    element's x/y plane mirrored in x about the offset; its front plane is at the NEGATED z offset and it is extruded by the
    depth toward -z (checked on the Strela-10M2, Avro Vulcan and M113A3 wheel pairs, which only come out symmetric so).
    Textures as mtb.py: the outline at the texture offset (front), mirrored beside it (back), the sides as one strip."""
    ox, oy, oz = mtb.num(f[15]), mtb.num(f[16]), mtb.num(f[17])
    depth = mtb.num(f[11])
    sw, sh = mtb.uv_dim(mtb.num(f[9])), mtb.uv_dim(mtb.num(f[10]))
    tx, ty = mtb.num(f[18]), mtb.num(f[19])
    pts = mtb.shape_outline(f)
    n = len(pts)
    front = [mtb.place([ox - x, oy + y, -oz], f) for x, y in pts]
    back = [mtb.place([ox - x, oy + y, -oz - depth], f) for x, y in pts]
    uf = [(tx + x, ty + y) for x, y in pts]
    ub = [(tx + sw * 2 - x, ty + y) for x, y in pts]
    polys = [(front[::-1], uf[::-1]), (back, ub)]
    lengths = [math.dist(pts[i], pts[(i + 1) % n]) for i in range(n)]
    total = sum(lengths) or 1.0
    pos = 0.0
    v0, v1 = ty + sh, ty + sh + mtb.uv_dim(depth)
    for i in range(n):
        j = (i + 1) % n
        u0 = tx + pos / total * total
        pos += lengths[i]
        u1 = tx + pos / total * total
        polys.append(([front[i], front[j], back[j], back[i]], [(u0, v0), (u1, v0), (u1, v1), (u0, v1)]))
    return polys


mtb.shape_polys = shape_polys


def load(path):
    return vehgen.load(path)


class Model:
    def __init__(self, sources):
        self.src = mtbgeo.Source(os.path.join(sources, SOURCE))
        self.E = self.src.elements
        self.frame = mtbgeo.Frame(SCALE, WHEEL_BOTTOM_Y + GROUND_Y * SCALE)

    def geo(self, p):
        return self.frame.geo(p)

    def els(self, ids):
        return [self.E[i] for i in ids]

    def mesh(self, ids):
        return mtbgeo.mesh(self.els(ids), self.frame, TEX_W, TEX_H)

    def box(self, ids):
        """geo box of the elements' real polygons (Shape elements included)."""
        c = np.array([self.geo(p) for i in ids for pos, _ in mtb.element_polys(self.E[i], TEX_W, TEX_H) for p in pos])
        return c.min(0), c.max(0)

    def corners_box(self, lo, hi):
        c = np.array([self.geo((x, y, z)) for x in (lo[0], hi[0]) for y in (lo[1], hi[1]) for z in (lo[2], hi[2])])
        return c.min(0), c.max(0)


def px(model, p):
    """Toolbox point -> final model px [x, y, z] (+X left, +Y up, nose -Z)."""
    return [round(float(v), 4) for v in model.geo(p)]


def box_solid(model, lo, hi, uv):
    """A toolbox box as a poly_mesh solid (every vertex on texel `uv`), outward normals."""
    c = {(i, j, k): model.geo((hi[0] if i else lo[0], hi[1] if j else lo[1], hi[2] if k else lo[2]))
         for i in (0, 1) for j in (0, 1) for k in (0, 1)}
    quads = [[(0, 0, 0), (1, 0, 0), (1, 1, 0), (0, 1, 0)], [(0, 0, 1), (1, 0, 1), (1, 1, 1), (0, 1, 1)],
             [(0, 0, 0), (0, 0, 1), (0, 1, 1), (0, 1, 0)], [(1, 0, 0), (1, 0, 1), (1, 1, 1), (1, 1, 0)],
             [(0, 0, 0), (1, 0, 0), (1, 0, 1), (0, 0, 1)], [(0, 1, 0), (1, 1, 0), (1, 1, 1), (0, 1, 1)]]
    centre = np.mean(list(c.values()), axis=0)
    return PB.solid([[c[v] for v in q] for q in quads], uv, centre)


TRACK_X = {}


def build_geo(model, tpl):
    """The fitted model in final px: the 9P149's bones and 72-link tracks, the Strela's meshes."""
    g = copy.deepcopy(tpl)
    geo = g['minecraft:geometry'][0]
    geo['description']['identifier'] = f'geometry.{VID}'
    geo['description']['texture_width'] = TEX_W
    geo['description']['texture_height'] = TEX_H
    drop = ('launcher_tube_spent', 'launcher_plate', 'reload_hatch', 'reload_hatch_well')
    geo['bones'] = [b for b in geo['bones'] if b['name'] not in drop]
    bones = {b['name']: b for b in geo['bones']}
    keep_mesh = ('trackRot', 'trackMov', 'brokenTrack', 'crudeTrack', 'TrackL', 'TrackR')
    for b in geo['bones']:
        if not b['name'].startswith(keep_mesh):
            b.pop('poly_mesh', None)
    s = SCALE
    g_ = model.geo

    bones['hull']['poly_mesh'] = model.mesh(HULL)
    bones['turret']['poly_mesh'] = model.mesh(TURRET)
    bones['turret']['pivot'] = [round(float(v), 5) for v in g_(TURRET_PIVOT)]
    bones['barell']['poly_mesh'] = model.mesh(GUN)
    bones['barell']['pivot'] = [round(float(v), 5) for v in g_(GUN_PIVOT)]

    uv_rim = PB.tex_uv(model, ROAD['L'].values())
    uv_spr = PB.tex_uv(model, SPROCKET['L'])
    for side, sign in (('L', 1), ('R', -1)):
        xp = sign * TRACK_Z * s
        for k in range(6):
            b = bones[f'wheel{side}{k}']
            b['poly_mesh'] = model.mesh([ROAD[side][k]])
            b['pivot'] = [round(xp, 5), round(float(g_((ROAD_X[k], WHEEL_Y, 0))[1]), 5),
                          round(float(g_((ROAD_X[k], 0, 0))[2]), 5)]
        # sprocket (front): the model's two discs and hub, plus a toothed ring in the track plane (the model has no teeth)
        cy, cz = float(g_((0, WHEEL_Y, 0))[1]), float(g_((SPR_X, 0, 0))[2])
        r = 5.0 * s
        x0, x1 = sorted((sign * TEETH_Z[0] * s, sign * TEETH_Z[1] * s))
        teeth = PB.disc(0, cy, cz, PB.sprocket_profile(0.84 * r, 1.04 * r, 13), x0, x1, uv_spr or uv_rim)
        b = bones[f'wheel{side}Front']
        b['poly_mesh'] = PB.merge(model.mesh(SPROCKET[side]), teeth)
        b['pivot'] = [round(xp, 5), round(cy, 5), round(cz, 5)]
        # idler (rear)
        b = bones[f'wheel{side}Rear']
        b['poly_mesh'] = model.mesh(IDLER[side])
        b['pivot'] = [round(xp, 5), round(float(g_((0, IDL_Y, 0))[1]), 5), round(float(g_((IDL_X, 0, 0))[2]), 5)]
        bones[f'Wheel{side}']['pivot'][0] = round(xp, 5)

    # the pintle PKMB: station (the roof plate and post), yaw (the cradle) and pitch (the gun) bones, all parented to the hull
    # (a hull-parented passenger weapon station, as the Toyota technicals)
    uv_gun = PB.tex_uv(model, ROAD['L'].values())
    new = [('passengerWeaponStation', 'hull', MG_YAW, MG_BASE), ('passengerWeaponStationYaw', 'passengerWeaponStation', MG_YAW,
                                                                   MG_CRADLE),
           ('passengerWeaponStationPitch', 'passengerWeaponStationYaw', MG_PITCH, MG_GUN)]
    for name, parent, pivot, boxes in new:
        geo['bones'].append({'name': name, 'parent': parent, 'pivot': [round(float(v), 5) for v in g_(pivot)],
                             'poly_mesh': PB.merge(*[box_solid(model, lo, hi, uv_gun) for lo, hi in boxes])})

    # the track plane: the 9P149's links (and fallback boxes) sit on its wheel plane |x| 26.589; move them to this model's
    tx = TRACK_Z * s
    for side, sign in (('L', 1), ('R', -1)):
        ref = bones[f'trackRot{side}0']['pivot'][0]
        dx = sign * tx - ref
        for name, b in bones.items():
            if name.startswith((f'trackRot{side}', f'trackMov{side}', f'brokenTrack{side}', f'crudeTrack{side}',
                                f'Track{side}')):
                PB.shift_bone(b, dx=dx)
        TRACK_X[side] = round(sign * tx, 5)
    lo, hi = PB.bounds_of(geo)
    size = hi - lo
    geo['description']['visible_bounds_width'] = round(float(max(size[0], size[2]) / 16 + 1), 2)
    geo['description']['visible_bounds_height'] = round(float(size[1] / 16 + 1), 2)
    geo['description']['visible_bounds_offset'] = [0, round(float((lo[1] + hi[1]) / 32), 2), 0]
    return g


def make_spec(model):
    t = lambda *p: px(model, p)
    hull_lo, hull_hi = model.box(HULL)
    eng_lo, eng_hi = model.corners_box((0.0, -16.0, -14.0), (36.0, 4.0, 14.0))
    tur_lo, tur_hi = model.box(TURRET)
    gun_lo, gun_hi = model.box(GUN)
    spec_seats = []
    for name, frame, tpl in (('operator', 'Turret', 0), ('driver', 'Vehicle', 1), ('commander', 'WeaponStation', 1)):
        p = t(*(MG_SEAT if name == 'commander' else SEATS[name]))
        p[1] = round(p[1] - 12.0, 4)           # entity feet: the hips (cushion contact) sit 12 px over them
        spec_seats.append({'frame': frame, 'at': p, 'fromTemplate': tpl})
    attachments = {
        'gunnerCamera': {'parent': 'Barrel', 'at': t(*SIGHT_EYE)},
        'turretPivot': {'parent': 'Vehicle', 'at': 'pivot:turret'},
        'barrelPivot': {'parent': 'Turret', 'at': 'pivot:barell'},
        'passenger_station_camera': {'parent': 'WeaponStationBarrel', 'at': t(*MG_EYE)},
        'passenger_station_muzzle': {'parent': 'WeaponStationBarrel', 'at': t(*MG_MUZZLE)},
        'passenger_station_left_grip': {'parent': 'WeaponStationBarrel', 'at': t(*MG_GRIPS[0])},
        'passenger_station_right_grip': {'parent': 'WeaponStationBarrel', 'at': t(*MG_GRIPS[1])},
    }
    names = []
    for i, m in enumerate(MUZZLES, 1):
        attachments[f'missileMuzzle_{i}'] = {'parent': 'Barrel', 'at': t(*m)}
        names.append(f'missileMuzzle_{i}')
    f = lambda a: [float(v) for v in a]
    return {
        'id': VID,
        'template': TEMPLATE,
        'hitbox': (2.9, 2.6),
        'geometry': {'scale': 1.0},
        'obb': [
            {'box': [f(hull_lo), f(hull_hi)]},
            {'box': [f(eng_lo), f(eng_hi)], 'part': 'MainEngine'},
            {'box': [f(tur_lo), f(tur_hi)], 'transform': 'Turret', 'part': 'Turret'},
            {'box': [f(gun_lo), f(gun_hi)], 'transform': 'Barrel', 'part': 'Turret'},
        ],
        'attachments': attachments,
        'seats': spec_seats,
        'muzzles': {'Missile': names},
        'lang': {'weapon.berts_vehicle_pack.strela_10m2_missile': '9M37M',
                 'weapon.berts_vehicle_pack.strela_10m2_pkmb': '7.62 mm PKMB'},
        'keepData': [],
    }


# ---------------------------------------------------------------------------------------------- War Thunder data
# WT ussr_9a35_m2 (datamine gszabi99, 2026-10-23: gamedata/units/tankmodels/ussr_9a35_m2.blkx and
# weapons/groundmodels_weapons/120mm_9m37m_rocket_launcher.blkx): mass 12,300 kg (TakeOff 12,000 + 300 fuel), maxFwd 75 / maxRev
# 10 km/h, 238V 240 hp, floats (waterJet 2 x 8,000 N). Launcher turret speedYaw 100 / speedPitch 50 deg/s, limits yaw +-180,
# pitch -5 / +80. 9M37M: bulletsCartridge 4 (the four ready rounds), reloadTime 15 s, shotFreq 1.0, bullets 8 on the vehicle;
# rocket mass 39.2 kg, endSpeed 700 m/s, rangeMax 5,000 m, timeLife 16 s, explosive 1.1 kg A-IX-2 (x1.54 = 1.694 kg TNT),
# warhead 3.0 kg, proximity fuse 4 m (detects air units), IR seeker: warm-up 1.2 s, lockAngleMax 40 deg, fov 1.0, range
# bands 11,000 m, break lock 3 s, uncage before launch. The sensor is the 9S86 target tagging set (no radar lock needed).
# A pintle PKMB 7.62 mm (1,000 rounds, yaw +-60, pitch -5 / +20) is WT's only secondary weapon: see strela_mg.py.
NATIVE_RATE = 1.15           # VehicleEntity.turretTurnXSpeed / YSpeed multiply the data by 1.15
WT = {
    'Mass': 12.3,
    'TurretTurnSpeed': [50.0 / NATIVE_RATE, 100.0 / NATIVE_RATE],     # [pitch, yaw]
    'TurretPitchRange': [-5.0, 80.0],
    'MaxForwardSpeedRate': 0.816,        # WT maxFwdSpeed 75 (the Puma / M1A1 scale: 0.816 = 75 km/h)
    'MaxBackwardSpeedRate': 0.1088,      # WT maxRevSpeed 10
    'Buoyancy': 0.052,                   # the pack's amphibious value (BMP-2, BTR-80A, PT-76B)
}
MISSILE = {
    'Name': 'weapon.berts_vehicle_pack.strela_10m2_missile',
    'RPM': 60,                           # shotFreq 1.0
    'Magazine': 4,                       # bulletsCartridge 4
    'NormalReloadTime': 300, 'EmptyReloadTime': 300,     # reloadTime 15 s, in ticks
    'TntEquivalentKg': 1.694,            # 1.1 kg A-IX-2 x 1.54
}
SAM = {     # ground_sams/strela_10m2.json: the pack's IR-guided ground-launched missile mechanism (FFA interceptor)
    'Schema': 1,
    '_comment': 'Strela-10M2 (9A35M2) with the 9M37M: WT ussr_9a35_m2 data. Passive IR (the seeker must hold the target before '
                'launch, uncage before launch): LockTicks 24 = the 1.2 s seeker warm-up, ConeDegrees 40 = lockAngleMax, Range '
                '500 = WT rangeMax 5,000 m at the pack scale of the other IR SAMs (FIM-92, 9M336: 500), MaxSpeed 5.5 = 700 m/s '
                'against the 9M336 at 4.5. Refused without a lock.',
    'Weapons': {'Missile': {
        'Name': '9M37M', 'WithoutLock': 'REFUSE',
        'Guidance': {'Mode': 'INFRARED', 'LockTicks': 24, 'Range': 500, 'ConeDegrees': 40,
                     'CountermeasureVulnerability': 0.7},
        'Flight': {'InitialSpeed': 0.5, 'MaxSpeed': 5.5, 'AccelerationPerTick': 0.2, 'TurnDegreesPerSecond': 80,
                   'Damage': 40, 'BlastRadius': 4, 'TntEquivalentKg': 1.694}}},
}
LANG = {'entity.berts_vehicle_pack.strela_10m2': '9K35 Strela-10M2',
        'weapon.berts_vehicle_pack.strela_10m2_missile': '9M37M'}


def pkmb_weapon(d):
    """WT's PKMB (7_62mm_pkmb_user_machinegun: ap_i_ball 817.5 m/s, traceFreq 5, shotFreq 11.66 = 700 rpm, bulletsCartridge 250,
    reloadTime 8 s) as the pack's PKT belt (the T-64BV's coax: the same WT cartridge, 4 AP-I : 1 AP-T) on the Toyotas' hull
    station mechanics."""
    base = os.path.join(vehgen.DATA, 'sbw', 'projectile_profiles')
    src = os.path.join(base, 't64bv', 'machinegun')
    dst = os.path.join(base, VID, 'pkmb')
    os.makedirs(dst, exist_ok=True)
    for name in os.listdir(dst):
        os.remove(os.path.join(dst, name))
    for name in os.listdir(src):
        p = load(os.path.join(src, name))
        p['Combat']['WeaponId'] = f'berts_vehicle_pack:{VID}/pkmb'
        with open(os.path.join(dst, name), 'w') as fh:
            fh.write(json.dumps(p, indent=2) + '\n')
    w = json.loads(json.dumps(load(fit_existing.files_of('t64bv')['data'])['Weapons']['MachineGun'])
                   .replace('berts_vehicle_pack:t64bv/machinegun/', f'berts_vehicle_pack:{VID}/pkmb/'))
    toy = load(fit_existing.files_of('toyota_jihad_dshk')['data'])['Weapons']['DShK']
    muzzle = d['Attachments']['passenger_station_muzzle']['Position']
    sp = copy.deepcopy(toy['ShootPos'])
    sp['Positions'] = [list(muzzle)]
    sp['ViewPosition'] = list(muzzle)
    w['ShootPos'] = sp
    w['Crosshair'] = toy['Crosshair']
    w['DefaultZoom'] = toy['DefaultZoom']
    w.update({'RPM': 700, 'Magazine': 250, 'NormalReloadTime': 160, 'EmptyReloadTime': 160,
              'Name': 'weapon.berts_vehicle_pack.strela_10m2_pkmb'})
    w.pop('AutoReload', None)
    si = w['SoundInfo']
    for slot, key in (('1p', 'Fire1P'), ('3p', 'Fire3P'), ('far', 'Fire3PFar'), ('veryfar', 'Fire3PVeryFar')):
        si[key] = f'bvp_audio:weapon/pkt_{slot}' if slot != 'veryfar' else 'bvp_audio:weapon/pkt_veryfar'
    w['SoundRadius'] = 12
    return w


def finish(model, spec):
    """War Thunder and model-specific data on top of what fit_existing wrote."""
    sys.path.insert(0, os.path.join(HERE, '..', 'ballistics'))
    sys.path.insert(0, os.path.join(HERE, '..', 'damage'))
    import balance
    import driver_cameras
    paths = fit_existing.files_of(VID)
    d = load(paths['data'])
    d['VehicleIcon'] = f'berts_vehicle_pack:textures/vehicle_icon/{VID}.png'
    d['Mass'] = WT['Mass']
    d['TurretTurnSpeed'] = [round(v, 4) for v in WT['TurretTurnSpeed']]
    d['TurretPitchRange'] = WT['TurretPitchRange']
    d['EngineInfo']['MaxForwardSpeedRate'] = WT['MaxForwardSpeedRate']
    d['EngineInfo']['MaxBackwardSpeedRate'] = WT['MaxBackwardSpeedRate']
    d['EngineInfo']['Buoyancy'] = WT['Buoyancy']
    t, cls = balance.GROUND[VID]
    d['MaxHealth'] = balance.ground_hp(VID)
    d['DeathChargeKg'] = balance.death_charge_kg(VID)
    d['DamageClass'] = cls
    d['DamageModifiers'] = balance.modifiers(cls)

    # seats: 0 operator (the pack's controller seat: drives the launcher and fires from the launcher sight), 1 driver (the
    # same sight point, fixed to the hull: the owner's rule), 2 commander. All in the hull; dismount through the rear.
    hull_lo, hull_hi = model.box(HULL)
    rear = -(float(hull_hi[2]) / 16 + 1.3)
    dismount = {'Transform': 'Vehicle', 'Position': [0, 0.45, round(rear, 5)]}
    sight = spec['attachments']['gunnerCamera']['at']
    eye = mtbgeo.geo_to_data(np.array(sight))
    for i, seat in enumerate(d['Seats']):
        seat['BodyAttachment'] = 'Turret' if i == 0 else 'Vehicle'
        seat['HidePassenger'] = True
        seat['IsEnclosed'] = True
        seat['DismountInfo'] = dict(dismount)
        if i == 0:
            seat['Weapons'] = ['Missile']
            seat['MinPitch'], seat['MaxPitch'] = WT['TurretPitchRange']
            seat['Sensitivity'] = [0.45, 0.52, 0.8]
            seat['CanRotateHead'] = False
            seat['CameraPos'] = {'Transform': 'Barrel', 'Direction': 'Barrel', 'UseFixedCameraPos': True,
                                 'Position': [0, 0, 0], 'ZoomPosition': [0, 0, 0],
                                 'EyeAttachment': 'gunnerCamera', 'ZoomEyeAttachment': 'gunnerCamera',
                                 'DirectionAttachment': 'missileMuzzle_1', 'ZoomDirectionAttachment': 'missileMuzzle_1',
                                 'CameraMode': 'PLAYER_LOOK_AIM', 'AimCameraMode': 'PLAYER_LOOK_AIM'}
        elif i == 1:
            seat['Weapons'] = []
            seat['CameraPos'] = driver_cameras.camera()
            seat['CanRotateHead'] = True
        else:
            # the pintle PKMB gunner (the Toyota technicals' hull station seat, frame WeaponStation: the position is
            # relative to the station pivot)
            toy = load(fit_existing.files_of('toyota_jihad_dshk')['data'])['Seats'][2]
            pos = seat['Position']
            seat.clear()
            seat.update(copy.deepcopy(toy))
            seat['Position'] = pos
            seat['MinPitch'], seat['MaxPitch'] = MG_PITCH_RANGE
            seat['Weapons'] = ['PKMB']
            seat['DismountInfo'] = dict(dismount)
    d['Attachments']['driver_camera'] = {'Parent': 'VehicleCustomPitch', 'Position': [round(float(v), 5) for v in eye],
                                         'Direction': [0, 0, 1]}
    d.pop('WeaponPairs', None)

    # weapon: the 9M37M. The mechanism is the Vodnik AA's IR proxy (superbwarfare:ru_9m336_missile, a native weapon the
    # GroundSamLauncher turns into an FFA interceptor with ground_sams/strela_10m2.json); WT's figures on top
    base = os.path.join(vehgen.DATA, 'sbw', 'projectile_profiles', VID, 'missile')
    for name in os.listdir(base) if os.path.isdir(base) else []:
        os.remove(os.path.join(base, name))
    os.makedirs(base, exist_ok=True)
    vod = load(fit_existing.files_of('gaz_3937_vodnik_aa')['data'])['Weapons']['Missile']
    w = copy.deepcopy(vod)
    shoot = d['Weapons']['Missile']['ShootPos']
    shoot['BoundUpWithAmmoAmount'] = True
    w['ShootPos'] = shoot
    w.update(MISSILE)
    w['ExplosionDamage'] = 55
    w['ExplosionRadius'] = 7
    old = 'berts_vehicle_pack:gaz_3937_vodnik_aa/vodnik_aa_ir_proxy'
    new = f'berts_vehicle_pack:{VID}/missile/default'
    text = json.dumps(w).replace(old, new)
    w = json.loads(text)
    for e in w['AmmoType']:
        e['Override']['TntEquivalentKg'] = MISSILE['TntEquivalentKg']
    w['ApDurability'] = 28
    p = load(os.path.join(vehgen.DATA, 'sbw', 'projectile_profiles', 'gaz_3937_vodnik_aa', 'vodnik_aa_ir_proxy.json'))
    c = p['Combat']
    c['WeaponId'] = f'berts_vehicle_pack:{VID}/missile'
    c['RoundId'] = 'berts_vehicle_pack:9m37m_sam'
    c['CaliberMm'] = c['DiameterMm'] = 120      # WT rocket caliber 0.12
    c['HullDamage'], c['ModuleDamage'], c['AmmoRackDamage'] = 55, 33, 225     # between the Stinger (0.54 kg: 45) and 9M311 (4.62 kg: 80)
    c['PenetrationMm'] = 28.0                   # WT ArmorPower0m 28
    c['PenetrationCurve'] = {'DistancesMetres': [10, 100, 500, 1000, 1500, 2000],
                             'PenetrationMm': [28.0, 25.0, 25.0, 25.0, 25.0, 25.0]}
    with open(os.path.join(base, 'default.json'), 'w') as fh:
        fh.write(json.dumps(p, indent=2) + '\n')
    d['Weapons'] = {'Missile': w, 'PKMB': pkmb_weapon(d)}
    # the hull-parented station (absolute positions: VehiclePassengerStationFrame.base(vehicleTransform, pivot)), as the Toyotas
    toy = load(fit_existing.files_of('toyota_jihad_dshk')['data'])
    b = {x['name']: x for x in load(paths['geo'])['minecraft:geometry'][0]['bones']}
    yaw_p, pitch_p = np.array(b['passengerWeaponStationYaw']['pivot']), np.array(b['passengerWeaponStationPitch']['pivot'])
    d['PassengerWeaponStationPos'] = [round(float(v), 5) for v in mtbgeo.geo_to_data(yaw_p)]
    d['PassengerWeaponStationBarrelPos'] = [round(float(v), 5) for v in mtbgeo.geo_to_data(pitch_p - yaw_p)]
    d['PassengerWeaponStationControllerIndex'] = 2
    d['PassengerWeaponStationTurnSpeed'] = [round(MG_TURN / NATIVE_RATE, 4)] * 2
    d['PassengerWeaponStationYawRange'] = MG_YAW_RANGE
    d['PassengerWeaponStationPitchRange'] = MG_PITCH_RANGE
    d['PassengerWeaponStationBinding'] = {'Schema': 'berts_vehicle_pack:passenger_bed_weapon_station/v1', 'Parent': 'HULL',
                                          'WeaponId': 'PKMB', 'WeaponKind': 'HEAVY_MACHINE_GUN',
                                          'CameraAttachment': 'passenger_station_camera',
                                          'MuzzleAttachment': 'passenger_station_muzzle'}
    # sounds: the pack's SAM launch family (Lizardpyle igla_fire, tools/audio/lizard_manifest.json weapons.igla), set by
    # strela_audio.py; the engine and the TaP MT-LB recording likewise
    with open(paths['data'], 'w') as fh:
        fh.write(json.dumps(d, indent=2) + '\n')

    # ground SAM definition (data/<ns>/sbw/ground_sams/<vehicle>.json: BVP-source-owned, main resources)
    sam = os.path.join(vehgen.MAIN, 'resources', 'data', vehgen.NS, 'sbw', 'ground_sams', VID + '.json')
    os.makedirs(os.path.dirname(sam), exist_ok=True)
    with open(sam, 'w') as fh:
        fh.write(json.dumps(SAM, indent=2) + '\n')

    # exhaust: the model's roof louvres (EngineExhaust is in the MODEL frame: final px / 16, +Z = rear, not the attachment
    # frame). Origin at the centre of the louvre top, 0.25 px above the face
    x0, y, z0, x1, _, z1 = LOUVRE_BOX
    mid = model.geo(((x0 + x1) / 2, y, (z0 + z1) / 2))
    origin = [round(float(mid[0]) / 16, 5), round((float(mid[1]) + 0.25) / 16, 5), round(float(mid[2]) / 16, 5)]
    c = load(paths['client'])
    c.pop('FittedGroundRig', None)
    c['EngineExhaust']['Origins'] = [{'Id': 'strela_exhaust_000', 'Position': origin}]
    with open(paths['client'], 'w') as fh:
        fh.write(json.dumps(c, indent=2) + '\n')
    return [round(float(v), 5) for v in eye], origin


def write_textures(model):
    """Entity texture (the model's own PNG plus the 9P149's track-link rows), wreck texture (x0.178)."""
    assets = vehgen.ASSETS
    own = Image.open(__import__('io').BytesIO(model.src.png)).convert('RGBA')
    tpl = Image.open(os.path.join(assets, 'textures', 'entity', f'{TEMPLATE}.png')).convert('RGBA')
    img = Image.new('RGBA', (TEX_W, TEX_H), (0, 0, 0, 0))
    img.paste(own, (0, 0))
    img.paste(tpl.crop((0, own.height, TEX_W, TEX_H)), (0, own.height))      # the track-link texture
    img.save(os.path.join(assets, 'textures', 'entity', f'{VID}.png'))
    a = np.asarray(img).astype(float)
    dead = a.copy()
    dead[..., :3] = np.clip(np.round(a[..., :3] * vehgen.DEAD), 0, 255)
    Image.fromarray(dead.astype(np.uint8)).save(os.path.join(assets, 'textures', 'entity_dead', f'{VID}.png'))


def add_entity_lang():
    """fit_existing.register inserts the weapon lang keys after the entity key, so the entity key goes in first, after
    the template's (an anchored insertion between two complete lines)."""
    path = os.path.join(vehgen.ASSETS, 'lang', 'en_us.json')
    src = open(path, encoding='utf-8', newline='').read()
    key = f'entity.{vehgen.NS}.{VID}'
    if f'"{key}"' in src:
        return
    tkey = f'  "entity.{vehgen.NS}.{TEMPLATE}": '
    i = src.index(tkey)
    j = src.index('\n', i) + 1
    nl = '\r\n' if src[j - 2:j] == '\r\n' else '\n'
    line = f'  "{key}": {json.dumps(LANG[key])},{nl}'
    open(path, 'w', encoding='utf-8', newline='').write(src[:j] + line + src[j:])


def add_containers():
    """creative tab, containers and the scale record, after the template (as vehgen.register does)."""
    data = vehgen.DATA
    for rel, pattern, insert in (('creative_tabs.json', f'"{TEMPLATE}",', f'"{VID}",'),
                                 ('sbw/containers/mobile_vehicles.json', f'"{vehgen.NS}:{TEMPLATE}",',
                                  f'"{vehgen.NS}:{VID}",')):
        path = os.path.join(data, rel)
        src = open(path, encoding='utf-8', newline='').read()
        if insert in src:
            continue
        i = src.index(pattern)
        line_start = src.rfind('\n', 0, i) + 1
        indent = src[line_start:i]
        j = src.index('\n', i) + 1
        nl = '\r\n' if src[j - 2:j] == '\r\n' else '\n'
        open(path, 'w', encoding='utf-8', newline='').write(src[:j] + indent + insert + nl + src[j:])
    path = os.path.join(data, 'sbw', 'containers', 'land_vehicles.json')
    src = open(path, encoding='utf-8', newline='').read()
    if f'"{vehgen.NS}:{VID}"' not in src:
        m = re.search(rf'( *)\{{\r?\n *"Type": "{vehgen.NS}:{TEMPLATE}",\r?\n *"Weight": (\d+)\r?\n *\}},\r?\n', src)
        block = m.group(0).replace(f'{vehgen.NS}:{TEMPLATE}', f'{vehgen.NS}:{VID}')
        open(path, 'w', encoding='utf-8', newline='').write(src[:m.end()] + block + src[m.end():])
    path = os.path.join(vehgen.REPO, 'tools', 'vehicle_scale', 'applied.json')
    src = open(path, encoding='utf-8', newline='').read()
    if f'"{VID}"' not in src:
        applied = json.loads(src)
        applied[VID] = 1.1       # vehgen sizes ground vehicles against their (already x1.1) template
        nl = '\r\n' if '\r\n' in src else '\n'
        text = json.dumps(dict(sorted(applied.items())), indent=1).replace('\n', nl) + (nl if src.endswith('\n') else '')
        open(path, 'w', encoding='utf-8', newline='').write(text)


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
        side['RoadCenters'] = [round(float(model.geo((ROAD_X[k], 0, 0))[2]), 5) for k in range(6)][::-1]
    orig = {'geo': geo, 'wreck': load(files['wreck']), 'client': client, 'data': {}, 'armor': {}}
    fit_existing.originals = lambda vid, w: orig
    if write:
        add_entity_lang()
        add_containers()
    rc = fit_existing.main([VID] + (['--write'] if write else []))
    if write and rc == 0:
        sys.path.insert(0, os.path.join(HERE, '..', 'track_path'))
        import belt                                   # the link belt round this model's wheels (tools/track_path)
        _, msg = belt.rebuild(VID, write=True)
        print('belt:', msg)
        PB.fit_fallback_tracks(fit_existing.files_of(VID)['geo'], tpl)
        eye, origin = finish(model, spec)
        import strela_audio                           # engine / launch sound keys of the data (the audio files: strela_audio.py)
        strela_audio.profile_and_data()
        write_textures(model)
        print('driver_camera (data frame):', eye, 'exhaust origin (model frame):', origin)
    return rc


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
