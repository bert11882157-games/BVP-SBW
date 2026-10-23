#!/usr/bin/env python3
"""ZBD-05 (zbd_05): the supplied ZBD05 Elite.mtb fitted as a tracked, amphibious IFV on the pack's BMP-3M rig.

    python3 tools/vehgen/zbd05_build.py [--write] [--sources DIR]
    (then obb_armor_cover.py zbd_05, zbd05_icon.py, icon_flags/flags.py --write zbd_05, exhaust_check.py zbd_05)

Built like the T-64BV (t64bv_build.py) and the M1A1 TUSK (tusk_build.py) as a NEW vehicle on a pack rig:

  1. geometry (this file): toolbox parts -> the BMP-3M's bone hierarchy (6 road wheels, front sprocket, rear idler, its
     39 link bones kept; tools/track_path/belt.py re-fits the belt round the new wheels afterwards), plus two pitch
     bones for the HJ-73 launchers on the turret flanks (FittedGroundRig, like the pack's ZBD-09);
  2. systems (tools/vehgen/fit_existing.py, spec injected below): seats, station, OBBs, armour layout and handling from
     the bmp3m_elite template (tracked IFV, Type APC = the pack's amphibious class), weapons taken from the pack's
     zbd_09 (30 mm ZPT-99 with its WT belts, Type 86 coax, 2 x HJ-73 launcher); then War Thunder data on top
     (ZBD-04A, the nearest unit WT has: the ZBD-05 itself is not in WT).

Source (nose +x, left +z, 1024 x 1024 texture): 0 hull, 2 the amphibious trim vane and the stern vane in their STOWED
pose (flat on the glacis / up the stern), 1 the same vanes DEPLOYED with struts (dropped: land mode), 3 turret, 4 the
gun group (centre: 30 mm ZPT-99 and its shroud; z +-17: the two HJ-73 launchers), 9 / 10 left / right running gear
(six road wheels at x -67..29, sprocket x 46, idler x -80), 14 and 17 stray helper elements inside the hull (dropped).
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
VID = 'zbd_05'
TEMPLATE = 'bmp3m_elite'
SCALE = 0.82             # px per toolbox unit: real size (3.1 m over the hull, 0.0487 m/unit) at the pack's 16.8 px/m
WHEEL_BOTTOM = 1.7       # px: the BMP-3M's road wheels sit 1.7 px over the ground plane (track plate under them)
SOURCE = 'ZBD05 Elite.mtb'
TEX_H = 1152             # the model's 1024 rows plus the 128 the BMP-3M's track-link bones sample (x 0-1023, y 1024-1151)
TPL_TRACK_X = 22.16768   # the template's link plane (x of the track bones)
ROAD_X = [-67.0, -42.0, -24.0, -4.0, 13.0, 29.0]   # road wheel axles (toolbox x, rear to front)
IDLER_X, SPROCKET_X = -80.0, 46.0

# toolbox points (measured on the model)
GUN_PIVOT = (-21.5, -36.5, 0.0)
POD_PIVOT_L = (-43.5, -36.5, 17.0)
POD_PIVOT_R = (-43.5, -36.5, -17.0)
POINTS = {}   # filled by make_points()


def load(path):
    return vehgen.load(path)


class ShiftFrame(mtbgeo.Frame):
    """toolbox -> geo with the model's origin moved: the road wheel midpoint sits at geo z = 0 (centre of the track
    contact patch, where a tracked vehicle turns)."""

    def __init__(self, scale, y_offset, z_shift):
        super().__init__(scale, y_offset)
        self.z_shift = z_shift

    def geo(self, p):
        return super().geo(p) + np.array([0.0, 0.0, self.z_shift])


def elements_of(src, part):
    return src.parts[part]


class Model:
    def __init__(self, sources):
        self.src = mtbgeo.Source(os.path.join(sources, SOURCE))
        ground = self.src.corners([9, 10])[:, 1].max()
        self.ground = ground
        mid = (ROAD_X[0] + ROAD_X[-1]) / 2
        self.frame = ShiftFrame(SCALE, ground * SCALE + WHEEL_BOTTOM, mid * SCALE)

    def geo(self, p):
        return self.frame.geo(p)

    def els(self, part, pick=None):
        return [f for i, f in enumerate(self.src.parts[part]) if pick is None or pick(i, f)]

    def mesh(self, elements):
        if not elements:
            return {'normalized_uvs': True, 'positions': [], 'normals': [], 'uvs': [], 'polys': []}
        return mtbgeo.mesh(elements, self.frame, self.src.tw, TEX_H)

    def box(self, elements):
        c = np.array([self.geo(p) for f in elements for p in mtb.box_corners(f)])
        return c.min(0), c.max(0)

    def hull_elements(self):
        return self.els(0) + self.els(2)

    def turret_elements(self):
        return self.els(3)

    def gun_groups(self):
        """(centre gun group, left launcher, right launcher) of part 4 by rotation point."""
        c, l, r = [], [], []
        for f in self.src.parts[4]:
            z = mtb.num(f[8])
            (l if z > 5 else r if z < -5 else c).append(f)
        return c, l, r


def centre_of(f):
    return np.mean(mtb.box_corners(f), axis=0)


def side_groups(model):
    """Running gear elements by side and axle: {(side, key): [elements]}; key = road wheel index 0-5, 'idler', 'sprocket'.
    The right side's wheels are built from several rotation points (z -34.5 .. -27.5) and one right-hand element sits
    in the left part, so side comes from the element's position and axle from its rotation x."""
    out = {}
    for part in (9, 10):
        for f in model.src.parts[part]:
            side = 'L' if centre_of(f)[2] > 0 else 'R'
            x = mtb.num(f[6])
            if abs(x - IDLER_X) < 0.6:
                key = 'idler'
            elif abs(x - SPROCKET_X) < 0.6:
                key = 'sprocket'
            else:
                key = int(np.argmin([abs(x - a) for a in ROAD_X]))
            out.setdefault((side, key), []).append(f)
    return out


