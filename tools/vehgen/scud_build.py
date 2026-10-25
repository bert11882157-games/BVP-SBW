#!/usr/bin/env python3
"""Scud-B (scud_b): the supplied "Scud-B.mtb" fitted as the 9K72 Elbrus launcher, a MAZ-543 8x8 TEL carrying one 8K14 (R-17)
ballistic missile, on the pack's GAZ-3937 Vodnik wheeled rig (steered front axles) with two axles added.

    python3 tools/vehgen/scud_build.py [--write]
    (then scud_icon.py, tools/icon_flags/flags.py --write scud_b, tools/vehgen/exhaust_check.py scud_b)

The source (643 shapebox elements, toolbox frame +x forward, +y down, +z left; 0.06 m per unit: the 11.5-unit road wheel
radius is 0.69 m, the 184.5-unit hull 11.1 m, the missile 174 units = 10.4 m) is in its travel state, the missile lying on
the erector between the two cabs and overhanging the front by 73 units (4.4 m as modelled):

    0   (207)  chassis: twin cabs (left z 10 .. 26 with the driver's seat and wheel, right z -26 .. -10), the front grille,
               the rear platform, tanks and boxes, the outrigger jacks
    5 / 6      front pair of wheels left / right (rotation x 56 and 25, z +-17.5)
    7 / 8      rear pair left / right (x -27 and -59)
    17  (100)  the erector: rails, hinge brackets (rotation point about -26.5, -2, 0), the front cradle clamps (474-481, 540-542
               and 577-579 are the fixed hinge pillows and ram mounts = turret; the rest = barrel), and the missile
               (613-642 body and tail fins, 530-536 the warhead's stabilizer sheets) in its own bone ammo_Rocket_1

Systems: the Vodnik's wheeled data through tools/vehgen/fit_existing.py, then the Scud: no War Thunder Scud (checked
2026-10-23), so real figures. The missile is a projectile entity that flies as the same mesh (projectile_mesh_v1).
"""
import copy
import json
import os
import subprocess
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import ee2_ground as E  # noqa: E402
import mtbgeo  # noqa: E402
import vehgen  # noqa: E402
import fit_existing  # noqa: E402
import fit_specs  # noqa: E402

VID = 'scud_b'
TEMPLATE = 'gaz_3937_vodnik_aa'
AFTER = 'm142_himars'
SOURCE = 'Scud-B.mtb'
SCALE = 0.06 * 17.6                 # 0.06 m per unit x 17.6 px per m (the pack's 1.1 x real)
GROUND_Y = 9.0                      # toolbox y of the tyre bottoms (wheel centres y -2.5, radius 11.5)

HINGE = (-26.5, -2.0, 0.0)          # the erector hinge (toolbox)
TURRET_IDS = list(range(474, 482)) + [540, 541, 542, 577, 578, 579]
MISSILE_IDS = list(range(613, 643))  # body and tail fins; the thin sheets 530-536 round the nose stay on the erector (they are
                                     # a rail guard loop, not part of the missile: decision, the brief had them in the missile)
MUZZLE = (172.0, -17.85, 0.0)       # just ahead of the warhead tip (x 170.8)
SIGHT_EYE = (70.0, -40.0, 0.0)      # the launch operator's periscope: between the cab roofs (y -31), fixed to the hull side
SEATS = {'operator': (66.0, -16.5, 19.5), 'driver': (80.0, -16.5, 19.5), 'commander': (80.0, -16.5, -19.5)}   # pelvis
EYE_UP = 13.9 / SCALE               # a seated rider's eye is 25.9 px over the feet, the pelvis 12 px
ENGINE_TB = ((40.0, -12.0, -12.0), (86.0, 2.0, 12.0))
WHEELS = {('L', 0): (5, 56.0), ('R', 0): (6, 56.0), ('L', 1): (5, 25.0), ('R', 1): (6, 25.0),
          ('L', 2): (7, -27.0), ('R', 2): (8, -27.0), ('L', 3): (7, -59.0), ('R', 3): (8, -59.0)}
