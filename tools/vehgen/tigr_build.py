#!/usr/bin/env python3
"""GAZ-233014 Tigr (gaz_tigr): the supplied "GazTigr.mtb" fitted as the armoured 4x4 patrol vehicle with a roof-mounted 12.7 mm
Kord, on the pack's GAZ-3937 Vodnik wheeled rig (steered front axle).

    python3 tools/vehgen/tigr_build.py [--write]
    (then tigr_icon.py, tools/icon_flags/flags.py --write gaz_tigr, tools/vehgen/exhaust_check.py gaz_tigr)

The source (656 shapebox elements, toolbox frame +x forward, +y down, +z left; 0.054 m per unit: the 22-unit wheel is 1.19 m,
the body 2.4 m wide and 2.4 m tall without the gun) is modelled with the doors twice:

    0   (487)  body, bonnet, bumper bar, the roof panels, the Kord's pedestal post (59, 225: rotation point -5, -37.25, 0), and
               three of the four wheels (rotation points (34.5, -0.5, 15), (-32, -0.5, +-15))
    1   (22)   the doors OPEN (dropped: owner rule, doors closed)
    2   (22)   the doors CLOSED (hull)
    6   (85)   the right front wheel (rotation point (34.5, -0.5, -15))
    17  (40)   the 12.7 mm Kord: receiver, barrel, jacket, muzzle brake, ammunition box, sights (rotation point -5, -41, 0)

Systems: the Vodnik's wheeled data through tools/vehgen/fit_existing.py, then the Tigr: no War Thunder Tigr could be confirmed
(2026-10-23), so real figures (Wikipedia GAZ Tigr: 7.2 t, about 140 km/h theoretical, 125 km/h published for the military
GAZ-233014); no turret; the Kord is the M113A3's hull passenger weapon station (bones passengerWeaponStation / Yaw / Pitch, the
station seat standing on a platform behind the gun), and its weapon, the War Thunder belts of the pack's Kord (kord_tripod),
imported by tools/vehgen/fit_existing.py (weaponFrom).
"""
import copy
import json
import os
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import ee2_ground as E  # noqa: E402
import mtbgeo  # noqa: E402
import vehgen  # noqa: E402
import fit_existing  # noqa: E402
import fit_specs  # noqa: E402

VID = 'gaz_tigr'
TEMPLATE = 'gaz_3937_vodnik_aa'
AFTER = 'scud_b'
SOURCE = 'GazTigr.mtb'
SCALE = 0.054 * 17.6                # 0.054 m per unit x 17.6 px per m (the pack's 1.1 x real)
GROUND_Y = 10.5                     # toolbox y of the tyre bottoms (wheel centres y -0.5, radius 11)

YAW_PIVOT = (-5.0, -37.25, 0.0)     # the pedestal post's rotation point
PITCH_PIVOT = (-5.0, -41.0, 0.0)    # the Kord's rotation point
MUZZLE = (20.4, -41.0, 0.0)         # just ahead of the muzzle brake (element 628: x 18 .. 20.2)
SIGHT_EYE = (-17.0, -45.0, 0.0)     # behind the backplate (x -15.4), 2.5 units over the receiver top (-42.5)
GRIP_L = (-13.5, -38.4, 1.4)
GRIP_R = (-13.5, -38.4, -1.4)
STAND = (-12.0, -17.0, 0.0)         # the gunner's feet on a platform: his eye (27.3 units up) comes to the sight eye
EYE_UP = 13.9 / SCALE               # a seated rider's eye is 25.9 px over the feet, the pelvis 12 px
SEATS = {                           # pelvis points of seated riders (toolbox)
    'driver': (6.0, -14.5, 10.0), 'commander': (6.0, -14.5, -10.0),
    'rear_l1': (-16.0, -14.5, 12.0), 'rear_r1': (-16.0, -14.5, -12.0),
    'rear_l2': (-32.0, -14.5, 12.0), 'rear_r2': (-32.0, -14.5, -12.0),
}
ENGINE_TB = ((20.0, -20.0, -17.0), (52.0, 4.0, 17.0))
# exhaust: the model has no pipe. The tail pipe leaves under the rear left corner: the bottom edge of the rear left plate (element 82:
# x -45.6 .. -45.4, y -4 .. 1, z 11.5 .. 21.5) (decision, the real Tigr's outlet side is UNVERIFIED); (x0, x1, y, z0, z1)
EXHAUST_TB = (-45.6, -45.4, 1.0, 13.0, 19.0)

