#!/usr/bin/env python3
"""M142 HIMARS (m142_himars): the supplied "M142 Himars.mtb" fitted as a 6x6 wheeled rocket launcher on the pack's
GAZ-3937 Vodnik wheeled rig (steered front axle) with a third axle added.

    python3 tools/vehgen/himars_build.py [--write]
    (then himars_icon.py, tools/icon_flags/flags.py --write m142_himars, tools/vehgen/exhaust_check.py m142_himars)

The source (856 shapebox elements, toolbox frame +x forward, +y down, +z left; 0.05 m per unit: hull 138 units = the
M142's 7.0 m, 49.6 units = its 2.4 m width) is in its travel state (nothing open, the launcher pod stowed facing forward):

    0  (345)   chassis, FMTV cab (windscreen x 57 .. 65, roof y -39), the exhaust stack behind the cab on the left
               (elements 85-90, top elbow at y -42), bumpers, lights, the bed
    3  (9)     the launcher turntable (rotation point -44, -14, 0) = turret
    4  (118)   the launcher module: boom, cage and the six-round pod (two rows of three tubes, x -73.5 .. -1.5, mouths at the
               front: the pod hinges at its rear, rotation point -67.5, -15, 0, so the front end rises) = barrel
    5 / 6      the front wheels left / right (rotation point 42.5, 0, +-16)
    7 / 8      the rear tandem wheels left / right (two wheels each, x -24 and -46)

Systems: the Vodnik's wheeled data through tools/vehgen/fit_existing.py, then the M142 on top. War Thunder has no M142
(checked 2026-10-23), so real figures (Wikipedia M142, Lockheed Martin): 16.25 t, 85 km/h, crew 3; one six-round pod of
M31 GMLRS unitary (227 mm; GPS/INS guidance is not available to the pack's ground rockets: unguided with a tight spread),
no ATACMS (not in War Thunder: owner's brief), launcher 360 deg, 0 / +60 deg; slew, game reload are decisions.
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

VID = 'm142_himars'
TEMPLATE = 'gaz_3937_vodnik_aa'
AFTER = 'tos_1a'
SOURCE = 'M142 Himars.mtb'
SCALE = 0.88                        # 0.05 m per unit x 17.6 px per m
GROUND_Y = 10.0                     # toolbox y of the tyre bottoms (wheel centres y 0, radius 10)

TURRET_PIVOT = (-44.0, -14.0, 0.0)
GUN_PIVOT = (-67.5, -15.0, 0.0)
MUZZLE_X = 0.0                      # just ahead of the tube ends (x -1.5)
TUBES = [(-31.0, 6.5), (-31.0, -6.5), (-31.0, 0.0), (-24.5, 6.5), (-24.5, -6.5), (-24.5, 0.0)]    # (y, z) firing order
SIGHT_EYE = (-4.0, -46.0, 0.0)      # over the pod's front top, clear of the cab roof (y -39); the gunner lays through the
                                    # FCS, so the camera rides the pod
SEATS = {'gunner': (49.0, -17.2, 0.0), 'driver': (49.0, -17.2, 11.0), 'chief': (49.0, -17.2, -11.0)}   # pelvis, cab
EYE_UP = 13.9 / SCALE               # a seated rider's eye is 25.9 px over the feet, the pelvis 12 px
EXHAUST_TOP = (22.5, 28.0, -42.0, 14.0, 23.0)      # the stack's top elbow (elements 89 / 90): x0, x1, y (top), z0, z1
ENGINE_TB = ((40.0, -24.0, -15.0), (69.0, 0.0, 15.0))
WHEELS = {('L', 0): (5, None), ('R', 0): (6, None), ('L', 1): (7, -24.0), ('R', 1): (8, -24.0),
          ('L', 2): (7, -46.0), ('R', 2): (8, -46.0)}


class Model(E.Model):
    def __init__(self):
        super().__init__(SOURCE, SCALE, GROUND_Y, 0.0)
        self.hull = self.part(0)
        self.turret = self.part(3)
        self.gun = self.part(4)
        self.wheels = {}
        for key, (part, x) in WHEELS.items():
            ids = [i for i in self.part(part) if x is None or abs(E.mtb.num(self.E[i][6]) - x) < 1e-6]
            self.wheels[key] = ids


def build_geo(model, tpl):
    g = copy.deepcopy(tpl)
    geo = g['minecraft:geometry'][0]
    geo['description']['identifier'] = f'geometry.{VID}'
    geo['description']['texture_width'] = model.tex_w
    geo['description']['texture_height'] = model.tex_h
    for b in geo['bones']:
        b.pop('poly_mesh', None)
        b.pop('cubes', None)
    # the third axle: wheelL2 / wheelR2 on the hull, after the template's rear wheels
    names = [b['name'] for b in geo['bones']]
    for side in 'LR':
        if f'wheel{side}2' not in names:
            geo['bones'].append({'name': f'wheel{side}2', 'parent': 'hull', 'pivot': [0, 0, 0]})
    bones = {b['name']: b for b in geo['bones']}
    g_ = model.geo
    bones['hull']['poly_mesh'] = model.mesh(model.hull)
    bones['hull']['pivot'] = [0, round(float(g_((0, 0, 0))[1]), 5), 0]
    bones['turret']['poly_mesh'] = model.mesh(model.turret)
    bones['turret']['pivot'] = E.r5(g_(TURRET_PIVOT))
    bones['barell']['poly_mesh'] = model.mesh(model.gun)
    bones['barell']['pivot'] = E.r5(g_(GUN_PIVOT))
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
    tur_lo, tur_hi = model.box(model.turret)
    gun_lo, gun_hi = model.box(model.gun)
    bones = {b['name']: b for b in geo['minecraft:geometry'][0]['bones']}
    wheel_boxes = {}
    for side in 'LR':
        P = np.vstack([np.array(bones[f'wheel{side}{k}']['poly_mesh']['positions']) for k in range(3)])
        wheel_boxes[side] = (P.min(0), P.max(0))
    attachments = {
        'gunnerCamera': {'parent': 'Barrel', 'at': t(SIGHT_EYE)},
        'commander_camera': {'parent': 'Vehicle', 'at': t(eye_of(SEATS['chief']))},
        'turretPivot': {'parent': 'Vehicle', 'at': 'pivot:turret'},
        'barrelPivot': {'parent': 'Turret', 'at': 'pivot:barell'},
    }
    names = []
    for n, (y, z) in enumerate(TUBES, 1):
        attachments[f'rocketMuzzle_{n}'] = {'parent': 'Barrel', 'at': t((MUZZLE_X, y, z))}
        names.append(f'rocketMuzzle_{n}')
    seats = []
    for name in ('gunner', 'driver', 'chief'):
        p = t(SEATS[name])
        p[1] = round(p[1] - 12.0, 4)
        seats.append({'frame': 'Vehicle', 'at': p, 'fromTemplate': 1})
    return {
        'id': VID, 'template': TEMPLATE, 'hitbox': (3.0, 3.2), 'geometry': {'scale': 1.0},
        'obb': [
            {'box': [f(hull_lo), f(hull_hi)]},
            {'box': [f(np.minimum(*eng)), f(np.maximum(*eng))], 'part': 'MainEngine'},
            {'box': [f(wheel_boxes['L'][0]), f(wheel_boxes['L'][1])], 'part': 'WheelLeft'},
            {'box': [f(wheel_boxes['R'][0]), f(wheel_boxes['R'][1])], 'part': 'WheelRight'},
            {'box': [f(tur_lo), f(tur_hi)], 'transform': 'Turret', 'part': 'Turret'},
            {'box': [f(gun_lo), f(gun_hi)], 'transform': 'Barrel', 'part': 'Turret'},
        ],
        'attachments': attachments, 'seats': seats, 'dropWeapons': ['Missile'], '_muzzles': names,
    }


# --------------------------------------------------------------------------------------------------- data
KMH = 1.0 / 85.0                     # the wheeled rigs' MaxForwardSpeedRate per km/h (Fox 1.2353 = 105, Vodnik 1.4118 = 120)
REAL = {
    'Mass': 16.25,                   # M142 combat weight (Wikipedia M142 HIMARS)
    'MaxForwardSpeedRate': round(85 * KMH, 4),      # 85 km/h
    'TurretTurnSpeed': [6.0, 12.0],  # [pitch, yaw] deg/s, decision; data / 1.15
    'TurretPitchRange': [0.0, 60.0], # launcher maximum elevation 60 deg (1,067 mils)
}
ROCKET = dict(name='weapon.berts_vehicle_pack.m142_himars_gmlrs', magazine=6, rpm=12, reload_ticks=1200,
              tnt=30.0, velocity=12.0, gravity=0.04, life=400, radius=8)
LANG = {'entity.berts_vehicle_pack.m142_himars': 'M142 HIMARS',
        'weapon.berts_vehicle_pack.m142_himars_gmlrs': '227 mm M31 GMLRS unitary'}


def finish(model, spec):
    sys.path.insert(0, os.path.join(HERE, '..', 'damage'))
    import balance
    import driver_cameras
    import ee2_audio as A
    paths = fit_existing.files_of(VID)
    d = E.load(paths['data'])
    d['VehicleIcon'] = f'berts_vehicle_pack:textures/vehicle_icon/{VID}.png'
    d['Mass'] = REAL['Mass']
    d['TurretTurnSpeed'] = [round(v / E.NATIVE_RATE, 4) for v in REAL['TurretTurnSpeed']]
    d['TurretPitchRange'] = REAL['TurretPitchRange']
    d['TurretControllerIndex'] = 0
    d['EngineInfo']['MaxForwardSpeedRate'] = REAL['MaxForwardSpeedRate']
    d['ThirdPersonCameraPos'] = [0, 2.8, 9.0]
    t, cls = balance.GROUND[VID]
    d['MaxHealth'] = balance.ground_hp(VID)
    d['DeathChargeKg'] = balance.death_charge_kg(VID)
    d['DamageClass'] = cls
    d['DamageModifiers'] = balance.modifiers(cls)
    for k in ('driverCamera', 'gunnerSeat', 'passengerSeat_1'):
        d['Attachments'].pop(k, None)

    # seats: 0 gunner (controller: lays and fires the launcher; the camera rides the pod), 1 driver (cab left, his own
    # eye: the M142 has no gunner's optic to share), 2 section chief (cab right); all in the cab
    hull_lo, hull_hi = model.box(model.hull)
    dismount = {'Transform': 'Vehicle', 'Position': [round(float(hull_hi[0]) / 16 + 1.0, 5), 0.3, 2.6]}
    first = spec['_muzzles'][0]
    for i, seat in enumerate(d['Seats']):
        new = {'HidePassenger': True, 'IsEnclosed': True, 'Transform': 'Vehicle', 'Position': seat['Position'],
               'BodyAttachment': 'Vehicle', 'DismountInfo': dict(dismount)}
        if i == 0:
            new.update({'Weapons': ['Rocket'], 'MinPitch': REAL['TurretPitchRange'][0],
                        'MaxPitch': REAL['TurretPitchRange'][1], 'Sensitivity': [0.45, 0.52, 0.8], 'CanRotateHead': False,
                        'CameraPos': {'Transform': 'Barrel', 'Direction': 'Barrel', 'UseFixedCameraPos': True,
                                      'Position': [0, 0, 0], 'ZoomPosition': [0, 0, 0],
                                      'EyeAttachment': 'gunnerCamera', 'ZoomEyeAttachment': 'gunnerCamera',
                                      'DirectionAttachment': first, 'ZoomDirectionAttachment': first,
                                      'CameraMode': 'PLAYER_LOOK_AIM', 'AimCameraMode': 'PLAYER_LOOK_AIM'}})
        elif i == 1:
            new.update({'Weapons': [], 'CanRotateHead': True, 'CameraPos': driver_cameras.camera()})
        else:
            cam = driver_cameras.camera()
            cam.update({'EyeAttachment': 'commander_camera', 'ZoomEyeAttachment': 'commander_camera'})
            new.update({'Weapons': [], 'CanRotateHead': True, 'CameraPos': cam})
        d['Seats'][i] = new
    eye = mtbgeo.geo_to_data(np.array(model.geo(eye_of(SEATS['driver']))))
    d['Attachments']['driver_camera'] = {'Parent': 'VehicleCustomPitch', 'Position': E.r5(eye), 'Direction': [0, 0, 1]}

    # weapon: the six-round M31 GMLRS pod
    names = spec['_muzzles']
    origin = fit_existing.frame_origin(d, 'Barrel')
    positions = [E.r5(origin + np.array(d['Attachments'][n]['Position'])) for n in names]
    shot = A.tap_event('Shoot_MLRS_M142.ogg', 'tap_shoot_mlrs_m142')
    sounds = {'Fire1P': shot, 'Fire3P': shot, 'Fire3PFar': shot, 'Fire3PVeryFar': shot,
              'Fire1PGain': 0.8, 'Fire3PGain': 1.0, 'Fire3PFarGain': 1.0, 'Fire3PVeryFarGain': 1.0, 'Volume': 1.4,
              'VehicleReload3p': 'superbwarfare:missile_reload', 'VehicleReloadSoundTime': 7,
              'Change': 'superbwarfare:into_missile'}
    pid = f'berts_vehicle_pack:{VID}/rocket/default'
    w = E.rocket_weapon(ROCKET['name'], ROCKET['magazine'], ROCKET['rpm'], ROCKET['reload_ticks'],
                        'berts_vehicle_pack:s13_rocket', pid, ROCKET['tnt'], ROCKET['velocity'], ROCKET['gravity'],
                        ROCKET['life'], names, positions, sounds, spread=0.1, radius=ROCKET['radius'])
    w['ApDurability'] = 40
    d['Weapons'] = {'Rocket': w}
    prof = E.rocket_profile(f'berts_vehicle_pack:{VID}/rocket', 'berts_vehicle_pack:m31_gmlrs', 227, 300, 800, 113.5,
                            explosion='large', scale=1.8, boost_ticks=60)
    E.dump(os.path.join(vehgen.DATA, 'sbw', 'projectile_profiles', VID, 'rocket', 'default.json'), prof)

    # engine: Lizardpyle us_trk_logistics (the US logistics truck recording; the M142 is the FMTV 6x6, Caterpillar C7)
    d.pop('EngineSound', None)
    meta = A.engine_set('ustruck', 'land/us_trk_logistics/sounds/start_idle_stop2.wav')
    A.profiles(VID, 'ustruck', meta, None, 'generic')
    E.dump(paths['data'], d)

    # exhaust: the stack's top (EngineExhaust: model frame px / 16, +Z = rear), 0.25 px over its top face
    x0, x1, y, z0, z1 = EXHAUST_TOP
    p = model.geo(((x0 + x1) / 2, y, (z0 + z1) / 2))
    origin_px = [float(p[0]), float(p[1]) + 0.25, float(p[2])]
    c = E.load(paths['client'])
    c.pop('FittedGroundRig', None)
    c['EngineExhaust']['Origins'] = [{'Id': 'engine_exhaust_group_origin_000', 'Position': E.r5(np.array(origin_px) / 16)}]
    rg = c['RunningGear']
    rg['RoadWheelCount'] = 3
    rg['WheelBones'] = {'Left': ['wheelL0', 'wheelL1', 'wheelL2'], 'Right': ['wheelR0', 'wheelR1', 'wheelR2']}
    for wh in rg['Steering']['Wheels']:
        wh['MaxAngleDegrees'] = 30
    E.dump(paths['client'], c)
    return E.r5(eye), origin_px


def exits_box(model):
    x0, x1, y, z0, z1 = EXHAUST_TOP
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
    fit_existing.originals = lambda vid, w: orig
    fit_existing.register = lambda spec, out: None
    rc = fit_existing.main([VID] + (['--write'] if write else []))
    if write and rc == 0:
        E.register(VID, spec['hitbox'], AFTER)
        E.add_lang(LANG, f'entity.berts_vehicle_pack.{AFTER}')
        E.add_containers(VID, AFTER)
        eye, ex = finish(model, spec)
        E.write_textures(VID, model)
        print('driver_camera (data frame):', eye, 'exhaust origin px:', [round(v, 3) for v in ex])
        print('EXITS box (model px):', exits_box(model))
    return rc


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