def pivot_of_els(model, elements):
    """Wheel axle: centre of the elements' x and y extent (the right side's rotation points are not on the axle)."""
    c = np.array([p for f in elements for p in mtb.box_corners(f)])
    lo, hi = c.min(0), c.max(0)
    cz = (lo[2] + hi[2]) / 2
    return [round(float(v), 5) for v in model.geo(((lo[0] + hi[0]) / 2, (lo[1] + hi[1]) / 2, cz))]


def pivot_of(model, elements):
    rp = mtbgeo.Source.rotation_point(elements)
    return [round(float(v), 5) for v in model.geo(rp)]


def shifted(geo_bone, dx):
    pm = geo_bone.get('poly_mesh')
    if pm:
        for p in pm['positions']:
            p[0] = round(p[0] + dx, 5)
    for c in geo_bone.get('cubes', []):
        c['origin'][0] = round(c['origin'][0] + dx, 5)
    geo_bone['pivot'][0] = round(geo_bone['pivot'][0] + dx, 5)


TRACK_X = {}


def build_geo(model, tpl):
    """The fitted model in final px: template bones and hierarchy, this model's meshes."""
    g = copy.deepcopy(tpl)
    geo = g['minecraft:geometry'][0]
    geo['description']['identifier'] = f'geometry.{VID}'
    geo['description']['texture_width'] = model.src.tw
    geo['description']['texture_height'] = TEX_H
    bones = {b['name']: b for b in geo['bones']}
    keep_mesh = ('trackRot', 'trackMov', 'brokenTrack', 'crudeTrack', 'TrackL', 'TrackR')
    for b in geo['bones']:
        if not b['name'].startswith(keep_mesh):
            b.pop('poly_mesh', None)

    bones['hull']['poly_mesh'] = model.mesh(model.hull_elements())
    tur = model.turret_elements()
    bones['turret']['poly_mesh'] = model.mesh(tur)
    bones['turret']['pivot'] = pivot_of(model, tur)
    centre, left, right = model.gun_groups()
    bones['barell']['poly_mesh'] = model.mesh(centre)
    bones['barell']['pivot'] = [round(float(v), 5) for v in model.geo(GUN_PIVOT)]
    # the two HJ-73 launchers pitch with the gun about their own trunnions (FittedGroundRig pitch bones)
    blist = geo['bones']
    at = max(i for i, b in enumerate(blist) if b['name'] == 'barell')
    for name, els, pv in (('pod_L', left, POD_PIVOT_L), ('pod_R', right, POD_PIVOT_R)):
        blist.insert(at + 1, {'name': name, 'parent': 'turret',
                              'pivot': [round(float(v), 5) for v in model.geo(pv)],
                              'poly_mesh': model.mesh(els)})
        at += 1

    # running gear: six road wheels (rear to front), the rear idler and the front sprocket per side
    groups = side_groups(model)
    for side in ('L', 'R'):
        for i in range(6):
            els = groups[(side, i)]
            b = bones[f'wheel{side}{i}']
            b['poly_mesh'] = model.mesh(els)
            b['pivot'] = pivot_of_els(model, els)
        b = bones[f'wheel{side}Rear']          # rearmost wheel: the idler (x -80)
        b['poly_mesh'] = model.mesh(groups[(side, 'idler')])
        b['pivot'] = pivot_of_els(model, groups[(side, 'idler')])
        b = bones[f'wheel{side}Front']         # frontmost wheel: the drive sprocket (x 46)
        b['poly_mesh'] = model.mesh(groups[(side, 'sprocket')])
        b['pivot'] = pivot_of_els(model, groups[(side, 'sprocket')])

    # the track plane: the template's links sit on its wheel plane (x 22.17); move them to this model's
    for side, sign in (('L', 1), ('R', -1)):
        road0 = bones[f'wheel{side}0']
        P = np.array(road0['poly_mesh']['positions'])
        centre_x = (P[:, 0].min() + P[:, 0].max()) / 2
        dx = centre_x - sign * TPL_TRACK_X
        for name, b in bones.items():
            if name.startswith((f'trackRot{side}', f'trackMov{side}', f'brokenTrack{side}', f'crudeTrack{side}',
                                f'Track{side}')):
                shifted(b, dx)
        bones[f'Wheel{side}']['pivot'][0] = round(sign * abs(road0['pivot'][0]), 5)
        TRACK_X[side] = round(float(centre_x), 5)

    fit_links_to_wheels(bones)

    lo, hi = bounds_of(geo)
    size = hi - lo
    geo['description']['visible_bounds_width'] = round(float(max(size[0], size[2]) / 16 + 1), 2)
    geo['description']['visible_bounds_height'] = round(float(size[1] / 16 + 1), 2)
    geo['description']['visible_bounds_offset'] = [0, round(float((lo[1] + hi[1]) / 32), 2), 0]
    return g


