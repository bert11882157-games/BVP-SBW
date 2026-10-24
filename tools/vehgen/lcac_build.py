#!/usr/bin/env python3
"""LCAC (lcac): the supplied "LCAC Hovercraft.mtb" fitted as the Landing Craft Air Cushion on the SBW ship data contract
(ee2_naval.py).

    python3 tools/vehgen/lcac_build.py [--write]
    (then lcac_icon.py, tools/icon_flags/flags.py --write lcac)

The source (917 shapebox elements, one part, 2048 x 1024 texture; toolbox frame +x forward, +y down, +z left) is the craft with
both ramps DOWN and nothing separate: the skirt (y 20 .. 23 is its lowest face), the cargo deck (top y -14, z -101 .. 101), the bow
ramp (x 132 .. 173), the stern fan housings, the right-hand cab at the bow (x 80 .. 138, z -101 .. -58) and the left and right
side structures (z +-57 .. 121). At 0.057 m per unit (a compromise between the length, 430 units = 24.5 m, and the width,
266 units = 15.2 m, against the real 26.8 x 14 m) it is a single hull bone; the ramps do not move.

Systems: the SBW speedboat's ship contract; unarmed (the real mounts, two M240B / Mk 19, are not identifiable in the model).
The LCAC is not in War Thunder (checked 2026-10-23), so real figures (Wikipedia LCAC): 185 t full load, 40+ kn loaded,
crew 5, 60 short tons of cargo, 26.8 x 14 m. It is an air-cushion craft: the SBW ship engine moves only on water.
"""
import copy
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

VID = 'lcac'
AFTER = 'dv15'
SOURCE = 'LCAC Hovercraft.mtb'
SCALE = 0.057 * 17.6                # 0.057 m per unit x 17.6 px per m
SKIRT_Y = 23.0                      # toolbox y of the skirt bottom: the geometry rests on y 0
CENTRE_X = -12.0                    # the origin: the middle of the length (x -227 .. 203)
EYE_UP = 13.9 / SCALE
CRAFTMASTER_EYE = (143.0, -36.0, -88.0)    # the cab is a solid block (windows are texture): 5 units ahead of its front face (x 138)
SEATS = {'craftmaster': (112.0, -21.0, -76.0), 'navigator': (112.0, -21.0, -62.0), 'engineer': (96.0, -21.0, -76.0),
         'loadmaster_1': (96.0, -21.0, -62.0), 'loadmaster_2': (84.0, -21.0, -69.0)}      # inside the cab, 1.0 - 1.9 blocks from the well
DECK_SEATS = [(x, z) for x in (10.0, 40.0, 70.0) for z in (50.0, -50.0)]     # troops on the cargo deck, facing the centre line
REAL = {
    'Mass': 185.0,                   # 182 long tons full load (Wikipedia LCAC)
    'MaxForwardSpeedRate': round(1.5 * (40 * 1.852) / 74.0, 4),    # 40 kn = 74 km/h on the speedboat's 1.5 = 74 km/h scale
}
LANG = {'entity.berts_vehicle_pack.lcac': 'LCAC Landing Craft Air Cushion'}


class Model(E.Model):
    def __init__(self):
        super().__init__(SOURCE, SCALE, SKIRT_Y, 0.0, z_shift=CENTRE_X * SCALE)
        self.hull = self.part(0)


