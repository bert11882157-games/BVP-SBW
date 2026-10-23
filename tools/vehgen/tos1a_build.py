#!/usr/bin/env python3
"""TOS-1A Solntsepyok (tos_1a): the supplied TOS-1.mtb fitted as a tracked heavy flamethrower system (BM-1 launcher
vehicle) on the pack's T-72A (t72a) running gear and bone rig.

    python3 tools/vehgen/tos1a_build.py [--write]
    (then tos1a_icon.py, tools/icon_flags/flags.py --write tos_1a, tools/vehgen/exhaust_check.py tos_1a)

The source (390 elements, toolbox frame +x forward, +y down, +z left; 0.05 m per unit: the 7.5-unit road wheel radius is
the T-72's 375 mm, the 70-unit hull width its 3.5 m) carries the 24-tube launcher of the TOS-1A (3 rows of 8), so it is
built as the TOS-1A (the TOS-1 "Buratino" has 30 tubes):

    0   (84)   hull: T-72 hull with the driver's hatch, fenders, the left rear fender exhaust grille (element 365, turned
               -10 deg about x, on the fender side at x -48 .. -36), side skirts; no turret, nothing open
    3   (13)   launcher: the turntable plate (377-379, y -30 .. -24 on the roof), trunnion brackets (380-381), the rear
               struts (386-389) = turret; the post and the launcher box (382-385, x -28 .. 42, y -58 .. -35; tube mouths
               on the front face x 42) = the elevating barrel, trunnion (0, -33, 0)
    9 / 10     left / right running gear: six road wheels each (rotation points x -41.5 .. 43.5 every 17), the front idler
               (61.7, -7.5) of each side, both rear sprockets (-56.5, -8.3) in part 10 (split by side here)
    13  (16)   the static track strips (dropped: the T-72A link belt replaces them, refitted by tools/track_path/belt.py)

Systems: the T-72A data and hull armour layout through tools/vehgen/fit_existing.py, then the TOS-1A on top: no War
Thunder unit exists (checked 2026-10-23: datamine tankmodels and the wiki have no TOS-1 / TOS-1A), so real-world figures
(Wikipedia TOS-1, armyrecognition TOS-1A): 45.3 t, V-84 840 hp, 60 km/h, 3 crew, 24 x 220 mm MO.1.01.04M thermobaric
rockets, launcher traverse 360 deg; elevation, laying speed and the game reload are decisions (DECISIONS-for-bert.md).
"""
import copy
import json
import os
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import ee2_ground as E  # noqa: E402  (also installs the Shape / FlexTrapezoid rules)
import mtbgeo  # noqa: E402
import vehgen  # noqa: E402
import fit_existing  # noqa: E402
import fit_specs  # noqa: E402

VID = 'tos_1a'
TEMPLATE = 't72a'
AFTER = 'm1a1_tusk'                 # registry anchor (the last Elite Expansion 2 ground registration)
SOURCE = 'TOS-1.mtb'
SCALE = 0.88                        # px per toolbox unit: 0.05 m x 17.6 px per m (the pack's 1.1 x real)
GROUND_Y = 9.0                      # toolbox y of the road wheel bottoms
WHEEL_BOTTOM = 1.3                  # px: road wheel bottoms over the ground (the link plate under them), as the T-64BV
TEX_H = 1664                        # the model's 1024 rows plus the T-72A's track-link rows (y 1536 .. 1604) in place
TPL_ROWS = (1024, 1664)

HULL = None                         # part 0
TURRET = [377, 378, 379, 380, 381, 386, 387, 388, 389]
GUN = [382, 383, 384, 385]
TURRET_PIVOT = (0.0, -24.0, 0.0)    # the turntable plate's top centre
GUN_PIVOT = (0.0, -33.0, 0.0)       # the launcher's trunnion (rotation point of 382-385, between the brackets 380 / 381)
# tube mouths: the launcher's front face x 42, box y -56 .. -37 (3 rows), z -24.5 .. 24.5 (8 columns)
ROWS = [-56.0 + 19.0 * (k + 0.5) / 3 for k in range(3)]
COLS = [-24.5 + 49.0 * (j + 0.5) / 8 for j in range(8)]
MUZZLE_X = 42.3
# the operator's sight: the TOS-1A fire-control sight looks along the launcher; the eye sits just over the launcher's top
# front edge, left of centre, so the tube block does not fill the view
SIGHT_EYE = (41.0, -59.5, 16.0)
COMMANDER_EYE = (33.0, -28.0, 14.0)  # 4 units over the roof ahead of the turntable, left (his hatch periscopes)
SEATS = {           # pelvis points, toolbox frame (the entity's feet are 12 px under them)
    'operator': (8.0, -14.0, -8.0),      # in the launcher base (turret frame), right
    'driver': (50.0, -12.0, 0.0),        # T-72 driver: centre front under the glacis hatch
    'commander': (8.0, -14.0, 8.0),      # launcher base, left
}
# the T-72 exhaust: the louvred outlet on the left rear fender (element 365: x -48 .. -36, y -21.5 .. -19.2, outer face z 34.97)
EXHAUST_BOX_TB = (-48.0, -36.0, -21.5, -19.2, 34.97)
ENGINE_TB = ((-69.0, -19.0, -21.0), (-30.0, 1.0, 21.0))       # engine compartment (rear hull) for the MainEngine box