# exhaust: the model has no pipe or grille. The tallest rear-of-cab fitting on the left (element 70, behind the left cab: x 3.5 .. 6,
# top y -29.5, z 18 .. 24) is taken as the muffler stack (decision, UNVERIFIED against a real MAZ-543); (x0, x1, y top, z0, z1)
EXHAUST_TB = (3.5, 6.0, -29.5, 18.0, 24.0)


class Model(E.Model):
    def __init__(self):
        super().__init__(SOURCE, SCALE, GROUND_Y, 0.0)
        self.hull = self.part(0)
        p17 = self.part(17)
        self.turret = list(TURRET_IDS)
        self.missile = list(MISSILE_IDS)
        self.gun = [i for i in p17 if i not in self.turret and i not in self.missile]
        assert len(self.hull) + len(p17) + sum(len(self.part(p)) for p in (5, 6, 7, 8)) == len(self.E)
        self.wheels = {}
        for key, (part, x) in WHEELS.items():
            self.wheels[key] = [i for i in self.part(part) if abs(E.mtb.num(self.E[i][6]) - x) < 1e-6]
            assert len(self.wheels[key]) == 42, (key, len(self.wheels[key]))


def build_geo(model, tpl):
    g = copy.deepcopy(tpl)
    geo = g['minecraft:geometry'][0]
    geo['description']['identifier'] = f'geometry.{VID}'
    geo['description']['texture_width'] = model.tex_w
    geo['description']['texture_height'] = model.tex_h
    for b in geo['bones']:
        b.pop('poly_mesh', None)
        b.pop('cubes', None)
    names = [b['name'] for b in geo['bones']]
    for side in 'LR':                                  # axles 2 and 3 on the hull, the second axle steers
        for k in (2, 3):
            if f'wheel{side}{k}' not in names:
                geo['bones'].append({'name': f'wheel{side}{k}', 'parent': 'hull', 'pivot': [0, 0, 0]})
        if f'steer_wheel{side}1' not in names:
            geo['bones'].append({'name': f'steer_wheel{side}1', 'parent': 'hull', 'pivot': [0, 0, 0]})
    bones = {b['name']: b for b in geo['bones']}
    for side in 'LR':
        bones[f'wheel{side}1']['parent'] = f'steer_wheel{side}1'
    if 'ammo_Rocket_1' not in bones:
        geo['bones'].append({'name': 'ammo_Rocket_1', 'parent': 'barell', 'pivot': [0, 0, 0]})
        bones['ammo_Rocket_1'] = geo['bones'][-1]
    g_ = model.geo
    bones['hull']['poly_mesh'] = model.mesh(model.hull)
    bones['hull']['pivot'] = [0, round(float(g_((0, 0, 0))[1]), 5), 0]
    bones['turret']['poly_mesh'] = model.mesh(model.turret)
    bones['turret']['pivot'] = E.r5(g_(HINGE))
    bones['barell']['poly_mesh'] = model.mesh(model.gun)
    bones['barell']['pivot'] = E.r5(g_(HINGE))
    bones['ammo_Rocket_1']['poly_mesh'] = model.mesh(model.missile)
    bones['ammo_Rocket_1']['pivot'] = E.r5(g_(HINGE))
    for (side, k), ids in model.wheels.items():
        b = bones[f'wheel{side}{k}']
        b['poly_mesh'] = model.mesh(ids)
        P = np.array(b['poly_mesh']['positions'])
        b['pivot'] = E.r5((P.min(0) + P.max(0)) / 2)
        if k < 2:
            bones[f'steer_wheel{side}{k}']['pivot'] = list(b['pivot'])
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
    gun_lo, gun_hi = model.box(model.gun + model.missile)
    bones = {b['name']: b for b in geo['minecraft:geometry'][0]['bones']}
    wheel_boxes = {}
    for side in 'LR':
        P = np.vstack([np.array(bones[f'wheel{side}{k}']['poly_mesh']['positions']) for k in range(4)])
        wheel_boxes[side] = (P.min(0), P.max(0))
    attachments = {
        'gunnerCamera': {'parent': 'Turret', 'at': t(SIGHT_EYE)},
        'commander_camera': {'parent': 'Vehicle', 'at': t(eye_of(SEATS['commander']))},
        'turretPivot': {'parent': 'Vehicle', 'at': 'pivot:turret'},
        'barrelPivot': {'parent': 'Turret', 'at': 'pivot:barell'},
        'rocketMuzzle_1': {'parent': 'Barrel', 'at': t(MUZZLE)},
    }
    seats = []
    for name in ('operator', 'driver', 'commander'):
        p = t(SEATS[name])
        p[1] = round(p[1] - 12.0, 4)
        seats.append({'frame': 'Vehicle', 'at': p, 'fromTemplate': 1})
    return {
        'id': VID, 'template': TEMPLATE, 'hitbox': (3.6, 3.4), 'geometry': {'scale': 1.0},
        'obb': [
            {'box': [f(hull_lo), f(hull_hi)]},
            {'box': [f(np.minimum(*eng)), f(np.maximum(*eng))], 'part': 'MainEngine'},
            {'box': [f(wheel_boxes['L'][0]), f(wheel_boxes['L'][1])], 'part': 'WheelLeft'},
            {'box': [f(wheel_boxes['R'][0]), f(wheel_boxes['R'][1])], 'part': 'WheelRight'},
            {'box': [f(tur_lo), f(tur_hi)], 'transform': 'Turret', 'part': 'Turret'},
            {'box': [f(gun_lo), f(gun_hi)], 'transform': 'Barrel', 'part': 'Turret'},
        ],
        'attachments': attachments, 'seats': seats, 'dropWeapons': ['Missile'], '_muzzles': ['rocketMuzzle_1'],
    }


