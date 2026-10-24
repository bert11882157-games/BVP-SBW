#!/usr/bin/env python3
"""PGZ-95 (pgz_95): the supplied PGZ-95.mtb fitted as a tracked SPAAG on the pack's Marder rig.

    python3 tools/vehgen/pgz95_build.py [--write] [--sources DIR] [--dump OUT.json]
    (then obb_armor_cover.py pgz_95, pgz95_icon.py, tools/icon_flags/flags.py --write pgz_95, exhaust_check.py pgz_95,
     tools/audio/lizard_import.py SRC --only pgz_95)

The source is 658 elements in five parts. Elements by index (toolbox frame +x forward, +y down, +z left):

    part 0   hull (0-33, 38-63, 71, 196-226, 296, 306, 308, 309, 656, 657): deck, glacis, side skirts, front antennas
    part 3   turret (34-37 ring, 64-90, 151-195 radar pedestal and dish, 293-295, 297-305, 307, 650-655 sight head)
    part 4   the gun cradle (91-150, 227-292): two housings with two 25 mm barrels each (z +-12.7 upper, +-15.7 lower,
             muzzle brakes at x 28) and the two QW-2 launchers (two missiles a side, x -23 .. 4.5) riding on them
    part 9/10  left / right running gear: per side six road wheels of 28 elements (a rim ring, bolts, spokes and a hub
             disc, centred on the element position x = 54.5 / 37.5 / 23.5 / 6.5 / -8.5 / -22.5) and two stray hub
             discs (311, 312 / 648, 649) that sit inside the hull and are dropped
The model has no tracks, sprocket or idler.

It is built as a new vehicle in two steps:
  1. geometry (this file): the Marder's bone hierarchy and 96-link rubber track; the sprocket (front) and idler (rear)
     the source lacks are built; tools/track_path/belt.py then refits the belt round all eight wheels per side;
  2. systems (tools/vehgen/fit_existing.py, spec injected below): the 9K22 Tunguska's data and light armour layout
     (a SPAAG, not an IFV hull), the four-barrel cannon and the four-missile launcher; then War Thunder's PGZ04A on top
     (the nearest WT unit: the same PG87 25 mm guns and four missiles; WT has no PGZ-95).
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
import puma_build as P  # noqa: E402  (disc / polygon / sprocket_profile / merge / widen_links / shift_bone helpers)

mtb = mtbgeo.mtb
VID = 'pgz_95'
GEO_TEMPLATE = 'marder_1a5'           # bones, belt, wreck, client running gear
DATA_TEMPLATE = '9k22_tunguska'       # data, armour, weapons
SOURCE = 'PGZ-95.mtb'
SCALE = 1.0              # px per toolbox unit: 6.71 m hull / 117.5 units at the pack's 17.6 px per m = 1.005
XC = 17.75               # toolbox x of the hull centre (hull -41 .. 76.5)
WHEEL_BOTTOM = 1.0       # px: wheel bottoms over the ground plane (the link plate under them)
GROUND_Y = 8.0           # toolbox y of the road wheel bottoms (centre 2.5, radius 5.5)
TEX_W, TEX_H = 1216, 1024     # the model's 1024 columns plus the 192 the Marder's track-link bones sample (x 1024-1215)
TRACK_WIDTH = 8.0        # px: the road wheel pair spans z 18.5 .. 26.5 (0.45 m)
WHEEL_ZC = 22.5          # |z| of the wheel / track centre (hub disc 18.5..21.5, rim ring 24.5..26.5)
WHEEL_Y = 2.5
SPR_Z = (19.0, 26.0)     # |z| span of the built sprocket and idler

STRAY_HUBS = (311, 312, 648, 649)
SPROCKET = (70.0, -1.0, 5.2)    # toolbox x, y, radius: front, raised (the track climbs to it)
IDLER = (-33.0, -1.0, 5.2)      # rear
TURRET_PIVOT = (0.0, -21.0, 0.0)    # turret ring centre (the ring plates 34-37, 64-70 are centred x ~0, z 0)
GUN_PIVOT = (-8.0, -33.5, 0.0)      # trunnion: mid housing, between the barrel pairs
MUZZLE = (28.0, -33.7, 0.0)         # centre of the four muzzle brakes (exhaust_check compares the barrel bone's tip mean)
# the four 25 mm barrels: (x of the muzzle brake end, y, z); left = +z. Upper pair z +-12.7 y -35.7, lower pair z +-15.7 y -31.7
CANNON_MUZZLES = [(28.0, -35.7, 12.7), (28.0, -35.7, -12.7), (28.0, -31.7, 15.7), (28.0, -31.7, -15.7)]
# the four QW-2: two a side on the housing tops (centres y -42.75 upper, -39.25 lower, z +-12.25), nose at x 4.5
MISSILE_MUZZLES = [(5.5, -42.75, 12.25), (5.5, -39.25, 12.25), (5.5, -42.75, -12.25), (5.5, -39.25, -12.25)]
GUNNER_EYE = (31.0, -30.5, 0.0)     # ahead of the electro-optical head on the turret front (elements 84-90, x to 29.5)
COMMANDER_EYE = (14.0, -42.5, -8.0)  # over the turret roof, right front (a placed point: the model has no commander optic)
EXHAUST = (35.0, -20.45, -7.25)     # the louvred roof pad of the mid deck (element 20: x 24..46, z -14.5..0, top y -20.2), 0.25 px above it
SEATS = {          # pelvis points, toolbox frame (turret crew sit in the hull under the ring: the turret is unmanned in the pack)
    'gunner': (4.0, -5.0, 8.0), 'commander': (4.0, -5.0, -8.0), 'driver': (40.0, -5.0, 10.0),
}


class PFrame(mtbgeo.Frame):
    """toolbox -> geo, hull centre on z 0: [z s, -y s + yOff, -(x - XC) s]."""

    def geo(self, p):
        x, y, z = p
        return np.array([z * self.s, -y * self.s + self.y_off, -(x - XC) * self.s])


load = vehgen.load
merge, disc, polygon, sprocket_profile, tex_uv = P.merge, P.disc, P.polygon, P.sprocket_profile, P.tex_uv


def part_ids(E, part):
    return [i for i, f in enumerate(E) if int(f[4]) == part]


class Model:
    def __init__(self, sources):
        self.src = mtbgeo.Source(os.path.join(sources, SOURCE))
        self.E = self.src.elements
        self.frame = PFrame(SCALE, GROUND_Y * SCALE + WHEEL_BOTTOM)
        self.hull = part_ids(self.E, 0)
        self.turret = part_ids(self.E, 3)
        self.gun = part_ids(self.E, 4)
        self.wheel_ids, self.road_x = self.find_wheels()

    def geo(self, p):
        return self.frame.geo(p)

    def find_wheels(self):
        """{(side, index front to rear): [element ids]} and the road wheel centres, front to rear: the elements of parts 9
        (left) and 10 (right) grouped by their position x (every piece of a wheel carries the wheel centre)."""
        out, xs = {}, None
        for side, part in (('L', 9), ('R', 10)):
            groups = {}
            for i in part_ids(self.E, part):
                if i in STRAY_HUBS:
                    continue
                groups.setdefault(round(mtb.num(self.E[i][6]), 2), []).append(i)
            keys = sorted(groups, reverse=True)
            assert len(keys) == 6 and all(len(groups[k]) == 28 for k in keys), (side, [(k, len(groups[k])) for k in keys])
            for k, key in enumerate(keys):
                out[(side, k)] = groups[key]
            xs = xs or keys
            assert xs == keys, (xs, keys)
        return out, xs

    def points(self, i):
        """every vertex of one element (toolbox frame), shapes included"""
        return [p for pos, _ in mtb.element_polys(self.E[i], TEX_W, TEX_H) for p in pos]

    def mesh(self, ids):
        return mtbgeo.mesh([self.E[i] for i in ids], self.frame, TEX_W, TEX_H)

    def box(self, ids):
        c = np.array([self.geo(p) for i in ids for p in self.points(i)])
        return c.min(0), c.max(0)

    def corners_box(self, lo, hi):
        c = np.array([self.geo((x, y, z)) for x in (lo[0], hi[0]) for y in (lo[1], hi[1]) for z in (lo[2], hi[2])])
        return c.min(0), c.max(0)


TRACK_X = {}


def build_geo(model, tpl):
    """The fitted model in final px: the Marder's bones and 96-link tracks, the PGZ-95's meshes."""
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

    bones['hull']['poly_mesh'] = model.mesh(model.hull)
    bones['turret']['poly_mesh'] = model.mesh(model.turret)
    bones['turret']['pivot'] = [round(float(v), 5) for v in g_(TURRET_PIVOT)]
    bones['barell']['poly_mesh'] = model.mesh(model.gun)
    bones['barell']['pivot'] = [round(float(v), 5) for v in g_(GUN_PIVOT)]
    bones['hull']['pivot'] = [0, round(float(g_((0, WHEEL_Y, 0))[1]), 5), 0]

    rim_uv = tex_uv(model, model.wheel_ids[('L', 0)])
    for side, sign in (('L', 1), ('R', -1)):
        cy = float(g_((0, WHEEL_Y, 0))[1])
        xp = sign * WHEEL_ZC * s
        for k in range(6):
            b = bones[f'wheel{side}{k}']
            b['poly_mesh'] = model.mesh(model.wheel_ids[(side, k)])
            b['pivot'] = [round(xp, 5), round(cy, 5), round(float(g_((model.road_x[k], 0, 0))[2]), 5)]
        x0, x1 = sorted((sign * SPR_Z[0] * s, sign * SPR_Z[1] * s))
        for name, (tx, ty, tr) in (('Front', SPROCKET), ('Rear', IDLER)):
            cy2 = float(g_((0, ty, 0))[1])
            cz = float(g_((tx, 0, 0))[2])
            r = tr * s
            cap = sorted((sign * (SPR_Z[0] + 1.5) * s, sign * (SPR_Z[1] + 0.8) * s))
            if name == 'Front':
                parts = [disc(0, cy2, cz, sprocket_profile(0.86 * r, r), x0 - sign * 0.2 * s, x1 + sign * 0.2 * s, rim_uv),
                         disc(0, cy2, cz, polygon(0.62 * r, 16), *cap, rim_uv)]
            else:
                parts = [disc(0, cy2, cz, polygon(r, 24), x0 - sign * 0.2 * s, x1 + sign * 0.2 * s, rim_uv),
                         disc(0, cy2, cz, polygon(0.6 * r, 16), *cap, rim_uv)]
            b = bones[f'wheel{side}{name}']
            b['poly_mesh'] = merge(*parts)
            b['pivot'] = [round(xp, 5), round(cy2, 5), round(cz, 5)]

    # the track: the Marder's links widened to 8 px and moved onto the wheel plane (|x| = 22.5 px)
    tx = WHEEL_ZC * s
    for side, sign in (('L', 1), ('R', -1)):
        P.widen_links(bones, side, TRACK_WIDTH)
        ref = bones[f'trackRot{side}0']['pivot'][0]
        dx = sign * tx - ref
        for name, b in bones.items():
            if name.startswith((f'trackRot{side}', f'trackMov{side}', f'brokenTrack{side}', f'crudeTrack{side}',
                                f'Track{side}')):
                P.shift_bone(b, dx=dx)
        TRACK_X[side] = round(sign * tx, 5)
    lo, hi = P.bounds_of(geo)
    size = hi - lo
    geo['description']['visible_bounds_width'] = round(float(max(size[0], size[2]) / 16 + 1), 2)
    geo['description']['visible_bounds_height'] = round(float(size[1] / 16 + 1), 2)
    geo['description']['visible_bounds_offset'] = [0, round(float((lo[1] + hi[1]) / 32), 2), 0]
    return g


def px(model, p):
    """Toolbox point -> final model px [x, y, z] (+X left, +Y up, nose -Z)."""
    return [round(float(v), 4) for v in model.geo(p)]


def make_spec(model):
    t = lambda *p: px(model, p)
    hull_lo, hull_hi = model.box(model.hull)
    high = [i for i in model.turret if min(c[1] for c in model.points(i)) < -42.0]
    body = [i for i in model.turret if i not in high]
    tb_lo, tb_hi = model.box(body)
    rd_lo, rd_hi = model.box(high)
    left = [i for i in model.gun if np.mean([c[2] for c in model.points(i)]) > 0]
    right = [i for i in model.gun if i not in left]
    l_lo, l_hi = model.box(left)
    r_lo, r_hi = model.box(right)
    eng_lo, eng_hi = model.corners_box((44.0, -21.0, -20.0), (76.5, 3.0, 20.0))
    seat = lambda name: t(*SEATS[name])
    spec_seats = []
    for name in ('gunner', 'commander', 'driver'):
        p = seat(name)
        p[1] = round(p[1] - 12.0, 4)           # entity feet: the hips (cushion contact) sit 12 px over them
        spec_seats.append({'frame': 'Vehicle', 'at': p, 'fromTemplate': 0})
    at = {
        'gunnerCamera': {'parent': 'Turret', 'at': t(*GUNNER_EYE)},
        'commanderCamera': {'parent': 'Turret', 'at': t(*COMMANDER_EYE)},
        'mainMuzzle': {'parent': 'Barrel', 'at': t(*MUZZLE)},
    }
    for n, m in enumerate(CANNON_MUZZLES, 1):
        at[f'cannonMuzzle_{n}'] = {'parent': 'Barrel', 'at': t(*m)}
    for n, m in enumerate(MISSILE_MUZZLES, 1):
        at[f'missileMuzzle_{n}'] = {'parent': 'Barrel', 'at': t(*m)}
    return {
        'id': VID,
        'template': DATA_TEMPLATE,
        'hitbox': (3.4, 3.0),
        'geometry': {'scale': 1.0},
        'obb': [
            {'box': [list(hull_lo), list(hull_hi)]},
            {'box': [list(eng_lo), list(eng_hi)], 'part': 'MainEngine'},
            {'box': [[float(v) for v in tb_lo], [float(v) for v in tb_hi]], 'transform': 'Turret', 'part': 'Turret'},
            {'box': [[float(v) for v in rd_lo], [float(v) for v in rd_hi]], 'transform': 'Turret', 'part': 'Turret'},
            {'box': [[float(v) for v in l_lo], [float(v) for v in l_hi]], 'transform': 'Barrel', 'part': 'Turret'},
            {'box': [[float(v) for v in r_lo], [float(v) for v in r_hi]], 'transform': 'Barrel', 'part': 'Turret'},
        ],
        'attachments': at,
        'seats': spec_seats,
        'muzzles': {'Cannon': [f'cannonMuzzle_{n}' for n in range(1, 5)],
                    'Missile': [f'missileMuzzle_{n}' for n in range(1, 5)]},
    }


# ------------------------------------------------------------------ War Thunder data
# WT has no PGZ-95. The nearest unit is cn_pgz_04a (PGZ04A, the PGZ-95's successor: the same four PG87 25 mm guns and
# four IR missiles on the same turret layout), datamine gamedata/units/tankmodels/cn_pgz_04a.blkx and
# gamedata/weapons/groundmodels_weapons/25mm_pg87_pgz_user_cannon.blkx / 72mm_hn_6_launcher_user_cannon.blkx:
#   4 x 25mm_PG87_PGZ_user_cannon: shotFreq 10 (600 rpm each, 2,400 rpm), 258 rounds each (1,032), unit reloadTime 4.0,
#     pitch -5 .. +85, speedYaw 80 / speedPitch 60 deg/s (no modification changes them)
#     bullets: 25mm_type_87_api (ap_t, 0.25 kg, 1050 m/s, 30mmWhite tracer) and 25mm_type_87_he (frag_i_t, a_ix_2
#     0.019 kg, 1050 m/s, red tracer); wiki penetration at 10/100/500/1000/1500/2000 m: AP 63/61/51/41/33/27, HE 8
#   72mm_HN_6 launcher: 4 rounds, shotFreq 1, reloadTime 15, rangeMax 5000 m, optical IR seeker (warmUpTime 2, lockAngleMax 15
#     deg, rangeBand 6,000 m, fov 3), speed 30 .. 600 m/s, a_ix_2 0.6 kg
#   mass 22.5 t (VehiclePhys TakeOff 22,500, empty 21,800), 75 / -10 km/h, crew driver, gunner, commander
#   sensors cn_clc_1_search (CLC-1: range 10 km, 360 deg, period 1 s), sam_tv_ir_track (TV/IR tracker, 12 km)
#   radarAntenaSpeed 720 deg/s (the search antenna spins in WT; the pack's fitted ground rig has no spin channel)
ROUNDS = {      # id: (tracer, m/s, TNT kg or None, penetration at 10 m mm, donor round, WT name, shell type)
    'type87_api_t': ('WHITE', 1050, None, 63, '3ubr6_ap_t', 'Type 87 AP-T', 'AP'),
    'type87_hei_t': ('RED', 1050, 0.02926, 18, 'm792_hei_t_1', 'Type 87 HE-T', 'HE'),   # 0.019 kg A-IX-2 x 1.54
}
PEN_CURVE = {'type87_api_t': [63, 61, 51, 41, 33, 27]}
HE_PEN = 18          # owner rule (DamageNormalizationDataTest): autocannon HE penetrates >= 0.7 x calibre = 17.5 (WT: 8)
CANNON_BELTS = [('Type 87 AP', [('type87_api_t', 1)]),                       # best AP belt first (docs/WT_BELTS.md 2026-10-02)
                ('Default', [('type87_api_t', 1), ('type87_hei_t', 1)]),
                ('Type 87 HE', [('type87_hei_t', 1)])]
WT = {
    'Mass': 22.5,
    'TurretTurnSpeed': [52.17, 69.57],   # [pitch, yaw]; VehicleEntity multiplies by 1.15: WT 60 pitch / 80 yaw deg/s
    'TurretPitchRange': [-5.0, 85.0],
    'MaxForwardSpeedRate': 0.816,        # WT maxFwdSpeed 75 (the M1A1 / TUSK scale: 0.816 = 75 km/h)
    'MaxBackwardSpeedRate': 0.1088,      # WT maxRevSpeed 10
}
SOUND = {'Cannon': 'type87_25'}
RADIUS = {'type87_25': 24}
LANG = {'entity.berts_vehicle_pack.pgz_95': 'PGZ-95',
        'weapon.berts_vehicle_pack.pgz_95_cannon': '4 x 25 mm Type 87',
        'weapon.berts_vehicle_pack.pgz_95_missile': 'QW-2'}


def write_profiles(w_name, rounds, base_dir, wt_belts, cat):
    """New round profiles of a weapon from donor rounds, with this vehicle's weapon / round ids and WT penetration."""
    entries = []
    for n, (rid, spec) in enumerate(rounds.items()):
        tracer, ms, tnt, pen, donor, name, shell = spec
        if shell == 'HE':
            pen = max(pen, HE_PEN)
        dvid, dwn, dentry = cat[donor]
        e = copy.deepcopy(dentry)
        for k in wt_belts.ENTRY_KEYS_DROP:
            e.get('Override', {}).pop(k, None)
        src = wt_belts.profile_path(e['Override']['Projectile']['Profile'])
        p = json.load(open(src))
        c = p['Combat']
        k25 = (25.0 / c.get('CaliberMm', 30)) ** 2
        c['WeaponId'] = f'berts_vehicle_pack:{VID}/{w_name.lower()}'
        c['RoundId'] = f'berts_vehicle_pack:{rid}'
        c['CaliberMm'] = c['DiameterMm'] = 25
        for key in ('HullDamage', 'ModuleDamage', 'AmmoRackDamage'):
            if key in c and donor == '3ubr6_ap_t':
                c[key] = max(1, round(c[key] * k25))
        c['PenetrationMm'] = pen
        if rid in PEN_CURVE:
            c['PenetrationCurve'] = {'DistancesMetres': [10, 100, 500, 1000, 1500, 2000], 'PenetrationMm': PEN_CURVE[rid]}
        else:
            c['PenetrationCurve'] = {'DistancesMetres': [10, 100, 500, 1000, 1500, 2000], 'PenetrationMm': [pen] * 6}
        pid = f'ammo_{n:02d}_{rid}'
        with open(os.path.join(base_dir, pid + '.json'), 'w') as fh:
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

    # seats: 0 gunner (the pack's controller seat: drives and fires from the turret-front sight), 1 commander (over the
    # turret roof, aims where the guns do), 2 driver (the sight point, fixed to the hull: the owner's rule). All in the hull.
    hull_lo, hull_hi = model.box(model.hull)
    rear = -(float(hull_hi[2]) / 16 + 1.3)
    dismount = {'Transform': 'Vehicle', 'Position': [0, 0.45, round(rear, 5)]}
    sight = spec['attachments']['gunnerCamera']['at']
    eye = mtbgeo.geo_to_data(np.array(sight))
    for i, seat in enumerate(d['Seats']):
        seat['Transform'] = 'Vehicle'
        seat['BodyAttachment'] = 'Vehicle'
        seat['HidePassenger'] = True
        seat['IsEnclosed'] = True
        seat['DismountInfo'] = dict(dismount)
        if i == 0:
            seat['Weapons'] = ['Cannon', 'Missile']
            seat['MinPitch'], seat['MaxPitch'] = WT['TurretPitchRange']
            seat['HasThermalImaging'] = True
            seat['CanRotateHead'] = False
        elif i == 1:                        # commander: aims where the guns do
            seat.pop('Weapons', None)
            seat['CameraPos'] = {'Transform': 'Turret', 'UseFixedCameraPos': True, 'Position': [0, 0, 0],
                                 'ZoomPosition': [0, 0, 0], 'Direction': 'Turret', 'EyeAttachment': 'commanderCamera',
                                 'ZoomEyeAttachment': 'commanderCamera', 'DirectionAttachment': 'mainMuzzle',
                                 'ZoomDirectionAttachment': 'mainMuzzle', 'CameraMode': 'PLAYER_LOOK_AIM',
                                 'AimCameraMode': 'PLAYER_LOOK_AIM'}
            seat['MinPitch'], seat['MaxPitch'] = WT['TurretPitchRange']
            seat['HasThermalImaging'] = False
            seat['CanRotateHead'] = False
        else:                               # driver: looks out of the gunner's sight point, fixed to the hull
            seat.pop('Weapons', None)
            seat['CameraPos'] = driver_cameras.camera()
            seat['CanRotateHead'] = True
    d['Attachments']['driver_camera'] = {'Parent': 'VehicleCustomPitch', 'Position': [round(float(v), 5) for v in eye],
                                         'Direction': [0, 0, 1]}
    d.pop('WeaponPairs', None)

    # cannon -------------------------------------------------------------------------------------------------------------
    cat = wt_belts.catalogue()
    base = os.path.join(vehgen.DATA, 'sbw', 'projectile_profiles', VID)
    folder = os.path.join(base, 'cannon')
    if os.path.isdir(folder):
        for name in os.listdir(folder):
            os.remove(os.path.join(folder, name))
    os.makedirs(folder, exist_ok=True)
    wt_belts.ROUNDS.update({k: (v[0], v[1], v[2]) for k, v in ROUNDS.items()})
    notes = []
    w = d['Weapons']['Cannon']
    entries = write_profiles('Cannon', ROUNDS, folder, wt_belts, cat)
    w['AmmoType'] = [e for _, e, _ in entries]
    w['ProjectileBeltAmmoType'] = []
    wt_belts.rebuild(VID, 'Cannon', w, 'GENERIC', CANNON_BELTS, {}, {}, notes)
    first = w['AmmoType'][0]['Override']['Projectile']['Profile'].split(':', 1)[1]
    import shutil
    shutil.copyfile(os.path.join(vehgen.DATA, 'sbw', 'projectile_profiles', first + '.json'), os.path.join(folder, 'default.json'))
    w['Projectile']['Profile'] = f'berts_vehicle_pack:{VID}/cannon/default'
    w['NominalBallistics']['ProjectileProfile'] = f'berts_vehicle_pack:{VID}/cannon/default'
    w.pop('DepletionReload', None)
    w.update({'RPM': 2400, 'Magazine': 1032, 'NormalReloadTime': 80, 'EmptyReloadTime': 80,
              'Name': 'weapon.berts_vehicle_pack.pgz_95_cannon',
              'Icon': 'superbwarfare:textures/overlay/vehicle/weapon/icons/cannon_30mm_ap.png'})
    w['ApDurability'] = w['AmmoType'][0]['Override']['ApDurability']
    w.update({'ShellType': 'AP', 'Damage': w['AmmoType'][0]['Override']['Damage'], 'TntEquivalentKg': 0.0, 'ExplosionRadius': 0})
    si = w['SoundInfo']
    si.pop('VehicleReload', None)
    si.pop('VehicleReloadClipDurationTicks', None)
    for wn, fam in SOUND.items():
        info = d['Weapons'][wn]['SoundInfo']
        info['Fire1P'] = f'bvp_audio:weapon/{fam}_1p'
        info['Fire3P'] = f'bvp_audio:weapon/{fam}_3p'
        info['Fire3PFar'] = f'bvp_audio:weapon/{fam}_far'
        info['Fire3PVeryFar'] = f'bvp_audio:weapon/{fam}_veryfar'
        for gname in ('Fire1PGain', 'Fire3PGain', 'Fire3PFarGain', 'Fire3PVeryFarGain'):
            info[gname] = 1.0
        d['Weapons'][wn]['SoundRadius'] = RADIUS[fam]

    # missile: the pack's IR SAM weapon (the Vodnik AA's: ru_9m336 projectile, FIM-92 icon, javelin lock tones), four QW-2.
    # Guidance is the ground_sams file (INFRARED, refuses a shot without a lock); this keeps the native weapon's data
    src_d = load(fit_existing.files_of('gaz_3937_vodnik_aa')['data'])
    m = copy.deepcopy(src_d['Weapons']['Missile'])
    ns = 'berts_vehicle_pack'
    text = json.dumps(m).replace(f'{ns}:gaz_3937_vodnik_aa/vodnik_aa_ir_proxy', f'{ns}:{VID}/missile/qw2_ir')
    m = json.loads(text)
    m['ShootPos'] = d['Weapons']['Missile']['ShootPos']
    m.update({'Name': 'weapon.berts_vehicle_pack.pgz_95_missile', 'RPM': 60, 'Magazine': 4, 'NormalReloadTime': 300,
              'EmptyReloadTime': 300, 'TntEquivalentKg': 0.924})
    for a in m.get('AmmoType', []):
        a.setdefault('Override', {})['TntEquivalentKg'] = 0.924
    d['Weapons']['Missile'] = m
    os.makedirs(os.path.join(base, 'missile'), exist_ok=True)
    for name in os.listdir(os.path.join(base, 'missile')):      # the Tunguska's 9M311 profiles fit_existing copied
        os.remove(os.path.join(base, 'missile', name))
    sp = load(os.path.join(vehgen.DATA, 'sbw', 'projectile_profiles', 'gaz_3937_vodnik_aa', 'vodnik_aa_ir_proxy.json'))
    sp['Combat'].update({'WeaponId': f'{ns}:{VID}/missile', 'RoundId': f'{ns}:qw2_ir', 'CaliberMm': 72, 'DiameterMm': 72})
    with open(os.path.join(base, 'missile', 'qw2_ir.json'), 'w') as fh:
        fh.write(json.dumps(sp, indent=2) + '\n')
    tracer_nations.fix(d, True, [])
    with open(paths['data'], 'w') as fh:
        fh.write(json.dumps(d, indent=2) + '\n')
    for pf in os.listdir(folder):
        pth = os.path.join(folder, pf)
        pr = json.load(open(pth))
        tracer_nations.fix(pr, True, [])
        with open(pth, 'w') as fh:
            fh.write(json.dumps(pr, indent=2) + '\n')
    if notes:
        print('belts:', notes)

    # exhaust: the engine is at the front; the outlet is the louvred roof pad of the mid deck (right of centre)
    c = load(paths['client'])
    c.pop('FittedGroundRig', None)
    # EngineExhaust is in the MODEL frame: final px / 16, +Z = rear (the renderer negates Z); not the attachment frame
    c['EngineExhaust']['Origins'] = [{'Id': 'pgz95_exhaust_000', 'Position': [round(v / 16, 5) for v in px(model, EXHAUST)]}]
    with open(paths['client'], 'w') as fh:
        fh.write(json.dumps(c, indent=2) + '\n')
    return [round(float(v), 5) for v in eye]