class Model(E.Model):
    def __init__(self):
        super().__init__(SOURCE, SCALE, GROUND_Y, WHEEL_BOTTOM, tex_h=TEX_H)
        self.hull = self.part(0)
        groups = {}
        for i in self.part(9) + self.part(10):
            f = self.E[i]
            key = (round(E.mtb.num(f[6]), 2), round(E.mtb.num(f[7]), 2))
            side = 'L' if E.mtb.num(f[8]) > 0 else 'R'
            if key == (-56.5, -8.3):              # both sprockets sit in part 10: split by the element's own side
                c = self.corners([i])
                side = 'L' if c[:, 2].mean() > 0 else 'R'
            groups.setdefault((side, key), []).append(i)
        self.road = {s: [groups[(s, (x, 1.5))] for x in (43.5, 26.5, 9.5, -7.5, -24.5, -41.5)] for s in 'LR'}   # front to rear
        self.idler = {s: groups[(s, (61.7, -7.5))] for s in 'LR'}
        self.sprocket = {s: groups[(s, (-56.5, -8.3))] for s in 'LR'}
        assert all(len(v) == 6 for v in self.road.values())


TRACK_X = {}


def build_geo(model, tpl):
    g = copy.deepcopy(tpl)
    geo = g['minecraft:geometry'][0]
    geo['description']['identifier'] = f'geometry.{VID}'
    geo['description']['texture_width'] = model.tex_w
    geo['description']['texture_height'] = TEX_H
    drop = ('passengerWeaponStation', 'passengerWeaponStationYaw', 'passengerWeaponStationPitch')
    geo['bones'] = [b for b in geo['bones'] if b['name'] not in drop]
    bones = {b['name']: b for b in geo['bones']}
    keep_mesh = ('trackRot', 'trackMov', 'brokenTrack', 'crudeTrack', 'TrackL', 'TrackR')
    for b in geo['bones']:
        if not b['name'].startswith(keep_mesh):
            b.pop('poly_mesh', None)
            b.pop('cubes', None)
    g_ = model.geo
    bones['hull']['poly_mesh'] = model.mesh(model.hull)
    bones['turret']['poly_mesh'] = model.mesh(TURRET)
    bones['turret']['pivot'] = E.r5(g_(TURRET_PIVOT))
    bones['barell']['poly_mesh'] = model.mesh(GUN)
    bones['barell']['pivot'] = E.r5(g_(GUN_PIVOT))

    def pivot(ids):
        rp = mtbgeo.Source.rotation_point(model.els(ids))
        return E.r5(g_(rp))
    for side in 'LR':
        for k, ids in enumerate(model.road[side]):
            b = bones[f'wheel{side}{k}']
            b['poly_mesh'] = model.mesh(ids)
            P = np.array(b['poly_mesh']['positions'])
            b['pivot'] = [round(float((P[:, 0].min() + P[:, 0].max()) / 2), 5)] + pivot(ids)[1:]
        for name, ids in (('Front', model.idler[side]), ('Rear', model.sprocket[side])):
            b = bones[f'wheel{side}{name}']
            b['poly_mesh'] = model.mesh(ids)
            P = np.array(b['poly_mesh']['positions'])
            c = (P.min(0) + P.max(0)) / 2
            b['pivot'] = E.r5(c)
    # the track plane: the template's links sit on its wheel plane; move them onto this model's road wheel centre line
    for side, sign in (('L', 1), ('R', -1)):
        P = np.array(bones[f'wheel{side}0']['poly_mesh']['positions'])
        centre = float((P[:, 0].min() + P[:, 0].max()) / 2)
        ref = bones[f'trackRot{side}0']['pivot'][0]
        dx = centre - ref
        for name, b in bones.items():
            if name.startswith((f'trackRot{side}', f'trackMov{side}', f'brokenTrack{side}', f'crudeTrack{side}',
                                f'Track{side}')):
                E.shift_bone(b, dx=dx)
        bones[f'Wheel{side}']['pivot'][0] = round(centre, 5)
        TRACK_X[side] = round(centre, 5)
    E.set_bounds(geo)
    return g


