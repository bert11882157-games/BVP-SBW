#!/usr/bin/env python3
"""DV15 (dv15): the supplied "DV15.mtb" fitted as the CMN DV15 fast interceptor boat on the SBW ship contract (ee2_naval.py).

    python3 tools/vehgen/dv15_build.py [--write]
    (then dv15_icon.py, tools/icon_flags/flags.py --write dv15)

The source (228 shapebox elements, toolbox frame +x forward, +y down, +z left; 0.0614 m per unit: the 252.5-unit hull is the
DV15's 15.5 m) is a closed hull with a bridge forward of the middle, a rear deck with rail, a mast and a remotely operated
12.7 mm gun station on the bridge roof:

    0   (188)  hull, bridge (windscreen x 17 .. 34, y -36 .. -24; floor y -16), rear deck, mast, twin outboard-style drives and
               the stern rail
    3   (9)    the station's yaw base (rotation point 0, -40.5, 0) = turret
    4   (31)   the gun (receiver, barrel x 3 .. 21, sensor block; rotation point -1, -48.5, 0 for 24 elements) = barrel; seven
               cradle / sensor elements (rotation point 0, -40.5, 0) turn with the yaw base

Systems: the SBW speedboat's data contract. The DV15 is not in War Thunder (checked 2026-10-23), so real figures (navypedia,
CMN): 12 t full load, 50 kn, 15.5 x 3.0 m, two Caterpillar C32; the gun is the 12.7 mm M2HB of the M113A3 (War Thunder belts).
"""
import copy
import json
import os
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import ee2_ground as E  # noqa: E402
import ee2_naval as N  # noqa: E402
import mtbgeo  # noqa: E402
import vehgen  # noqa: E402
import fit_existing  # noqa: E402

VID = 'dv15'
AFTER = 'gaz_tigr'
SOURCE = 'DV15.mtb'
SCALE = 0.0614 * 17.6               # 0.0614 m per unit x 17.6 px per m
KEEL_Y = 10.0                       # toolbox y of the keel: the boat's geometry rests on y 0

TURRET_PIVOT = (0.0, -40.5, 0.0)
GUN_PIVOT = (-1.0, -48.5, 0.0)
MUZZLE = (21.3, -49.0, 0.0)
SIGHT_EYE = (-11.0, -56.5, 0.0)     # over the receiver (top y -52), behind the sensor block
EYE_UP = 13.9 / SCALE
HELM_EYE = (35.0, -31.0, 8.0)         # the bridge is a solid block in the model (windows are texture only): the helm eye sits just ahead of
                                    # the windscreen plane (x 34), left of centre, 5 units under the roof edge (y -36)
SEATS = {'helm': (20.0, -19.0, 8.0), 'gunner': (20.0, -19.0, -8.0), 'rear_l': (2.0, -19.0, 9.0), 'rear_r': (2.0, -19.0, -9.0)}

REAL = {
    'Mass': 12.0,                    # navypedia: 12 t full load
    'MaxForwardSpeedRate': round(1.5 * (50 * 1.852) / 74.0, 4),   # 50 kn = 92.6 km/h; the SBW speedboat's 1.5 taken as 74 km/h
    'TurretTurnSpeed': [90.0, 90.0],  # the owner-approved WT machine-gun rate, as the M113A3 station
    'TurretPitchRange': [-20.0, 60.0],
}
LANG = {'entity.berts_vehicle_pack.dv15': 'DV15 Fast Interceptor Boat',
        'weapon.berts_vehicle_pack.dv15_machinegun': '12.7 mm M2HB'}


class Model(E.Model):
    def __init__(self):
        super().__init__(SOURCE, SCALE, KEEL_Y, 0.0)
        gun = [i for i in self.part(4) if (E.mtb.num(self.E[i][6]), E.mtb.num(self.E[i][7])) == (-1.0, -48.5)]
        self.barrel = gun
        self.turret = self.part(3) + [i for i in self.part(4) if i not in gun]
        self.hull = self.part(0)
        assert len(self.barrel) == 24 and len(self.turret) == 16, (len(self.barrel), len(self.turret))


def eye_of(p):
    return (p[0], p[1] - EYE_UP, p[2])