def fit_links_to_wheels(bones):
    """The template's link bones rest on the BMP-3M's belt (z -44..60, y 1.8..18.8 over their pivots); map that extent
    onto this model's wheel extent first, so belt.py sees links that lie on the running gear instead of fitting
    phantom wheels from links that reach past it (the fit is cached in tools/track_path/fitted_wheels.json)."""
    import re as _re
    link = _re.compile(r'(trackMov|trackRot)[LR]\d+')
    pv = np.array([b['pivot'] for n, b in bones.items() if _re.fullmatch(r'trackMovL\d+', n)])
    z0, z1, y0, y1 = pv[:, 2].min(), pv[:, 2].max(), pv[:, 1].min(), pv[:, 1].max()
    wp = np.array([p for n, b in bones.items() if _re.fullmatch(r'wheelL(\d|Front|Rear)', n)
                   for p in b['poly_mesh']['positions']])
    nz0, nz1 = wp[:, 2].min() + 1.5, wp[:, 2].max() - 1.5
    ny0, ny1 = wp[:, 1].min() + 1.0, wp[:, 1].max() + 0.5
    kz, ky = (nz1 - nz0) / (z1 - z0), (ny1 - ny0) / (y1 - y0)
    fz = lambda v: round(float(nz0 + (v - z0) * kz), 5)
    fy = lambda v: round(float(ny0 + (v - y0) * ky), 5)
    for n, b in bones.items():
        if not link.fullmatch(n):
            continue
        b['pivot'] = [b['pivot'][0], fy(b['pivot'][1]), fz(b['pivot'][2])]
        pm = b.get('poly_mesh')
        if pm:
            for p in pm['positions']:
                p[1], p[2] = fy(p[1]), fz(p[2])