YAW_IDS = [59, 225]
WHEEL_KEYS = {('L', 0): (34.0, 34.5, 1), ('R', 0): (34.0, 34.5, -1), ('L', 1): (-32.0, -32.0, 1), ('R', 1): (-32.0, -32.0, -1)}


class Model(E.Model):
    def __init__(self):
        super().__init__(SOURCE, SCALE, GROUND_Y, 0.0)
        self.wheels = {k: [] for k in WHEEL_KEYS}
        for i, f in enumerate(self.E):
            rx, ry, rz = E.mtb.num(f[6]), E.mtb.num(f[7]), E.mtb.num(f[8])
            for (side, axle), (x0, x1, sgn) in WHEEL_KEYS.items():
                if x0 - 1e-6 <= rx <= x1 + 1e-6 and abs(rz) in (14.0, 15.0, 16.0) and rz * sgn > 0 and ry in (-0.5, -1.0):
                    self.wheels[(side, axle)].append(i)
        for k, ids in self.wheels.items():
            assert len(ids) == 90, (k, len(ids))
        taken = {i for ids in self.wheels.values() for i in ids}
        self.hull = [i for i in self.part(0) + self.part(2) if i not in taken and i not in YAW_IDS]
        self.yaw = list(YAW_IDS)
        self.pitch = self.part(17)
        assert len(self.hull) + len(taken) + len(YAW_IDS) + len(self.pitch) + len(self.part(1)) == len(self.E), 'unassigned'


def build_geo(model, tpl):
    g = copy.deepcopy(tpl)
    geo = g['minecraft:geometry'][0]
    geo['description']['identifier'] = f'geometry.{VID}'
    geo['description']['texture_width'] = model.tex_w
    geo['description']['texture_height'] = model.tex_h
    for b in geo['bones']:
        b.pop('poly_mesh', None)
        b.pop('cubes', None)
    g_ = model.geo
    yaw_piv, pit_piv = E.r5(g_(YAW_PIVOT)), E.r5(g_(PITCH_PIVOT))
    i = [k for k, b in enumerate(geo['bones']) if b['name'] == 'barell'][0]
    geo['bones'][i + 1:i + 1] = [
        {'name': 'passengerWeaponStation', 'parent': 'hull', 'pivot': list(yaw_piv)},
        {'name': 'passengerWeaponStationYaw', 'parent': 'passengerWeaponStation', 'pivot': list(yaw_piv),
         'poly_mesh': model.mesh(model.yaw)},
        {'name': 'passengerWeaponStationPitch', 'parent': 'passengerWeaponStationYaw', 'pivot': list(pit_piv),
         'poly_mesh': model.mesh(model.pitch)}]
    bones = {b['name']: b for b in geo['bones']}
    bones['hull']['poly_mesh'] = model.mesh(model.hull)
    bones['hull']['pivot'] = [0, round(float(g_((0, 0, 0))[1]), 5), 0]
    for n in ('turret', 'barell'):                       # no turret: the empty bones stay at the origin
        bones[n]['pivot'] = [0, 0, 0]
    for (side, k), ids in model.wheels.items():
        b = bones[f'wheel{side}{k}']
        b['poly_mesh'] = model.mesh(ids)
        P = np.array(b['poly_mesh']['positions'])
        b['pivot'] = E.r5((P.min(0) + P.max(0)) / 2)
        if k == 0:
            bones[f'steer_wheel{side}0']['pivot'] = list(b['pivot'])
    E.set_bounds(geo)
    return g


def eye_of(p):
    return (p[0], p[1] - EYE_UP, p[2])