def write_textures(model, geo):
    """Entity texture (the model's own PNG plus the Marder's track-link columns), wreck texture (x0.178)."""
    assets = vehgen.ASSETS
    own = Image.open(__import__('io').BytesIO(model.src.png)).convert('RGBA')
    assert own.width == 1024 and own.height == 1024, own.size
    tpl = Image.open(os.path.join(assets, 'textures', 'entity', f'{GEO_TEMPLATE}.png')).convert('RGBA')
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
    tpl = load(fit_existing.files_of(GEO_TEMPLATE)['geo'])
    geo = build_geo(model, tpl)
    if '--dump' in argv:                       # the fitted geometry only, for a look in Blender
        with open(argv[argv.index('--dump') + 1], 'w') as fh:
            json.dump(geo, fh, separators=(',', ':'))
        return 0
    spec = make_spec(model)
    spec['lang'] = LANG
    fit_specs.SPECS[VID] = spec
    files = fit_existing.files_of(GEO_TEMPLATE)
    client = json.loads(json.dumps(load(files['client'])).replace(GEO_TEMPLATE, VID))
    client.pop('FittedGroundRig', None)
    for side in client['RunningGear']['TrackRender']['Sides']:
        side['XCenter'] = TRACK_X[side['Side']]
        side['RoadCenters'] = [round(float(model.geo((x, 0, 0))[2]), 5) for x in model.road_x]
    orig = {'geo': geo, 'wreck': load(files['wreck']), 'client': client, 'data': {}, 'armor': {}}
    fit_existing.originals = lambda vid, w: orig
    rc = fit_existing.main([VID] + (['--write'] if write else []))
    if write and rc == 0:
        sys.path.insert(0, os.path.join(HERE, '..', 'track_path'))
        import belt                                   # the link belt round this model's wheels (tools/track_path)
        _, msg = belt.rebuild(VID, write=True)
        print('belt:', msg)
        P.fit_fallback_tracks(fit_existing.files_of(VID)['geo'], tpl)
        eye = finish(model, spec)
        write_textures(model, geo)
        print('driver_camera (data frame):', eye)
    return rc


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