def bounds_of(geo):
    pts = [p for b in geo['bones'] if b.get('poly_mesh') for p in b['poly_mesh']['positions']]
    P = np.array(pts)
    return P.min(0), P.max(0)


def px(model, p):
    """Toolbox point -> final model px [x, y, z] (+X left, +Y up, nose -Z)."""
    return [round(float(v), 4) for v in model.geo(p)]


# toolbox points measured on the model (turret frame points are plain toolbox points too: the turret turns about
# toolbox (-34, *, 0), the geo pivot is derived from the same point)
# gunner's sight head: part 3 elements at x -37..-29, y -44..-40, z 5..11.4 (left of the turret roof, ahead of the left
# hatch): the eye is on its front glass, on its axis
GUNNER_EYE = (-28.2, -42.2, 8.5)
# commander: head over his closed hatch ring (right octagonal hatch, x -39.5, z -8.3), behind and above his sight mast
# (part 3 x -33..-30.5, y -46.2, z -9.8..-6.2: an eye at the hatch looks straight into it)
CMD_EYE = (-43.0, -50.0, -8.3)
MAIN_MUZZLE = (22.8, -36.5, 0.0)          # ZPT-99 barrel tip (part 4 elements 53-57)
COAX_MUZZLE = (-14.2, -36.5, 3.2)         # mantlet front face, left of the gun: the model has no coax tube, placed
HJ_MUZZLE_L = (-26.5, -36.9, 17.0)        # nose of the left launcher's missile (part 4, group at z +17)
HJ_MUZZLE_R = (-26.5, -36.9, -17.0)
# engine bay front right: the louvre panel on the right deck edge (part 0 elements 83-88: x 11..33, y -30..-26,
# z -29.5..-19.5) is the exhaust grille; the large lattice grille beside it is the air intake. Origin 0.25 px over the panel.
EXHAUST_PANEL = list(range(83, 89))
SEATS_TB = [('Turret', (-33.5, -10.6, 8.5)),        # gunner (the pack's controller seat), left, under the sight
            ('Turret', (-43.0, -18.4, -8.3)),       # commander, right, under his hatch
            ('Vehicle', (24.3, 0.0, 20.0))]         # driver, front left under the round hatch (passenger seat)
TROOP_X = (-82.0, -73.0, -64.0, -55.0)              # eight troops on two benches in the rear compartment
TROOP_Z = (20.0, -20.0)
ENGINE_TB = ((14.0, -30.0, -30.0), (80.0, -3.0, -1.0))      # front right engine bay (toolbox box)
DISMOUNT = [0, 0.41215, -4.9]                       # behind the closed rear door (hull rear at data z -3.7)


