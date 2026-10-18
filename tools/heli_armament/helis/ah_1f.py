# AH-1F Cobra: WT ah_1f. Four stub-wing slots; TOW launchers only outboard (1, 4). Pylon points: the tops of the
# baked M261 / TOW stores the model carried (inner x 1.25, outer x 1.94), at their centres.
_ALL = ['lau131_m247', 'm261_m247']
_OUT = _ALL + ['tow_x2', 'tow2_x2', 'tow_x4', 'tow2_x4']
SPEC = {
    'wt': 'ah_1f', 'payload': 1450, 'weapon_seat': 1,
    'slots': [
        {'id': 'slot_1', 'name': 'Left outboard', 'pos': [1.9375, 1.5625, 1.4688], 'stores': _OUT},
        {'id': 'slot_2', 'name': 'Left inboard', 'pos': [1.25, 1.5, 2.0], 'stores': _ALL},
        {'id': 'slot_3', 'name': 'Right inboard', 'pos': [-1.25, 1.5, 2.0], 'stores': _ALL},
        {'id': 'slot_4', 'name': 'Right outboard', 'pos': [-1.9375, 1.5625, 1.4688], 'stores': _OUT},
    ],
}
