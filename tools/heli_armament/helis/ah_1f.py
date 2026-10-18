# AH-1F Cobra: WT ah_1f. Four stub-wing slots; TOW launchers only outboard (1, 4). Pylon points: the tops of the
# baked M261 / TOW stores the model carried (inner x 1.25, outer x 1.94), at their centres.
_ALL = ['lau131_m247', 'm261_m247']
_OUT = _ALL + ['tow_x2', 'tow2_x2', 'tow_x4', 'tow2_x4']
SPEC = {
    'wt': 'ah_1f', 'payload': 1450, 'weapon_seat': 1,
    # The gunner aims the M197 turret and guides the TOWs through his telescopic sight unit; the pilot fires the rockets
    # and fires the M197 with the turret stowed straight ahead. The gunner can fire the wing rockets as well.
    'crew': {'unguided': [0, 1], 'guided': [1], 'guns': {'PassengerGun1': [1, 0]}},
    # M97A1 turret: 200° of azimuth (±100°) and 60° of elevation (+10° / -50°) (FAS, M197 20mm Gun)
    'gun': {'PassengerWeaponStationYawRange': [-100, 100], 'PassengerWeaponStationPitchRange': [-50, 10]},
    'slots': [
        {'id': 'slot_1', 'name': 'Left outboard', 'pos': [1.9375, 1.5625, 1.4688], 'stores': _OUT},
        {'id': 'slot_2', 'name': 'Left inboard', 'pos': [1.25, 1.5, 2.0], 'stores': _ALL},
        {'id': 'slot_3', 'name': 'Right inboard', 'pos': [-1.25, 1.5, 2.0], 'stores': _ALL},
        {'id': 'slot_4', 'name': 'Right outboard', 'pos': [-1.9375, 1.5625, 1.4688], 'stores': _OUT},
    ],
}