def make_spec(model):
    t = lambda *p: px(model, p)
    hull_els = model.hull_elements()
    hull_lo, hull_hi = model.box(hull_els)
    tur_lo, tur_hi = model.box(model.turret_elements())
    eng = np.array([model.geo(c) for c in [(x, y, z) for x in (ENGINE_TB[0][0], ENGINE_TB[1][0])
                                           for y in (ENGINE_TB[0][1], ENGINE_TB[1][1])
                                           for z in (ENGINE_TB[0][2], ENGINE_TB[1][2])]])
    panel_lo, panel_hi = model.box([model.src.parts[0][i] for i in EXHAUST_PANEL])
    centre = (panel_lo + panel_hi) / 2
    exhaust = [round(float(centre[0]), 4), round(float(panel_hi[1] + 0.25), 4), round(float(centre[2]), 4)]
    PANEL['lo'], PANEL['hi'] = [float(v) for v in panel_lo], [float(v) for v in panel_hi]
    seats = [{'frame': f, 'at': t(*p)} for f, p in SEATS_TB]
    seats[0]['set'] = {'Weapons': ['Cannon', 'MainMachineGun', 'Missile']}
    seats[1]['fromTemplate'] = 0
    for x in TROOP_X:
        for z in TROOP_Z:
            seats.append({'frame': 'Vehicle', 'at': t(x, 0.0, z), 'fromTemplate': 1})
    for s in seats:
        s.setdefault('set', {})['DismountInfo'] = {'Transform': 'Vehicle', 'Position': DISMOUNT}
    return {
        'id': VID,
        'template': TEMPLATE,
        'hitbox': (3.5, 2.9),
        'geometry': {'scale': 1.0},
        # collision: the hull with its stowed vanes, the engine bay (front right, where the model has its grilles)
        # and the turret
        'obb': [
            {'box': [[float(v) for v in hull_lo], [float(v) for v in hull_hi]]},
            {'box': [[float(v) for v in eng.min(0)], [float(v) for v in eng.max(0)]], 'part': 'MainEngine'},
            {'box': [[float(v) for v in tur_lo], [float(v) for v in tur_hi]], 'transform': 'Turret', 'part': 'Turret'},
        ],
        'attachments': {
            'gunner1_camera': {'parent': 'Turret', 'at': t(*GUNNER_EYE)},
            'gunner1_zoom': {'parent': 'Turret', 'at': t(*GUNNER_EYE)},
            'gunner2_camera': {'parent': 'Turret', 'at': t(*CMD_EYE)},
            'main_muzzle': {'parent': 'Barrel', 'at': t(*MAIN_MUZZLE)},
            'coax_muzzle': {'parent': 'Barrel', 'at': t(*COAX_MUZZLE)},
            # the HJ-73 launchers pitch with the gun on their own bones (RotationChannel TURRET_PITCH), like the ZBD-09's
            'fitted_pod_L': {'parent': 'Turret', 'at': t(*POD_PIVOT_L), 'RotationChannel': 'TURRET_PITCH'},
            'fitted_pod_R': {'parent': 'Turret', 'at': t(*POD_PIVOT_R), 'RotationChannel': 'TURRET_PITCH'},
            'missileMuzzle_1': {'parent': 'fitted_pod_L', 'at': t(*HJ_MUZZLE_L)},
            'missileMuzzle_2': {'parent': 'fitted_pod_R', 'at': t(*HJ_MUZZLE_R)},
        },
        'seats': seats,
        'rig': ['pod_L', 'pod_R'],
        'exhaust': [exhaust],
        # weapons: the pack's ZBD-09 set (ZPT-99 with its WT belts, Type 86 coax, HJ-73 launchers); the BMP-3M's
        # 100 mm gun-launcher and 2A72 are dropped (the ZBD-05 has neither)
        'weaponFrom': {'Cannon': ('zbd_09', 'Cannon'), 'MainMachineGun': ('zbd_09', 'MainMachineGun'),
                       'Missile': ('zbd_09', 'Missile')},
        'dropWeapons': ['DualCannon'],
    }


PANEL = {}


# ---------------------------------------------------------------------------- War Thunder data
# WT cn_zbd_04a (datamine gszabi99, 2026-10-23), the nearest unit WT has to the ZBD-05 (30 mm ZPT-99 turret, amphibious
# IFV): VehiclePhys Mass TakeOff 22,900 kg, maxFwdSpeed 75 / maxRevSpeed 10 km/h, 30 mm gunner0 speedYaw 35 /
# speedPitch 35 deg/s, limits yaw +-180, pitch -6 / +60.
WT = {
    'Mass': 22.9,
    'TurretTurnSpeed': [35.0, 35.0],     # [pitch, yaw] deg/s; the data is divided by the 1.15 the getters apply
    'MaxForwardSpeedRate': 0.9,          # the pack's 0.012 per km/h (T-64B: 0.72 = 60 km/h): 75 km/h
    'MaxBackwardSpeedRate': 0.12,        # 10 km/h
}
NATIVE_RATE = 1.15                       # VehicleEntity.turretTurnXSpeed / YSpeed multiply the data by 1.15
ENGINE_SOUND = 'berts_vehicle_pack:tap_engine_t90aenginerun__t90aengine'     # TaP V-92S2 diesel: ~1,000 hp class like the 8V150