def make_spec(model, geo):
    t = model.px
    f = lambda a: [float(v) for v in a]
    hull_lo, hull_hi = model.box(model.hull)
    eng = [np.array(model.geo(p)) for p in ENGINE_TB]
    yaw_lo, yaw_hi = model.box(model.yaw)
    gun_lo, gun_hi = model.box(model.pitch)
    bones = {b['name']: b for b in geo['minecraft:geometry'][0]['bones']}
    wheel_boxes = {}
    for side in 'LR':
        P = np.vstack([np.array(bones[f'wheel{side}{k}']['poly_mesh']['positions']) for k in range(2)])
        wheel_boxes[side] = (P.min(0), P.max(0))
    seats = [{'frame': 'Vehicle', 'at': [round(v, 4) for v in (np.array(t(SEATS['driver'])) + [0, -12.0, 0])],
              'fromTemplate': 1},
             {'frame': 'WeaponStation', 'at': t(STAND), 'fromTemplate': 1}]
    for name in ('commander', 'rear_l1', 'rear_r1', 'rear_l2', 'rear_r2'):
        seats.append({'frame': 'Vehicle', 'at': [round(v, 4) for v in (np.array(t(SEATS[name])) + [0, -12.0, 0])],
                      'fromTemplate': 1})
    return {
        'id': VID, 'template': TEMPLATE, 'hitbox': (2.7, 2.6), 'geometry': {'scale': 1.0},
        'obb': [
            {'box': [f(hull_lo), f(hull_hi)]},
            {'box': [f(np.minimum(*eng)), f(np.maximum(*eng))], 'part': 'MainEngine'},
            {'box': [f(wheel_boxes['L'][0]), f(wheel_boxes['L'][1])], 'part': 'WheelLeft'},
            {'box': [f(wheel_boxes['R'][0]), f(wheel_boxes['R'][1])], 'part': 'WheelRight'},
            {'box': [f(yaw_lo), f(yaw_hi)], 'transform': 'WeaponStation'},
            {'box': [f(gun_lo), f(gun_hi)], 'transform': 'WeaponStationBarrel'},
        ],
        'attachments': {
            'gunner2_camera': {'parent': 'WeaponStationBarrel', 'at': t(SIGHT_EYE)},
            'hmg_muzzle': {'parent': 'WeaponStationBarrel', 'at': t(MUZZLE)},
            'passenger_station_left_grip': {'parent': 'WeaponStationBarrel', 'at': t(GRIP_L)},
            'passenger_station_right_grip': {'parent': 'WeaponStationBarrel', 'at': t(GRIP_R)},
        },
        'seats': seats,
        'dropWeapons': ['Missile'],
        'weaponFrom': {'PassengerMachineGun': ('kord_tripod', 'Cannon')},
    }


# --------------------------------------------------------------------------------------------------- data
KMH = 1.0 / 85.0                     # the wheeled rigs' MaxForwardSpeedRate per km/h (Vodnik 1.4118 = 120)
REAL = {
    'Mass': 7.2,                     # Wikipedia GAZ Tigr: family mass 7,200 kg
    'MaxForwardSpeedRate': round(125 * KMH, 4),      # 125 km/h (military GAZ-233014; 140 km/h is the family top speed)
    'StationTurnSpeed': [90.0, 90.0],  # the owner-approved WT machine-gun reference, as the M113A3's station
    'StationYaw': [-360, 360],       # roof ring mount
    'StationPitch': [-10, 60],
}
NATIVE_RATE = E.NATIVE_RATE
LANG = {'entity.berts_vehicle_pack.gaz_tigr': 'GAZ-233014 Tigr',
        'weapon.berts_vehicle_pack.gaz_tigr_kord': '12.7 mm Kord'}