def main(argv):
    write = '--write' in argv
    model = Model()
    tpl = E.load(fit_existing.files_of('m142_himars')['geo'])
    geo = N.boat_geo(model, tpl, {'id': VID, 'hull': model.hull})
    if '--dump' in argv:
        E.dump(argv[argv.index('--dump') + 1], geo, compact=True)
        return 0
    g_ = model.geo
    to_data = mtbgeo.geo_to_data
    sys.path.insert(0, os.path.join(HERE, '..', 'damage'))
    import balance
    import driver_cameras
    d = N.speedboat_data()
    d = {'ID': f'berts_vehicle_pack:{VID}', **d}
    for k in ('Weapons', 'TurretPos', 'BarrelPos', 'TurretTurnSpeed', 'TurretPitchRange', 'TurretControllerIndex'):
        d.pop(k, None)
    t_, cls = balance.GROUND[VID]
    d['MaxHealth'] = balance.ground_hp(VID)
    d['DeathChargeKg'] = balance.death_charge_kg(VID)
    d['DamageClass'] = cls
    d['DamageModifiers'] = balance.modifiers(cls)
    d['Mass'] = REAL['Mass']
    d['EngineInfo'].update({'MaxForwardSpeedRate': REAL['MaxForwardSpeedRate'], 'MaxBackwardSpeedRate': 0.4,
                            'Increment': 0.015, 'Decrement': 0.015, 'SteeringSpeed': 0.06,
                            'BodyRollRate': 0.0})      # the cargo deck (lcac_deck.py) never rolls, like a carrier's
    d['VehicleIcon'] = f'berts_vehicle_pack:textures/vehicle_icon/{VID}.png'
    d['ThirdPersonCameraPos'] = [0, 6.0, 18.0]
    d['DestroyInfo'] = {'ParticleType': 'Medium', 'ExplosionDamage': 150, 'ExplosionRadius': 10}
    d['HasDecoy'] = False

    # boxes: the deck and skirt slab, then the left and right superstructures (everything above the deck)
    hull = model.hull
    low = [i for i in hull if model.tb_box([i])[0][1] >= -14.5]
    up = [i for i in hull if i not in low]
    boxes = [vehgen.box_obb(*model.box(low))]
    for sign in (1, -1):
        side = [i for i in up if (model.tb_box([i])[0][2] + model.tb_box([i])[1][2]) / 2 * sign > 0]
        if side:
            boxes.append(vehgen.box_obb(*model.box(side)))
    d['OBB'] = boxes

    # seats: 0 craftmaster (controller of the craft), 1 navigator, 2 engineer, 3-4 loadmasters (cab), 5-10 deck troops
    at = {}
    eye = to_data(np.array(g_(CRAFTMASTER_EYE)))
    at['driver_camera'] = {'Parent': 'VehicleCustomPitch', 'Position': E.r5(eye), 'Direction': [0, 0, 1]}
    d['Attachments'] = at
    hull_lo, hull_hi = model.box(hull)
    # dismount: every seat steps out onto the nearest point of the cargo-well floor (0.5 block clear of walls and ramp edges), found on
    # the deck heightfield itself (lcac_deck.py), in the vehicle frame; the old single point 1 block behind the stern threw a rider from the
    # bow cab 23 blocks (the server's 'moved too quickly') and left him in the water
    import lcac_deck as L
    deck, deck_top, _ = L.build(geo=geo)

    def dismount_for(seat_pos):
        x, y, z = L.floor_spot(deck, deck_top, seat_pos[0], seat_pos[2])
        return {'Transform': 'Vehicle', 'Position': [x, y, z]}
    dismount = None
    base = copy.deepcopy(d['Seats'][2])

    def seat(pos_tb, orientation=0):
        s = copy.deepcopy(base)
        s.update({'Position': E.r5(to_data(np.array(g_(pos_tb)) + [0, -12.0, 0])), 'Orientation': orientation,
                  'HidePassenger': True})
        s['DismountInfo'] = dismount_for(s['Position'])
        return s
    seats = []
    for name in ('craftmaster', 'navigator', 'engineer', 'loadmaster_1', 'loadmaster_2'):
        s = seat(SEATS[name])
        if not seats:
            s = copy.deepcopy(d['Seats'][0])
            s.update({'Position': E.r5(to_data(np.array(g_(SEATS[name])) + [0, -12.0, 0])),
                      'CameraPos': driver_cameras.camera(), 'CanRotateHead': True, 'HidePassenger': True})
            s['DismountInfo'] = dismount_for(s['Position'])
        seats.append(s)
    for x, z in DECK_SEATS:
        seats.append(seat((x, -21.0, z), orientation=-90 if z > 0 else 90))
    d['Seats'] = seats

    if not write:
        print('lcac dry run:', len(d['OBB']), 'boxes,', len(d['Seats']), 'seats; length',
              round(float(hull_hi[2] - hull_lo[2]) / 16, 2), 'blocks, width', round(float(hull_hi[0] - hull_lo[0]) / 16, 2))
        return 0
    paths, out = N.write_vehicle(VID, geo, d, None)
    N.register_boat(VID, (15.0, 6.5), AFTER, entity='FittedDeckBoatEntity')
    E.add_lang(LANG, f'entity.berts_vehicle_pack.{AFTER}')
    E.add_containers(VID, AFTER, land=False)
    E.write_textures(VID, model)
    print('wrote', len(out.files), 'files; driver_camera (data frame):', at['driver_camera']['Position'])
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