def main(argv):
    write = '--write' in argv
    model = Model()
    t = model.px
    tpl = E.load(fit_existing.files_of('m142_himars')['geo'])
    geo = N.boat_geo(model, tpl, {'id': VID, 'hull': model.hull, 'turret': (model.turret, TURRET_PIVOT),
                                  'barell': (model.barrel, GUN_PIVOT)})
    if '--dump' in argv:
        E.dump(argv[argv.index('--dump') + 1], geo, compact=True)
        return 0
    g_ = model.geo
    to_data = mtbgeo.geo_to_data
    tp, bp = np.array(g_(TURRET_PIVOT)), np.array(g_(GUN_PIVOT))
    d = N.speedboat_data()
    d = {'ID': f'berts_vehicle_pack:{VID}', **d}
    sys.path.insert(0, os.path.join(HERE, '..', 'damage'))
    import balance                       # hull points and damage modifiers by class (DamageNormalizationDataTest rule)
    t_, cls = balance.GROUND[VID]
    d['MaxHealth'] = balance.ground_hp(VID)
    d['DeathChargeKg'] = balance.death_charge_kg(VID)
    d['DamageClass'] = cls
    d['DamageModifiers'] = balance.modifiers(cls)
    d['Mass'] = REAL['Mass']
    d['EngineInfo'].update({'MaxForwardSpeedRate': REAL['MaxForwardSpeedRate'], 'MaxBackwardSpeedRate': 0.7,
                            'Increment': 0.03, 'SteeringSpeed': 0.13})
    d['VehicleIcon'] = f'berts_vehicle_pack:textures/vehicle_icon/{VID}.png'
    d['ThirdPersonCameraPos'] = [0, 3.4, 9.0]
    d['DestroyInfo'] = {'ParticleType': 'Medium', 'ExplosionDamage': 100, 'ExplosionRadius': 6}
    d['HasDecoy'] = False
    d['TurretPos'] = E.r5(to_data(tp))
    d['BarrelPos'] = E.r5(to_data(bp - tp))
    d['TurretTurnSpeed'] = [round(v / E.NATIVE_RATE, 4) for v in REAL['TurretTurnSpeed']]
    d['TurretPitchRange'] = REAL['TurretPitchRange']
    d['TurretControllerIndex'] = 1

    # boxes: the hull in slices (the V bow narrows), the bridge, the station
    hull = model.hull
    boxes = [vehgen.box_obb(lo, hi) for lo, hi in N.slice_boxes(model, hull, [-110, -22, 40, 80, 150])]
    tlo, thi = model.box(model.turret)
    blo, bhi = model.box(model.barrel)
    boxes.append(vehgen.box_obb(tlo, thi, 'Turret', 'Turret', origin=tp))
    boxes.append(vehgen.box_obb(blo, bhi, 'Barrel', 'Turret', origin=bp))
    d['OBB'] = boxes

    # attachments and seats
    at = {}
    for name, p in (('gunnerCamera', SIGHT_EYE), ('hmg_muzzle', MUZZLE)):
        at[name] = {'Parent': 'Barrel', 'Position': E.r5(to_data(np.array(g_(p)) - bp)), 'Direction': [0, 0, 1]}
    eye = to_data(np.array(g_(HELM_EYE)))
    at['driver_camera'] = {'Parent': 'VehicleCustomPitch', 'Position': E.r5(eye), 'Direction': [0, 0, 1]}
    d['Attachments'] = at
    # dismount: each seat steps out beside the hull at its own z (the bridge is a closed block; the old single point 1 block behind the
    # stern put the helm 11 blocks from it, the same defect as the LCAC's 23-block throw): ee2_naval.side_dismount
    obbs = d['OBB']
    sp = d['Seats']

    def seat_pos(name):
        return E.r5(to_data(np.array(g_(SEATS[name])) + [0, -12.0, 0]))
    import driver_cameras
    helm = copy.deepcopy(sp[0])
    helm.update({'Position': seat_pos('helm'), 'DismountInfo': N.side_dismount(seat_pos('helm'), obbs), 'CameraPos': driver_cameras.camera(),
                 'CanRotateHead': True, 'HidePassenger': True})
    gunner = {'Transform': 'Vehicle', 'Position': seat_pos('gunner'), 'MinPitch': REAL['TurretPitchRange'][0],
              'MaxPitch': REAL['TurretPitchRange'][1], 'CanRotateBody': True, 'Weapons': ['MachineGun'],
              'Sensitivity': [0.45, 0.52, 0.8], 'CanRotateHead': False, 'HidePassenger': True,
              'CameraPos': {'Transform': 'Barrel', 'Direction': 'Barrel', 'UseFixedCameraPos': True,
                            'Position': [0, 0, 0], 'ZoomPosition': [0, 0, 0],
                            'EyeAttachment': 'gunnerCamera', 'ZoomEyeAttachment': 'gunnerCamera',
                            'DirectionAttachment': 'hmg_muzzle', 'ZoomDirectionAttachment': 'hmg_muzzle',
                            'CameraMode': 'PLAYER_LOOK_AIM', 'AimCameraMode': 'PLAYER_LOOK_AIM'},
              'DismountInfo': N.side_dismount(seat_pos('gunner'), obbs)}
    rear = []
    for name in ('rear_l', 'rear_r'):
        s = copy.deepcopy(sp[2])
        s.update({'Position': seat_pos(name), 'Orientation': 0, 'DismountInfo': N.side_dismount(seat_pos(name), obbs), 'HidePassenger': True})
        rear.append(s)
    d['Seats'] = [helm, gunner] + rear

    # weapon: the M113A3's M2HB (War Thunder belts), laid by the remote station
    w = N.import_weapon(VID, 'MachineGun', 'm113a3', 'PassengerMachineGun', d)
    muz = at['hmg_muzzle']['Position']
    w['ShootPos'] = {'Transform': 'Barrel', 'Positions': [muz], 'Directions': ['Barrel'], 'ViewPosition': muz,
                     'MuzzleAttachments': ['hmg_muzzle'], 'MuzzleDirectionAttachments': ['hmg_muzzle'],
                     'HudOriginAttachment': 'hmg_muzzle', 'HudDirectionAttachment': 'hmg_muzzle',
                     'ViewAttachment': 'hmg_muzzle', 'ViewDirectionAttachment': 'hmg_muzzle',
                     'SeekAttachment': 'hmg_muzzle', 'SeekDirectionAttachment': 'hmg_muzzle',
                     'EffectAttachments': ['hmg_muzzle'], 'EffectDirectionAttachments': ['hmg_muzzle']}
    w['Name'] = f'weapon.berts_vehicle_pack.{VID}_machinegun'

    if not write:
        print('dv15 dry run:', len(d['OBB']), 'boxes,', len(d['Seats']), 'seats; length',
              round(float(model.box(model.hull)[1][2] - model.box(model.hull)[0][2]) / 16, 2), 'blocks')
        return 0
    paths, out = N.write_vehicle(VID, geo, d, None)
    N.register_boat(VID, (3.4, 2.6), AFTER)
    E.add_lang(LANG, f'entity.berts_vehicle_pack.{AFTER}')
    E.add_containers(VID, AFTER, land=False)
    E.write_textures(VID, model)
    print('wrote', len(out.files), 'files; driver_camera (data frame):', at['driver_camera']['Position'])
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