def finish(model, spec, geo):
    sys.path.insert(0, os.path.join(HERE, '..', 'damage'))
    import balance
    import driver_cameras
    paths = fit_existing.files_of(VID)
    d = E.load(paths['data'])
    d['VehicleIcon'] = f'berts_vehicle_pack:textures/vehicle_icon/{VID}.png'
    d['Mass'] = REAL['Mass']
    d['Type'] = 'APC'
    d['EngineInfo']['MaxForwardSpeedRate'] = REAL['MaxForwardSpeedRate']
    d.pop('EngineSound', None)                           # the vehicle_audio profile (Lizardpyle Tigr) is the engine sound
    for k in ('TurretPos', 'BarrelPos', 'TurretControllerIndex', 'TurretTurnSpeed', 'TurretPitchRange'):
        d.pop(k, None)
    t, cls = balance.GROUND[VID]
    d['MaxHealth'] = balance.ground_hp(VID)
    d['DeathChargeKg'] = balance.death_charge_kg(VID)
    d['DamageClass'] = cls
    d['DamageModifiers'] = balance.modifiers(cls)
    d['ThirdPersonCameraPos'] = [0, 2.6, 7.0]

    # station data: the Kord on the roof (the M113A3 / Toyota technical precedent, Parent HULL)
    d['PassengerWeaponStationControllerIndex'] = 1
    d['PassengerWeaponStationBinding'] = {'Schema': 'berts_vehicle_pack:passenger_bed_weapon_station/v1',
                                          'Parent': 'HULL', 'WeaponId': 'PassengerMachineGun',
                                          'WeaponKind': 'HEAVY_MACHINE_GUN', 'CameraAttachment': 'gunner2_camera',
                                          'MuzzleAttachment': 'hmg_muzzle'}
    d['PassengerWeaponStationTurnSpeed'] = [round(v / NATIVE_RATE, 4) for v in REAL['StationTurnSpeed']]
    d['PassengerWeaponStationYawRange'] = REAL['StationYaw']
    d['PassengerWeaponStationPitchRange'] = REAL['StationPitch']

    # seats: 0 driver (front left), 1 gunner at the Kord, 2 commander (front right), 3-6 the rear benches
    hull_lo, hull_hi = model.box(model.hull)
    toy = E.load(os.path.join(vehgen.DATA, 'sbw', 'vehicles', 'toyota_jihad_dshk.json'))
    station_seat = copy.deepcopy(toy['Seats'][2])
    order = ['driver', 'gunner', 'commander', 'rear_l1', 'rear_r1', 'rear_l2', 'rear_r2']
    for i, seat in enumerate(d['Seats']):
        pos = seat['Position']
        name = order[i]
        if name == 'gunner':
            keep = copy.deepcopy(station_seat)
            keep['Transform'] = 'WeaponStation'
            keep['BodyAttachment'] = 'WeaponStation'
            keep['Position'] = pos
            keep['MinPitch'], keep['MaxPitch'] = REAL['StationPitch']
            keep['Weapons'] = ['PassengerMachineGun']
            keep['CameraPos'].update({'EyeAttachment': 'gunner2_camera', 'ZoomEyeAttachment': 'gunner2_camera',
                                      'DirectionAttachment': 'hmg_muzzle', 'ZoomDirectionAttachment': 'hmg_muzzle'})
            keep['OperatorPose'] = {'LeftHandAttachment': 'passenger_station_left_grip',
                                    'RightHandAttachment': 'passenger_station_right_grip',
                                    'MaxForwardLeanDegrees': 60, 'MaxBackwardLeanDegrees': 15}
            keep['MinYaw'], keep['MaxYaw'] = -360, 360
            d['Seats'][i] = keep
            continue
        side = 1.0 if SEATS[name][2] > 0 else -1.0
        dz = round(SEATS[name][0] * SCALE / 16, 5)
        dismount = {'Transform': 'Vehicle', 'Position': [round(side * (float(hull_hi[0]) / 16 + 1.0), 5), 0.3, dz]}
        new = {'HidePassenger': True, 'IsEnclosed': True, 'Transform': 'Vehicle', 'Position': pos,
               'BodyAttachment': 'Vehicle', 'Weapons': [], 'CanRotateHead': True, 'DismountInfo': dismount}
        if i == 0:
            new.pop('Weapons')
            new['CameraPos'] = driver_cameras.camera()
        d['Seats'][i] = new
    eye = mtbgeo.geo_to_data(np.array(model.geo(eye_of(SEATS['driver']))))
    d = driver_cameras.apply(d, {'eye': [round(float(v), 5) for v in eye], 'seat': 0})
    d.pop('WeaponPairs', None)

    # weapon: the pack's Kord (War Thunder belts of kord_tripod) on the station
    w = d['Weapons']['PassengerMachineGun']
    shoot = mtbgeo.geo_to_data(np.array(spec['attachments']['hmg_muzzle']['at']))
    gun_origin = fit_existing.frame_origin(d, 'WeaponStationBarrel')
    muzzle = shoot - gun_origin
    z = [round(float(muzzle[0]), 5), round(float(muzzle[1]), 5), round(float(-muzzle[2]), 5)]
    w['ShootPos'] = {'Transform': 'WeaponStationBarrel', 'Positions': [z], 'Directions': ['WeaponStationBarrel'],
                     'ViewPosition': z, 'MuzzleAttachments': ['hmg_muzzle'], 'MuzzleDirectionAttachments': ['hmg_muzzle'],
                     'HudOriginAttachment': 'hmg_muzzle', 'HudDirectionAttachment': 'hmg_muzzle',
                     'ViewAttachment': 'hmg_muzzle', 'ViewDirectionAttachment': 'hmg_muzzle',
                     'SeekAttachment': 'hmg_muzzle', 'SeekDirectionAttachment': 'hmg_muzzle',
                     'EffectAttachments': ['hmg_muzzle'], 'EffectDirectionAttachments': ['hmg_muzzle']}
    w['Name'] = f'weapon.berts_vehicle_pack.{VID}_kord'
    E.dump(paths['data'], d)

    # exhaust (EngineExhaust: model frame px / 16, +Z = rear)
    c = E.load(paths['client'])
    c.pop('FittedGroundRig', None)
    origin_px = None
    if EXHAUST_TB is not None:
        x0, x1, y, z0, z1 = EXHAUST_TB
        p = model.geo(((x0 + x1) / 2, y, (z0 + z1) / 2))
        origin_px = [float(p[0]), float(p[1]) + 0.25, float(p[2])]
        c['EngineExhaust']['Origins'] = [{'Id': 'engine_exhaust_group_origin_000',
                                          'Position': E.r5(np.array(origin_px) / 16)}]
    for wh in c['RunningGear']['Steering']['Wheels']:
        wh['MaxAngleDegrees'] = 30
    E.dump(paths['client'], c)
    va = os.path.join(vehgen.MAIN, 'resources', 'assets', 'berts_vehicle_pack', 'sbw', 'vehicle_audio', VID + '.json')
    E.dump(va, {'extends': 'bvp_audio:shared/tigr__none__hmmwv'})
    return E.r5(eye), origin_px


