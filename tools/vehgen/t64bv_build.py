#!/usr/bin/env python3
"""T-64BV obr. 1985 (t64bv): the supplied T-64BV_Obr._1985.mtb fitted as a tracked T-64.

    python3 tools/vehgen/t64bv_build.py [--write] [--sources DIR]     (then t64bv_icon.py, then icon_flags/flags.py --write t64bv)

Built like the M1A1 TUSK (tusk_build.py) as a new vehicle on the pack's own T-64B (t64b_obr1976):

  1. geometry (this file): toolbox parts -> the T-64B's bone hierarchy (6 road wheels, front idler, rear sprocket,
     its link bones kept; tools/track_path/belt.py re-fits the belt round the new wheels afterwards);
  2. systems (tools/vehgen/fit_existing.py, spec injected below): weapons, seats, station, armour layout and
     handling from the t64b_obr1976 template (WT 125 mm 2A46 rounds 3BM42 / 3OF26 / 3BK18M / 9M112 Kobra, PKT,
     NSVT station), the model's own points; then War Thunder data (ussr_t_64_b_1984, the T-64B whose
     "t_64b_protection_improvement" mod is the Kontakt-1 kit this model carries) on top.

The source is at the same scale and in the same frame as the pack's T-64B model (hull nose to tail and muzzle land on
its values at 0.853 px/unit). Source parts: 0 hull (+ the Kontakt-1 bricks, skirts, rear snorkel tubes, log and
stowage), 3 turret (+ the Kontakt-1 bricks), 4 gun tube and the coax stub, 17 the commander's NSVT-12.7 (cradle, gun)
on its mount, 9 / 10 left / right road wheels (six groups), idler (front, x 67.5) and sprocket (rear, x -52),
13 / 14 the authored static track strips (dropped: the link belt replaces them).
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
VID = 't64bv'
TEMPLATE_GEO_VID = 't64b_obr1976'
SCALE = 0.853            # px per toolbox unit: the pack T-64B's hull length (127.3 px) over the model's 149.2 units
WHEEL_BOTTOM = 1.3       # px: the pack T-64B's road wheels sit 1.3 px over the ground plane (track plate under them)
SOURCE = 'T-64BV_Obr._1985.mtb'
TEX_H = 2176             # the model's 2048 rows plus the 128 the T-64B's track-link bones sample (x 0-2047, y 2048-2175)
TPL_TRACK_X = 25.16739   # the template's link plane (x of the track bones)
NSV_PIVOT = (3.0, -42.5, -12.25)     # the NSVT's trunnion (part 17's rotation point); the mount turns about the same axis

# toolbox points (measured on the model)
# the gunner's sight head (part 3 elements 181-186: glass at x 17-18, y -34.7..-30.5, z 11.6..14.6) is recessed in the
# stepped turret front (slabs reach x 19.5 up to y -37): the eye is on its axis, over the slab top and ahead of it
GUNNER_EYE = (19.6, -38.0, 13.1)
NSV_EYE = (-3.0, -47.5, -12.25)      # commander's head over and behind the NSVT receiver
MAIN_MUZZLE = (140.0, -27.0, 0.0)
COAX_MUZZLE = (34.5, -27.7, -7.8)    # the PKT stub right of the gun (part 4 element 63)
NSV_MUZZLE = (38.0, -43.3, -12.25)
# the model's louvred exhaust grille on the rear plate (part 0 elements 156-159 and 206-215: x -69.2..-63, y -21.8..-16.8,
# z -10.5..14.5; outer face x -69.2): origin at its centre, 0.25 px outside the face
EXHAUST = (-69.45, -19.3, 2.0)
SEATS_TB = [('Turret', (8.0, -22.0, 13.0)),      # gunner / driver (the pack's controller seat), left under the sight
            ('Turret', (0.0, -22.0, -12.25)),    # commander, right under the NSVT cupola
            ('Vehicle', (45.0, -8.0, 0.6))]      # driver's seat in the hull (passenger seat; the controller is seat 0)

# Kontakt-1 bricks as the model carries them: element classes by their corner-box extents (sorted, 1 decimal)
HULL_BRICKS = [(1.4, 2.8, 5.2), (3.0, 3.6, 5.7), (1.4, 2.8, 5.0)]
TURRET_BRICKS = [(3.8, 6.1, 6.9), (3.8, 6.4, 6.7), (4.1, 6.1, 6.9), (4.1, 6.4, 6.7), (4.0, 6.5, 6.7), (2.9, 3.0, 6.2)]


def load(path):
    return vehgen.load(path)


def extents(f):
    return tuple(round(float(v), 1) for v in np.sort(np.ptp(np.array(mtb.box_corners(f)), 0)))


def is_brick(f, classes):
    e = extents(f)
    return any(all(abs(a - b) < 0.06 for a, b in zip(e, c)) for c in classes)


class Model:
    def __init__(self, sources):
        self.src = mtbgeo.Source(os.path.join(sources, SOURCE))
        ground = self.src.corners([9, 10])[:, 1].max()
        self.ground = ground
        self.frame = mtbgeo.Frame(SCALE, ground * SCALE + WHEEL_BOTTOM)

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

    def bricks(self):
        hull = [f for f in self.els(0) if is_brick(f, HULL_BRICKS)]
        tur = [f for f in self.els(3) if is_brick(f, TURRET_BRICKS)]
        return hull, tur

    def body(self, part, classes):
        return [f for f in self.els(part) if not is_brick(f, classes)]


def wheel_groups(model, part):
    """Elements of a track side part, grouped by their rotation point (x, y)."""
    groups = {}
    for f in model.src.parts[part]:
        key = (round(mtb.num(f[6]), 3), round(mtb.num(f[7]), 3))
        groups.setdefault(key, []).append(f)
    return dict(sorted(groups.items()))


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


def nsv_split(model):
    """Part 17 split into the station's two bones: the gun with its cradle (rotation point of part 17: pitch) and the
    mount (the rest, which turns in yaw only)."""
    yaw, pitch = [], []
    for f in model.src.parts[17]:
        (pitch if (f[6], f[7], f[8]) == ('3', '-42,5', '-12,25') else yaw).append(f)
    return yaw, pitch


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

    bones['hull']['poly_mesh'] = model.mesh(model.els(0))
    tur = model.els(3)
    bones['turret']['poly_mesh'] = model.mesh(tur)
    bones['turret']['pivot'] = pivot_of(model, tur)
    gun = model.els(4)
    bones['barell']['poly_mesh'] = model.mesh(gun)
    bones['barell']['pivot'] = pivot_of(model, gun)
    # the commander's NSVT: mount (yaw bone) and gun (pitch bone), both about the trunnion axis
    yaw_els, pitch_els = nsv_split(model)
    bones['passengerWeaponStationYaw']['poly_mesh'] = model.mesh(yaw_els)
    bones['passengerWeaponStationPitch']['poly_mesh'] = model.mesh(pitch_els)
    pt = [round(float(v), 5) for v in model.geo(NSV_PIVOT)]
    for name in ('passengerWeaponStation', 'passengerWeaponStationYaw', 'passengerWeaponStationPitch'):
        bones[name]['pivot'] = list(pt)

    # running gear: six road wheels (rear to front), the front idler and the rear sprocket per side
    for side, part in (('L', 9), ('R', 10)):
        groups = wheel_groups(model, part)
        road = [k for k in groups if abs(k[1] - 2.75) < 0.01]
        others = sorted((k for k in groups if k not in road), key=lambda k: k[0])
        assert len(road) == 6 and len(others) == 2, (side, list(groups))
        for i, k in enumerate(road):
            b = bones[f'wheel{side}{i}']
            b['poly_mesh'] = model.mesh(groups[k])
            b['pivot'] = pivot_of(model, groups[k])
        b = bones[f'wheel{side}Rear']          # sprocket, rear (smallest x)
        b['poly_mesh'] = model.mesh(groups[others[0]])
        b['pivot'] = pivot_of(model, groups[others[0]])
        b = bones[f'wheel{side}Front']         # idler, front
        b['poly_mesh'] = model.mesh(groups[others[1]])
        b['pivot'] = pivot_of(model, groups[others[1]])

    # the track plane: the template's links sit on its wheel plane (x 25.17); move them to this model's
    for side, sign in (('L', 1), ('R', -1)):
        road0 = bones[f'wheel{side}0']
        P = np.array(road0['poly_mesh']['positions'])
        centre = (P[:, 0].min() + P[:, 0].max()) / 2
        dx = centre - sign * TPL_TRACK_X
        for name, b in bones.items():
            if name.startswith((f'trackRot{side}', f'trackMov{side}', f'brokenTrack{side}', f'crudeTrack{side}',
                                f'Track{side}')):
                shifted(b, dx)
        bones[f'Wheel{side}']['pivot'][0] = round(sign * abs(road0['pivot'][0]), 5)
        TRACK_X[side] = round(float(centre), 5)

    lo, hi = bounds_of(geo)
    size = hi - lo
    geo['description']['visible_bounds_width'] = round(float(max(size[0], size[2]) / 16 + 1), 2)
    geo['description']['visible_bounds_height'] = round(float(size[1] / 16 + 1), 2)
    geo['description']['visible_bounds_offset'] = [0, round(float((lo[1] + hi[1]) / 32), 2), 0]
    return g


TRACK_X = {}


def bounds_of(geo):
    pts = [p for b in geo['bones'] if b.get('poly_mesh') for p in b['poly_mesh']['positions']]
    P = np.array(pts)
    return P.min(0), P.max(0)


def px(model, p):
    """Toolbox point -> final model px [x, y, z] (+X left, +Y up, nose -Z)."""
    return [round(float(v), 4) for v in model.geo(p)]


def make_spec(model):
    t = lambda *p: px(model, p)
    hull_lo, hull_hi = model.box(model.els(0))
    tur_lo, tur_hi = model.box(model.els(3))
    return {
        'id': VID,
        'template': 't64b_obr1976',
        'hitbox': (4.07, 2.42),
        'geometry': {'scale': 1.0},
        # collision: the hull with its Kontakt-1 bricks and skirts, the engine deck (the template's MainEngine box:
        # same frame and scale) and the turret with its bricks
        'obb': [
            {'box': [[float(v) for v in hull_lo], [float(v) for v in hull_hi]]},
            {'box': [[-13.6, 5.9, 38.4], [13.6, 21.3, 56.8]], 'part': 'MainEngine'},
            {'box': [[float(v) for v in tur_lo], [float(v) for v in tur_hi]], 'transform': 'Turret', 'part': 'Turret'},
        ],
        'attachments': {
            'gunner1_camera': {'parent': 'Turret', 'at': t(*GUNNER_EYE)},
            'gunner1_zoom': {'parent': 'Turret', 'at': t(*GUNNER_EYE)},
            'gunner2_camera': {'parent': 'WeaponStationBarrel', 'at': t(*NSV_EYE)},
            'main_muzzle': {'parent': 'Barrel', 'at': t(*MAIN_MUZZLE)},
            'coax_muzzle': {'parent': 'Barrel', 'at': t(*COAX_MUZZLE)},
            'hmg_muzzle': {'parent': 'WeaponStationBarrel', 'at': t(*NSV_MUZZLE)},
        },
        'seats': [{'frame': f, 'at': t(*p)} for f, p in SEATS_TB],
        'weaponSet': {'PassengerMachineGun': {'Name': 'weapon.berts_vehicle_pack.t64bv_hmg'}},
        'lang': {'weapon.berts_vehicle_pack.t64bv_hmg': '12.7 mm NSVT'},
    }


# ---------------------------------------------------------------------------- War Thunder data
# WT ussr_t_64_b_1984 (datamine gszabi99, 2026-10-23): 2A46 125 mm autoloader (shotFreq 0.16666 = 6 s), maxFwd 60 / maxRev
# 10 km/h, mass 37,000 kg + 1,100 kg for the "t_64b_protection_improvement" (Kontakt-1) mod, turret speedYaw 24 /
# speedPitch 3.55 deg/s, limits yaw +-180, pitch -6 / +14; NSV 12.7 mm station yaw +-95 / pitch -5 / +70 at 90 deg/s,
# 150-round belt, reloadTime 5 s, 700 rpm; PKT 250-round belt, reloadTime 8 s, 700 rpm.
BELT_ROUNDS = {
    # WT 7.62 mm PKT: ap_i_ball 817.5 m/s, ap_i_t_ball 865 m/s
    'russian_762_ap_i': ('NONE', 817.5, None), 'russian_762_ap_t': ('RED', 865, None),
    # WT 12.7 mm NSVT: ap_i_t_ball BZT-44 865 m/s, he_i_ball MDZ 860 m/s (0.0019 kg PETN x 1.70), apcr_i_ball BS-41 850 m/s
    'russian_127_bzt44_api_t_1': ('RED', 865, None), 'russian_127_bzt44_api_t_2': ('RED', 865, None),
    'russian_127_mdz_iai': ('NONE', 860, 0.00323), 'russian_127_bs41_ap_i_c': ('NONE', 850, None),
}
# WT belts: the PKT fires ap_i_ball with a tracer every fifth round; the NSVT's bullet list is BZT-44, MDZ, BZT-44, BS-41
# (tracer every third). The best armour-piercing belt (BS-41 APCR) is the default (docs/WT_BELTS.md).
COAX_BELTS = [('AP Belt', [('russian_762_ap_i', 4, 'NONE'), ('russian_762_ap_t', 1, 'T')])]
NSV_BELTS = [('AP Belt', [('russian_127_bs41_ap_i_c', 2, 'NONE'), ('russian_127_bzt44_api_t_1', 1, 'T')]),
             ('Universal Belt', [('russian_127_bzt44_api_t_2', 1, 'T'), ('russian_127_mdz_iai', 1, 'NONE'),
                                 ('russian_127_bzt44_api_t_1', 1, 'T'), ('russian_127_bs41_ap_i_c', 1, 'NONE')]),
             ('API-T Belt', [('russian_127_bzt44_api_t_1', 1, 'T')])]
WT = {
    'Mass': 38.1,                      # 37,000 kg + 1,100 kg Kontakt-1 kit
    'TurretTurnSpeed': [3.55, 24.0],   # [pitch, yaw] deg/s; the data is divided by the 1.15 the getters apply (below)
    'StationTurnSpeed': [90.0, 90.0],
    'MaxBackwardSpeedRate': 0.12,      # WT maxRevSpeed 10 against maxFwdSpeed 60 (the template's forward rate 0.72)
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
    path = fit_existing.files_of(VID)['data']
    d = load(path)
    d['VehicleIcon'] = f'berts_vehicle_pack:textures/vehicle_icon/{VID}.png'
    d['Mass'] = WT['Mass']
    d['TurretTurnSpeed'] = [round(v / NATIVE_RATE, 4) for v in WT['TurretTurnSpeed']]
    d['PassengerWeaponStationTurnSpeed'] = [round(v / NATIVE_RATE, 4) for v in WT['StationTurnSpeed']]
    d['EngineInfo']['MaxBackwardSpeedRate'] = WT['MaxBackwardSpeedRate']
    d['TurretPitchRange'] = [-6, 14]
    d['TurretYawRange'] = [-180, 180]
    d['PassengerWeaponStationYawRange'] = [-95, 95]
    d['PassengerWeaponStationPitchRange'] = [-5, 70]
    t, cls = balance.GROUND[VID]
    d['MaxHealth'] = balance.ground_hp(VID)
    d['DeathChargeKg'] = balance.death_charge_kg(VID)
    d['DamageClass'] = cls
    d['DamageModifiers'] = balance.modifiers(cls)
    # WT belts and belt change times
    wt_belts.ROUNDS.update(BELT_ROUNDS)
    notes = []
    for wn, family, belts, mag in (('MachineGun', 'RUSSIAN_COAX_762', COAX_BELTS, 250),
                                   ('PassengerMachineGun', 'RUSSIAN_127', NSV_BELTS, 150)):
        w = d['Weapons'][wn]
        wt_belts.rebuild(VID, wn, w, family, belts, {}, {}, notes)
        w['Magazine'] = mag
    for wn in ('MachineGun', 'PassengerMachineGun'):         # round entries are named for the round, not a belt
        for e in d['Weapons'][wn].get('ProjectileBeltAmmoType', []):
            rid = os.path.basename(e['Override']['Projectile']['Profile'])
            e['Override']['Name'] = {'belt_ammo_00_russian_762_ap_t': 'T',
                                     'belt_ammo_00_russian_127_mdz_iai': 'I'}.get(rid, e['Override']['Name'])
    tracer_nations.fix(d, True, [])                        # Soviet-pattern ammunition: green tracers
    # the weapon-level default profile is the default belt's first round (fit_specs 'defaultFromFirst')
    prof_dir = os.path.join(vehgen.DATA, 'sbw', 'projectile_profiles')
    for wn, w in d['Weapons'].items():
        default = w['Projectile']['Profile'].split(':', 1)[1]
        first = w['AmmoType'][0]['Override']['Projectile']['Profile'].split(':', 1)[1]
        if default != first:
            with open(os.path.join(prof_dir, default + '.json'), 'w') as fh:
                fh.write(json.dumps(load(os.path.join(prof_dir, first + '.json')), indent=2) + '\n')
    # reloads: the pack's wt_reloads rules for this id only (main 6 s autoloader, PKT 8 s, NSVT 5 s)
    lang = load(os.path.join(vehgen.ASSETS, 'lang', 'en_us.json'))
    for wn, v in d['Weapons'].items():
        name = lang.get(v.get('Name', ''), v.get('Name', ''))
        wcls, changes = wt_reloads.plan(VID, wn, v, name, d.get('Type'))
        v.update(changes)
        si = v.setdefault('SoundInfo', {})
        for k, new in wt_reloads.sound_plan(VID, wn, wcls, v).items():
            if new is None:
                si.pop(k, None)
            else:
                si[k] = new
    # the NSVT's 5 s belt change is shorter than any crew reload clip (wt_reloads.NO_CLIP): no clip is played for it
    si = d['Weapons']['PassengerMachineGun'].get('SoundInfo', {})
    for k in ('VehicleReload', 'VehicleReload3p', 'VehicleReloadClipDurationTicks', 'VehicleReloadSoundTime'):
        si.pop(k, None)
    if notes:
        print('belts:', notes)
    # the driver's eye is the gunner's sight (owner's rule for this expansion): reference attachment, data frame
    sight = spec['attachments']['gunner1_camera']['at']
    eye = mtbgeo.geo_to_data(np.array(sight))
    d.setdefault('Attachments', {})['driver_camera'] = {'Parent': 'VehicleCustomPitch',
                                                        'Position': [round(float(v), 5) for v in eye],
                                                        'Direction': [0, 0, 1]}
    with open(path, 'w') as fh:
        fh.write(json.dumps(d, indent=2) + '\n')
    # exhaust: the model's louvred grille on the rear plate. EngineExhaust is in the MODEL frame (model px / 16,
    # +Z = rear: BvpEngineExhaustRenderer negates Z), unlike the muzzle attachments (matrix frame, nose +Z)
    cpath = fit_existing.files_of(VID)['client']
    c = load(cpath)
    c['EngineExhaust']['Origins'][0]['Position'] = [round(v / 16, 5) for v in px(model, EXHAUST)]
    with open(cpath, 'w') as fh:
        fh.write(json.dumps(c, indent=2) + '\n')
    return [round(float(v), 5) for v in eye]


# ---------------------------------------------------------------------------- Kontakt-1 bricks
def euler_xyz(R):
    """(a, b, c) degrees with R = Rz(c) Ry(b) Rx(a): the order ArmorBoxVolume applies (X, then Y, then Z)."""
    b = -math.asin(max(-1.0, min(1.0, R[2, 0])))
    a = math.atan2(R[2, 1], R[2, 2])
    c = math.atan2(R[1, 0], R[0, 0])
    return [round(math.degrees(v), 3) for v in (a, b, c)]


def rot_matrix(deg):
    a, b, c = (math.radians(v) for v in deg)
    rx = np.array([[1, 0, 0], [0, math.cos(a), -math.sin(a)], [0, math.sin(a), math.cos(a)]])
    ry = np.array([[math.cos(b), 0, math.sin(b)], [0, 1, 0], [-math.sin(b), 0, math.cos(b)]])
    rz = np.array([[math.cos(c), -math.sin(c), 0], [math.sin(c), math.cos(c), 0], [0, 0, 1]])
    return rz @ ry @ rx


def brick_box(model, f):
    """One element as an oriented box in the armour profile frame (-x/16, y/16, z/16 of the geo px): centre,
    half size, euler degrees (the corners of a Shapebox are only nearly a box: its principal axes give the box)."""
    P = np.array([model.geo(p) for p in mtb.box_corners(f)])
    A = np.stack([-P[:, 0] / 16, P[:, 1] / 16, P[:, 2] / 16], 1)
    c0 = A.mean(0)
    _, _, vt = np.linalg.svd(A - c0)
    axes = vt                                    # rows: principal axes, longest first
    # local x, y, z as the world axes they are closest to (small euler angles), right-handed
    order, used = [], set()
    cols = [None, None, None]
    for w in range(3):
        best = max((i for i in range(3) if i not in used), key=lambda i: abs(axes[i][w]))
        used.add(best)
        v = axes[best] if axes[best][w] >= 0 else -axes[best]
        cols[w] = v
    R = np.stack(cols, 1)
    if np.linalg.det(R) < 0:
        k = min(range(3), key=lambda i: abs(cols[i][i]))
        cols[k] = -cols[k]
        R = np.stack(cols, 1)
    local = (A - c0) @ R
    lo, hi = local.min(0), local.max(0)
    centre = c0 + R @ ((lo + hi) / 2)
    half = (hi - lo) / 2
    eul = euler_xyz(R)
    assert np.allclose(rot_matrix(eul), R, atol=1e-3), (eul, R)
    return [round(float(v), 5) for v in centre], [round(float(max(v, 0.004)), 5) for v in half], \
        [0.0 if abs(v) < 0.5 else round(v, 2) for v in eul]


def add_era(model):
    """The Kontakt-1 bricks the model carries as geometry, one armour ERA box per brick (hull frame for part 0, turret
    frame for part 3: both in the rest-pose geo coordinates /16)."""
    path = fit_existing.files_of(VID)['armor']
    a = load(path)
    hull, tur = model.bricks()
    tiles, counts = [], {'hull': 0, 'turret': 0}
    for frame, els in (('hull', hull), ('turret', tur)):
        boxes = []
        for f in els:
            centre, half, eul = brick_box(model, f)
            boxes.append((centre, half, eul))
        boxes.sort(key=lambda b: (b[0][0] < 0, b[0][2], b[0][0], b[0][1]))
        for centre, half, eul in boxes:
            tiles.append({'name': f'kontakt1_{frame}_{counts[frame]:03d}', 'center': centre, 'half_size': half,
                          'rotation': eul, 'frame': frame, 'type': 'kontakt1',
                          'kinetic_protection_mm': 25, 'chemical_protection_mm': 400})
            counts[frame] += 1
    a['explosive_reactive_armor'] = tiles
    with open(path, 'w') as fh:
        fh.write(json.dumps(a, indent=2) + '\n')
    return counts


def write_textures(model):
    """Entity texture (the model's own PNG plus the track-link rows), wreck texture (x0.178). The icons are t64bv_icon.py."""
    assets = vehgen.ASSETS
    own = Image.open(io.BytesIO(model.src.png)).convert('RGBA')
    tpl = Image.open(os.path.join(assets, 'textures', 'entity', f'{TEMPLATE_GEO_VID}.png')).convert('RGBA')
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
    tpl = load(fit_existing.files_of(TEMPLATE_GEO_VID)['geo'])
    geo = build_geo(model, tpl)
    if '--dump' in argv:                       # the fitted geometry only, for a look in Blender
        with open(argv[argv.index('--dump') + 1], 'w') as fh:
            json.dump(geo, fh, separators=(',', ':'))
        return 0
    spec = make_spec(model)
    fit_specs.SPECS[VID] = spec
    # the data-only "original" fit_existing starts from: this fitted geo and the T-64B client/wreck skeletons
    files = fit_existing.files_of(TEMPLATE_GEO_VID)
    client = json.loads(json.dumps(load(files['client'])).replace(TEMPLATE_GEO_VID, VID))
    for side in client['RunningGear']['TrackRender']['Sides']:
        side['XCenter'] = TRACK_X[side['Side']]
    orig = {'geo': geo, 'wreck': load(files['wreck']), 'client': client, 'data': {}, 'armor': {}}
    fit_existing.originals = lambda vid, w: orig
    # the armour layout maps the template's hull and turret boxes onto this model's bodies, without the bricks
    own_box = fit_existing.own_box

    def body_box(g, bone):
        if bone == 'hull':
            return model.box(model.body(0, HULL_BRICKS))
        if bone == 'turret':
            return model.box(model.body(3, TURRET_BRICKS))
        return own_box(g, bone)
    fit_existing.own_box = body_box
    rc = fit_existing.main([VID] + (['--write'] if write else []))
    if write and rc == 0:
        sys.path.insert(0, os.path.join(HERE, '..', 'track_path'))
        import belt                                   # the link belt round this model's wheels (tools/track_path)
        _, msg = belt.rebuild(VID, write=True)
        print('belt:', msg)
        eye = finish(model, spec)
        print('Kontakt-1 bricks:', add_era(model))
        write_textures(model)
        print('driver_camera (data frame):', eye)
    return rc


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