def make_spec(model):
    t = model.px
    hull_lo, hull_hi = model.box(model.hull)
    eng_lo, eng_hi = [np.array(model.geo(p)) for p in ENGINE_TB]
    eng_lo, eng_hi = np.minimum(eng_lo, eng_hi), np.maximum(eng_lo, eng_hi)
    tur_lo, tur_hi = model.box(TURRET)
    gun_lo, gun_hi = model.box(GUN)
    f = lambda a: [float(v) for v in a]
    attachments = {
        'gunnerCamera': {'parent': 'Barrel', 'at': t(SIGHT_EYE)},
        'commander_camera': {'parent': 'Turret', 'at': t(COMMANDER_EYE)},
        'turretPivot': {'parent': 'Vehicle', 'at': 'pivot:turret'},
        'barrelPivot': {'parent': 'Turret', 'at': 'pivot:barell'},
    }
    names = []
    n = 0
    for y in ROWS:                       # firing order: top row first, alternating sides from the centre out
        for j in (3, 4, 2, 5, 1, 6, 0, 7):
            n += 1
            attachments[f'rocketMuzzle_{n}'] = {'parent': 'Barrel', 'at': t((MUZZLE_X, y, COLS[j]))}
            names.append(f'rocketMuzzle_{n}')
    seats = []
    for name, frame, tpl in (('operator', 'Turret', 0), ('driver', 'Vehicle', 2), ('commander', 'Turret', 2)):
        p = t(SEATS[name])
        p[1] = round(p[1] - 12.0, 4)
        seats.append({'frame': frame, 'at': p, 'fromTemplate': tpl})
    return {
        'id': VID,
        'template': TEMPLATE,
        'hitbox': (4.07, 2.42),
        'geometry': {'scale': 1.0},
        'obb': [
            {'box': [f(hull_lo), f(hull_hi)]},
            {'box': [f(eng_lo), f(eng_hi)], 'part': 'MainEngine'},
            {'box': [f(tur_lo), f(tur_hi)], 'transform': 'Turret', 'part': 'Turret'},
            {'box': [f(gun_lo), f(gun_hi)], 'transform': 'Barrel', 'part': 'Turret'},
        ],
        'attachments': attachments,
        'seats': seats,
        'dropWeapons': ['Cannon', 'MachineGun', 'PassengerMachineGun'],
        '_muzzles': names,
    }


# ------------------------------------------------------------------------------------------------- data
KMH = 0.012                          # the T-72A template's MaxForwardSpeedRate per km/h (0.72 = 60 km/h)
REAL = {
    'Mass': 45.3,                    # TOS-1A combat weight (Wikipedia TOS-1)
    'TurretTurnSpeed': [5.0, 10.0],  # [pitch, yaw] deg/s, decision (no published laying speed); data / 1.15
    'TurretPitchRange': [0.0, 60.0], # decision (no published elevation limits)
    'MaxForwardSpeedRate': 60 * KMH, # 60 km/h road
    'MaxBackwardSpeedRate': 0.048,   # the T-72A's (4 km/h)
}
ROCKET = dict(name='weapon.berts_vehicle_pack.tos_1a_rocket', magazine=24, rpm=120, reload_ticks=1200,
              tnt=70.0, velocity=6.0, gravity=0.06, life=160, radius=10)
LANG = {'entity.berts_vehicle_pack.tos_1a': 'TOS-1A Solntsepyok',
        'weapon.berts_vehicle_pack.tos_1a_rocket': '220 mm MO.1.01.04M thermobaric rockets'}