# --------------------------------------------------------------------------------------------------- data
KMH = 1.0 / 85.0                     # the wheeled rigs' MaxForwardSpeedRate per km/h (Fox 1.2353 = 105, Vodnik 1.4118 = 120)
REAL = {
    'Mass': 37.4,                    # 9P117M TEL loaded (Wikipedia R-17 Elbrus: MAZ-543 loaded 37,400 kg)
    'MaxForwardSpeedRate': round(60 * KMH, 4),       # 60 km/h (the MAZ-543's published road speed; Wikipedia R-17 says 55)
    'TurretTurnSpeed': [3.0, 1.0],   # [pitch, yaw] deg/s: the erector's 3 deg/s (owner brief), yaw only the +-0.5 deg play
    'TurretPitchRange': [0.0, 88.0],
    'TurretYawRange': [-0.5, 0.5],
}
ROCKET = dict(name='weapon.berts_vehicle_pack.scud_b_missile', magazine=1, rpm=6, reload_ticks=3600,
              tnt=720.0, velocity=8.0, gravity=0.04, life=320, radius=22)
LANG = {'entity.berts_vehicle_pack.scud_b': 'Scud-B (9K72 Elbrus)',
        'weapon.berts_vehicle_pack.scud_b_missile': '8K14 (R-17) ballistic missile'}
FLIGHT = 'scud_b'