def finish(model, spec):
    """War Thunder and model-specific data on top of what fit_existing wrote."""
    for sub in ('ballistics', 'damage'):
        sys.path.insert(0, os.path.join(HERE, '..', sub))
    import tracer_nations
    import balance
    path = fit_existing.files_of(VID)['data']
    d = load(path)
    d['VehicleIcon'] = f'berts_vehicle_pack:textures/vehicle_icon/{VID}.png'
    d['Mass'] = WT['Mass']
    d['TurretTurnSpeed'] = [round(v / NATIVE_RATE, 4) for v in WT['TurretTurnSpeed']]
    # amphibious like the pack's BMP-2 and PT-76 (EngineInfo.Buoyancy 0.052: the engine floats the hull by its submerged
    # height; the BMP-3M template has 0)
    d['EngineInfo']['Buoyancy'] = 0.052
    d['EngineInfo']['MaxForwardSpeedRate'] = WT['MaxForwardSpeedRate']
    d['EngineInfo']['MaxBackwardSpeedRate'] = WT['MaxBackwardSpeedRate']
    d['TurretPitchRange'] = [-6, 60]
    d['TurretYawRange'] = [-180, 180]
    d['CustomSoundProfileId'] = ENGINE_SOUND
    d['ThirdPersonCameraPos'] = [0, 1.3, 3.9]
    d['RotateOffsetHeight'] = 2.8
    t, cls = balance.GROUND[VID]
    d['MaxHealth'] = balance.ground_hp(VID)
    d['DeathChargeKg'] = balance.death_charge_kg(VID)
    d['DamageClass'] = cls
    d['DamageModifiers'] = balance.modifiers(cls)

    # seats: the gunner keeps the template's sight camera; the commander is a second turret seat looking from over his
    # closed hatch, without a weapon
    cmd = d['Seats'][1]
    cmd.pop('Weapons', None)
    cmd.pop('HasThermalImaging', None)
    cmd['CameraPos'].update({'Transform': 'Turret', 'Direction': 'Turret', 'EyeAttachment': 'gunner2_camera',
                             'ZoomEyeAttachment': 'gunner2_camera', 'DirectionAttachment': 'main_muzzle',
                             'ZoomDirectionAttachment': 'main_muzzle'})

    # the HJ-73 launchers' ShootPos: the ZBD-09's Vehicle frame convention (positions = frame origin + attachment, z not
    # negated), for this vehicle's muzzles
    sp = d['Weapons']['Missile']['ShootPos']
    pos = []
    for n in sp['MuzzleAttachments']:
        a = d['Attachments'][n]
        v = np.array(a['Position'], float) + fit_existing.frame_origin(d, a['Parent'])
        pos.append([round(float(x), 5) for x in v])
    sp['Positions'] = pos
    sp['ViewPosition'] = pos[0]

    tracer_nations.fix(d, True, [])                        # Chinese ammunition: green tracers like the pack's Eastern set
    # the weapon-level default profile is the default belt's first round (fit_specs 'defaultFromFirst'): the best
    # armour-piercing round (docs/WT_BELTS.md)
    prof_dir = os.path.join(vehgen.DATA, 'sbw', 'projectile_profiles')
    for wn, w in d['Weapons'].items():
        default = w['Projectile']['Profile'].split(':', 1)[1]
        first = w['AmmoType'][0]['Override']['Projectile']['Profile'].split(':', 1)[1]
        if default != first:
            with open(os.path.join(prof_dir, default + '.json'), 'w') as fh:
                fh.write(json.dumps(load(os.path.join(prof_dir, first + '.json')), indent=2) + '\n')
    # the driver's eye is the gunner's sight (owner's rule for this expansion): reference attachment, data frame
    sight = spec['attachments']['gunner1_camera']['at']
    eye = mtbgeo.geo_to_data(np.array(sight))
    d.setdefault('Attachments', {})['driver_camera'] = {'Parent': 'VehicleCustomPitch',
                                                        'Position': [round(float(v), 5) for v in eye],
                                                        'Direction': [0, 0, 1]}
    with open(path, 'w') as fh:
        fh.write(json.dumps(d, indent=2) + '\n')

    # client: the road wheel centres of this model (z of each axle, ascending), the exhaust is already in place
    cpath = fit_existing.files_of(VID)['client']
    c = load(cpath)
    for side in c['RunningGear']['TrackRender']['Sides']:
        s = side['Side']
        zs = sorted(pivot_z(model, s, i) for i in range(6))
        side['RoadCenters'] = [round(v, 5) for v in zs]
    with open(cpath, 'w') as fh:
        fh.write(json.dumps(c, indent=2) + '\n')

    # armour: the engine moves from the BMP-3M's rear to the front right bay
    apath = fit_existing.files_of(VID)['armor']
    a = load(apath)
    box = np.array([model.geo(p) for p in [(x, y, z) for x in (ENGINE_TB[0][0], ENGINE_TB[1][0])
                                          for y in (ENGINE_TB[0][1], ENGINE_TB[1][1])
                                          for z in (ENGINE_TB[0][2], ENGINE_TB[1][2])]])
    lo, hi = box.min(0), box.max(0)
    centre, half = (lo + hi) / 2, (hi - lo) / 32
    e = a['engines'][0]
    e['center'] = [round(float(-centre[0] / 16), 5), round(float(centre[1] / 16), 5), round(float(centre[2] / 16), 5)]
    e['half_size'] = [round(float(v), 5) for v in half]
    with open(apath, 'w') as fh:
        fh.write(json.dumps(a, indent=2) + '\n')
    return [round(float(v), 5) for v in eye]