def finish(model, spec):
    sys.path.insert(0, os.path.join(HERE, '..', 'damage'))
    import balance
    import driver_cameras
    paths = fit_existing.files_of(VID)
    d = E.load(paths['data'])
    for k in ('HasAutoloader', 'Fires', 'WeaponPairs', 'PassengerWeaponStationControllerIndex',
              'PassengerWeaponStationBinding', 'PassengerWeaponStationPos', 'PassengerWeaponStationBarrelPos',
              'PassengerWeaponStationTurnSpeed', 'PassengerWeaponStationPitchRange', 'PassengerWeaponStationYawRange'):
        d.pop(k, None)
    d['VehicleIcon'] = f'berts_vehicle_pack:textures/vehicle_icon/{VID}.png'
    d['Mass'] = REAL['Mass']
    d['TurretTurnSpeed'] = [round(v / E.NATIVE_RATE, 4) for v in REAL['TurretTurnSpeed']]
    d['TurretPitchRange'] = REAL['TurretPitchRange']
    d['TurretControllerIndex'] = 0
    d['EngineInfo']['MaxForwardSpeedRate'] = round(REAL['MaxForwardSpeedRate'], 4)
    d['EngineInfo']['MaxBackwardSpeedRate'] = REAL['MaxBackwardSpeedRate']
    t, cls = balance.GROUND[VID]
    d['MaxHealth'] = balance.ground_hp(VID)
    d['DeathChargeKg'] = balance.death_charge_kg(VID)
    d['DamageClass'] = cls
    d['DamageModifiers'] = balance.modifiers(cls)

    # seats: 0 operator (controller: lays and fires the launcher from its sight; drives, the pack's convention),
    # 1 driver (the sight point as his eye: the owner's rule for this expansion), 2 commander (looks round from his
    # hatch periscopes over the roof); all enclosed, dismount behind the hull
    hull_lo, hull_hi = model.box(model.hull)
    dismount = {'Transform': 'Vehicle', 'Position': [0, 0.45, round(-(float(hull_hi[2]) / 16 + 1.3), 5)]}
    sight = spec['attachments']['gunnerCamera']['at']
    eye = mtbgeo.geo_to_data(np.array(sight))
    first = spec['_muzzles'][0]
    for i, seat in enumerate(d['Seats']):
        pos, tr = seat['Position'], seat['Transform']
        new = {'HidePassenger': True, 'IsEnclosed': True, 'Transform': tr, 'Position': pos,
               'BodyAttachment': 'Turret' if tr == 'Turret' else 'Vehicle', 'DismountInfo': dict(dismount)}
        if i == 0:
            new.update({'Weapons': ['Rocket'], 'MinPitch': REAL['TurretPitchRange'][0],
                        'MaxPitch': REAL['TurretPitchRange'][1], 'Sensitivity': [0.45, 0.52, 0.8],
                        'CanRotateHead': False,
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
    d['Attachments']['driver_camera'] = {'Parent': 'VehicleCustomPitch', 'Position': E.r5(eye), 'Direction': [0, 0, 1]}

    # weapon: 24 x MO.1.01.04M, unguided (ballistic computer only), fired as a ripple salvo from the tube mouths
    names = spec['_muzzles']
    origin = fit_existing.frame_origin(d, 'Barrel')
    positions = [E.r5(origin + np.array(d['Attachments'][n]['Position'])) for n in names]
    sounds = {'Fire1P': 'berts_vehicle_pack:tap_bm21fire', 'Fire3P': 'berts_vehicle_pack:tap_bm21fire',
              'Fire3PFar': 'berts_vehicle_pack:tap_bm21fire', 'Fire3PVeryFar': 'berts_vehicle_pack:tap_bm21fire',
              'Fire1PGain': 0.8, 'Fire3PGain': 1.0, 'Fire3PFarGain': 1.0, 'Fire3PVeryFarGain': 1.0, 'Volume': 1.4,
              'VehicleReload3p': 'superbwarfare:missile_reload', 'VehicleReloadSoundTime': 7,
              'Change': 'superbwarfare:into_missile'}
    pid = f'berts_vehicle_pack:{VID}/rocket/default'
    w = E.rocket_weapon(ROCKET['name'], ROCKET['magazine'], ROCKET['rpm'], ROCKET['reload_ticks'],
                        'berts_vehicle_pack:s13_rocket', pid, ROCKET['tnt'], ROCKET['velocity'], ROCKET['gravity'],
                        ROCKET['life'], names, positions, sounds, spread=0.6, radius=ROCKET['radius'],
                        icon='generic_small_rocket', ammo='superbwarfare:small_rocket')
    w['ApDurability'] = 40
    d['Weapons'] = {'Rocket': w}
    prof = E.rocket_profile(f'berts_vehicle_pack:{VID}/rocket', 'berts_vehicle_pack:mo_1_01_04m', 220, 300, 800, 110.0,
                            explosion='large', scale=1.8, boost_ticks=40)
    E.dump(os.path.join(vehgen.DATA, 'sbw', 'projectile_profiles', VID, 'rocket', 'default.json'), prof)

    # sounds: the T-72A's (TaP T72Engine / T72Idle custom profile, Lizardpyle T-72 tracks): the TOS-1A is a T-72A chassis
    d['EngineSoundMode'] = 'CUSTOM'
    d['TrackSoundMode'] = 'NATIVE'
    d['CustomSoundProfileId'] = 'berts_vehicle_pack:tap_engine_t72engine__t72idle'
    d['EngineSound'] = 'berts_vehicle_pack:tank_engine_silent'
    E.dump(paths['data'], d)
    va = os.path.join(vehgen.MAIN, 'resources', 'assets', 'berts_vehicle_pack', 'sbw', 'vehicle_audio', VID + '.json')
    E.dump(va, {'extends': 'bvp_audio:shared/t72__t72__t90'})

    # armour: the T-72A hull plates (mapped box to box), no turret plates (the launcher is a thin box: the 16 mm floor
    # applies), the rockets as an ammo rack in the launcher (barrel frame), the engine kept
    a = E.load(paths['armor'])
    a['plates'] = [p for p in a['plates'] if p.get('frame', 'hull') == 'hull']
    gl, gh = model.box(GUN[1:])                 # rest-pose geo px / 16, as the turret-frame plates (t64bv_build)
    c, h = (gl + gh) / 2, (gh - gl) / 2
    a['ammo_racks'] = [{'name': 'launcher_rockets_00', 'center': E.r5([-c[0] / 16, c[1] / 16, c[2] / 16]),
                        'half_size': E.r5(h / 16 * 0.9), 'rotation': [0, 0, 0], 'frame': 'barrel'}]
    a.pop('ap_round', None)
    a['id'] = VID
    E.dump(paths['armor'], a)

    # exhaust: the left rear fender grille (EngineExhaust: model frame px / 16, +Z = rear), 0.25 px outside its face
    x0, x1, y0, y1, zf = EXHAUST_BOX_TB
    p = model.geo(((x0 + x1) / 2, (y0 + y1) / 2, zf))
    origin_px = [float(p[0]) + 0.25, float(p[1]), float(p[2])]
    c = E.load(paths['client'])
    c.pop('FittedGroundRig', None)
    c['EngineExhaust']['Origins'] = [{'Id': 'engine_exhaust_group_origin_000', 'Position': E.r5(np.array(origin_px) / 16)}]
    E.dump(paths['client'], c)
    return E.r5(eye), origin_px


def exits_box(model):
    x0, x1, y0, y1, zf = EXHAUST_BOX_TB
    a, b = model.geo((x0, y0, zf)), model.geo((x1, y1, zf))
    return [round(float(a[0]), 3), round(float(a[0]), 3), round(float(min(a[1], b[1])), 3), round(float(max(a[1], b[1])), 3),
            round(float(min(a[2], b[2])), 3), round(float(max(a[2], b[2])), 3)]


def main(argv):
    write = '--write' in argv
    model = Model()
    tpl = E.load(fit_existing.files_of(TEMPLATE)['geo'])
    geo = build_geo(model, tpl)
    if '--dump' in argv:
        E.dump(argv[argv.index('--dump') + 1], geo, compact=True)
        return 0
    spec = make_spec(model)
    fit_specs.SPECS[VID] = spec
    files = fit_existing.files_of(TEMPLATE)
    client = json.loads(json.dumps(E.load(files['client'])).replace(TEMPLATE, VID))
    for side in client['RunningGear']['TrackRender']['Sides']:
        side['XCenter'] = TRACK_X[side['Side']]
        side['RoadCenters'] = [round(float(model.geo((x, 0, 0))[2]), 5) for x in (43.5, 26.5, 9.5, -7.5, -24.5, -41.5)]
    orig = {'geo': geo, 'wreck': E.load(files['wreck']), 'client': client, 'data': {}, 'armor': {}}
    fit_existing.originals = lambda vid, w: orig
    fit_existing.register = lambda spec, out: None            # registration: hand-anchored insertions below
    rc = fit_existing.main([VID] + (['--write'] if write else []))
    if write and rc == 0:
        E.register(VID, spec['hitbox'], AFTER)
        E.add_lang(LANG, f'entity.berts_vehicle_pack.{AFTER}')
        E.add_containers(VID, AFTER)
        sys.path.insert(0, os.path.join(HERE, '..', 'track_path'))
        import belt
        _, msg = belt.rebuild(VID, write=True)
        print('belt:', msg)
        eye, ex = finish(model, spec)
        E.write_textures(VID, model, TEMPLATE, TPL_ROWS, size=(model.tex_w, TEX_H))
        print('driver_camera (data frame):', eye, 'exhaust origin px:', [round(v, 3) for v in ex])
        print('EXITS box (model px):', exits_box(model))
    return rc


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