def export_flight(model, geo):
    """The missile bone as a projectile_mesh_v1 flight model (nose -Z, body centre at the origin, the vehicle's texture)."""
    bone = next(b for b in geo['minecraft:geometry'][0]['bones'] if b['name'] == 'ammo_Rocket_1')
    pm = json.loads(json.dumps(bone['poly_mesh']))
    P = np.array(pm['positions'])
    c = (P.min(0) + P.max(0)) / 2
    c[0] = 0.0
    pm['positions'] = [E.r5(p - c) for p in P]
    out = {'format_version': '1.12.0', 'minecraft:geometry': [{
        'description': {'identifier': f'geometry.atgm.{FLIGHT}', 'texture_width': model.tex_w, 'texture_height': model.tex_h,
                        'visible_bounds_width': 3, 'visible_bounds_height': 3, 'visible_bounds_offset': [0, 0, 0]},
        'bones': [{'name': 'missile', 'pivot': [0, 0, 0], 'poly_mesh': pm}]}]}
    path = os.path.join(vehgen.ASSETS, 'custom_geo', 'atgm', FLIGHT + '.geo.json')
    E.dump(path, out, compact=True)
    # munition models are wound outward by the loader's convention (MunitionMeshWindingTest): tools/munition_models/fix_winding.py
    subprocess.run([sys.executable, os.path.join(vehgen.REPO, 'tools', 'munition_models', 'fix_winding.py'), path], check=True,
                   stdout=subprocess.DEVNULL)
    return float((P[:, 2].max() - P[:, 2].min()) / 16)