WHEEL_PIVOTS = {}


def pivot_z(model, side, i):
    return WHEEL_PIVOTS[(side, i)]


def write_textures(model):
    """Entity texture (the model's own PNG plus the track-link rows of the BMP-3M), wreck texture. The icons are zbd05_icon.py."""
    assets = vehgen.ASSETS
    own = Image.open(io.BytesIO(model.src.png)).convert('RGBA')
    tpl = Image.open(os.path.join(assets, 'textures', 'entity', f'{TEMPLATE}.png')).convert('RGBA')
    img = Image.new('RGBA', (own.width, TEX_H), (0, 0, 0, 0))
    img.paste(own, (0, 0))
    img.paste(tpl.crop((0, own.height, own.width, TEX_H)), (0, own.height))      # the track-link texture
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
    for b in geo['minecraft:geometry'][0]['bones']:
        m = __import__('re').fullmatch(r'wheel([LR])(\d)', b['name'])
        if m:
            WHEEL_PIVOTS[(m.group(1), int(m.group(2)))] = b['pivot'][2]
    if '--dump' in argv:                       # the fitted geometry only, for a look in Blender
        with open(argv[argv.index('--dump') + 1], 'w') as fh:
            json.dump(geo, fh, separators=(',', ':'))
        print('hull', model.box(model.hull_elements()), 'turret', model.box(model.turret_elements()))
        return 0
    spec = make_spec(model)
    fit_specs.SPECS[VID] = spec
    # the data-only "original" fit_existing starts from: this fitted geo and the BMP-3M client/wreck skeletons
    files = fit_existing.files_of(TEMPLATE)
    client = json.loads(json.dumps(load(files['client'])).replace(TEMPLATE, VID))
    for side in client['RunningGear']['TrackRender']['Sides']:
        side['XCenter'] = TRACK_X[side['Side']]
    orig = {'geo': geo, 'wreck': load(files['wreck']), 'client': client, 'data': {}, 'armor': {}}
    fit_existing.originals = lambda vid, w: orig
    # the armour layout maps the template's hull onto this model's hull body (without the stowed vanes)
    own_box = fit_existing.own_box

    def body_box(g, bone):
        if bone == 'hull':
            return model.box(model.els(0))
        return own_box(g, bone)
    fit_existing.own_box = body_box
    rc = fit_existing.main([VID] + (['--write'] if write else []))
    if write and rc == 0:
        sys.path.insert(0, os.path.join(HERE, '..', 'track_path'))
        import belt                                   # the link belt round this model's wheels (tools/track_path)
        _, msg = belt.rebuild(VID, write=True)
        print('belt:', msg)
        eye = finish(model, spec)
        write_textures(model)
        print('driver_camera (data frame):', eye)
        print('exhaust panel box (geo px):', PANEL)
    return rc


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