def exits_box(model):
    if EXHAUST_TB is None:
        return None
    x0, x1, y, z0, z1 = EXHAUST_TB
    a, b = model.geo((x0, y, z0)), model.geo((x1, y, z1))
    lo, hi = np.minimum(a, b), np.maximum(a, b)
    return [round(float(lo[0]), 3), round(float(hi[0]), 3), round(float(a[1]), 3), round(float(a[1]), 3),
            round(float(lo[2]), 3), round(float(hi[2]), 3)]


def main(argv):
    write = '--write' in argv
    model = Model()
    tpl = E.load(fit_existing.files_of(TEMPLATE)['geo'])
    geo = build_geo(model, tpl)
    if '--dump' in argv:
        E.dump(argv[argv.index('--dump') + 1], geo, compact=True)
        return 0
    spec = make_spec(model, geo)
    fit_specs.SPECS[VID] = spec
    files = fit_existing.files_of(TEMPLATE)
    client = json.loads(json.dumps(E.load(files['client'])).replace(TEMPLATE, VID))
    orig = {'geo': geo, 'wreck': E.load(files['wreck']), 'client': client, 'data': {}, 'armor': {}}
    real_own_box = fit_existing.own_box

    def own_box(g, bone):                  # no turret: the empty turret / barell bones map the template's (empty) armour only
        if 'poly_mesh' not in fit_existing.bones_of(g)[bone]:
            return np.zeros(3), np.ones(3)
        return real_own_box(g, bone)
    fit_existing.own_box = own_box
    fit_existing.originals = lambda vid, w: orig
    fit_existing.register = lambda spec, out: None
    rc = fit_existing.main([VID] + (['--write'] if write else []))
    if write and rc == 0:
        E.register(VID, spec['hitbox'], AFTER)
        E.add_lang(LANG, f'entity.berts_vehicle_pack.{AFTER}')
        E.add_containers(VID, AFTER)
        eye, ex = finish(model, spec, geo)
        E.write_textures(VID, model)
        print('driver_camera (data frame):', eye, 'exhaust origin px:', ex and [round(v, 3) for v in ex])
        print('EXITS box (model px):', exits_box(model))
    return rc


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