def finish(model, spec, geo):
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
    d['TurretYawRange'] = REAL['TurretYawRange']
    d['TurretControllerIndex'] = 0
    d['EngineInfo']['MaxForwardSpeedRate'] = REAL['MaxForwardSpeedRate']
    d['ThirdPersonCameraPos'] = [0, 3.6, 12.0]
    t, cls = balance.GROUND[VID]
    d['MaxHealth'] = balance.ground_hp(VID)
    d['DeathChargeKg'] = balance.death_charge_kg(VID)
    d['DamageClass'] = cls
    d['DamageModifiers'] = balance.modifiers(cls)
    for k in ('driverCamera', 'gunnerSeat', 'passengerSeat_1'):
        d['Attachments'].pop(k, None)

    # seats: 0 launch operator (controller: raises the erector and fires; the periscope sits between the cab roofs),
    # 1 driver (left cab front, his own eye), 2 commander (right cab front)
    hull_lo, hull_hi = model.box(model.hull)
    first = spec['_muzzles'][0]
    for i, seat in enumerate(d['Seats']):
        dz = round(SEATS[('operator', 'driver', 'commander')[i]][0] * SCALE / 16, 5)
        side = 1.0 if i < 2 else -1.0
        dismount = {'Transform': 'Vehicle', 'Position': [round(side * (float(hull_hi[0]) / 16 + 1.0), 5), 0.3, dz]}
        new = {'HidePassenger': True, 'IsEnclosed': True, 'Transform': 'Vehicle', 'Position': seat['Position'],
               'BodyAttachment': 'Vehicle', 'DismountInfo': dismount}
        if i == 0:
            new.update({'Weapons': ['Rocket'], 'MinPitch': REAL['TurretPitchRange'][0],
                        'MaxPitch': REAL['TurretPitchRange'][1], 'Sensitivity': [0.45, 0.52, 0.8], 'CanRotateHead': False,
                        'CameraPos': {'Transform': 'Turret', 'Direction': 'Barrel', 'UseFixedCameraPos': True,
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

    # weapon: the one 8K14 on the erector; it leaves from the warhead tip along the elevated erector
    names = spec['_muzzles']
    origin = fit_existing.frame_origin(d, 'Barrel')
    positions = [E.r5(origin + np.array(d['Attachments'][n]['Position'])) for n in names]
    shot = A.tap_event('ShipMissile.ogg', 'tap_shipmissile')
    sounds = {'Fire1P': shot, 'Fire3P': shot, 'Fire3PFar': shot, 'Fire3PVeryFar': shot,
              'Fire1PGain': 0.8, 'Fire3PGain': 1.0, 'Fire3PFarGain': 1.0, 'Fire3PVeryFarGain': 1.0, 'Volume': 1.6,
              'VehicleReload3p': 'superbwarfare:missile_reload', 'VehicleReloadSoundTime': 7,
              'Change': 'superbwarfare:into_missile'}
    pid = f'berts_vehicle_pack:{VID}/rocket/default'
    w = E.rocket_weapon(ROCKET['name'], ROCKET['magazine'], ROCKET['rpm'], ROCKET['reload_ticks'],
                        'berts_vehicle_pack:s13_rocket', pid, ROCKET['tnt'], ROCKET['velocity'], ROCKET['gravity'],
                        ROCKET['life'], names, positions, sounds, spread=0.05, radius=ROCKET['radius'],
                        icon='generic_missile' if os.path.exists(os.path.join(
                            vehgen.ASSETS, 'textures', 'overlay', 'vehicle', 'weapon', 'icons', 'generic_missile.png'))
                        else 'generic_small_rocket')
    w['ApDurability'] = 40
    d['Weapons'] = {'Rocket': w}
    prof = E.rocket_profile(f'berts_vehicle_pack:{VID}/rocket', 'berts_vehicle_pack:8k14', 885, 1000, 1000, 442.5,
                            explosion='large', scale=1.0, boost_ticks=100)
    trail = prof['Extensions']['berts_vehicle_pack:projectile_effect_v1']['Trail']
    trail['Phases'][0]['Scale'] = 1.6
    trail['Phases'][1]['Scale'] = 2.4
    trail['WidthBlocks'] = 0.6
    prof['Extensions']['berts_vehicle_pack:projectile_mesh_v1'] = {
        'Model': f'berts_vehicle_pack:custom_geo/atgm/{FLIGHT}.geo.json',
        'Texture': f'berts_vehicle_pack:textures/entity/{VID}.png', 'ForwardYaw': 0, 'SpinDegreesPerTick': 0}
    E.dump(os.path.join(vehgen.DATA, 'sbw', 'projectile_profiles', VID, 'rocket', 'default.json'), prof)

    # engine: Lizardpyle T-72 engine (the existing bvp_audio t72_start / idle / stop), engine only (no tracks, no turret)
    d.pop('EngineSound', None)
    A.profiles(VID, 't72', {'startSeconds': 1.863, 'hasStop': True}, None, None)
    E.dump(paths['data'], d)

    # exhaust and steering
    c = E.load(paths['client'])
    c.pop('FittedGroundRig', None)
    if EXHAUST_TB is not None:
        x0, x1, y, z0, z1 = EXHAUST_TB
        p = model.geo(((x0 + x1) / 2, y, (z0 + z1) / 2))
        origin_px = [float(p[0]), float(p[1]) + 0.25, float(p[2])]
        c['EngineExhaust']['Origins'] = [{'Id': 'engine_exhaust_group_origin_000',
                                          'Position': E.r5(np.array(origin_px) / 16)}]
    else:
        origin_px = None
    rg = c['RunningGear']
    rg['RoadWheelCount'] = 4
    rg['WheelBones'] = {'Left': [f'wheelL{k}' for k in range(4)], 'Right': [f'wheelR{k}' for k in range(4)]}
    first_axle = rg['Steering']['Wheels']
    for wh in first_axle:
        wh['MaxAngleDegrees'] = 30
    second = []
    for wh in first_axle:
        n = copy.deepcopy(wh)
        n['SteeringBone'] = wh['SteeringBone'].replace('0', '1')
        n['WheelBone'] = wh['WheelBone'].replace('0', '1')
        n['MaxAngleDegrees'] = 22
        second.append(n)
    rg['Steering']['Wheels'] = first_axle + second
    E.dump(paths['client'], c)
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
    fit_existing.originals = lambda vid, w: orig
    fit_existing.register = lambda spec, out: None
    rc = fit_existing.main([VID] + (['--write'] if write else []))
    if write and rc == 0:
        E.register(VID, spec['hitbox'], AFTER)
        E.add_lang(LANG, f'entity.berts_vehicle_pack.{AFTER}')
        E.add_containers(VID, AFTER)
        eye, ex = finish(model, spec, geo)
        E.write_textures(VID, model)
        print('missile flight model length (m):', round(export_flight(model, geo), 3))
        print('driver_camera (data frame):', eye, 'exhaust origin px:', ex and [round(v, 3) for v in ex])
        print('EXITS box (model px):', exits_box(model))
    return rc


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
