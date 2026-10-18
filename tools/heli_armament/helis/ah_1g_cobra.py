# AH-1G Cobra: WT ah_1g. Four stub-wing slots of Aero 6A / 7A FFAR launchers; the XM18 minigun pods on the inboard
# slots and the XM35 20 mm on the left inboard (slot 2) only. Pylon points: tops of the baked stores.
_POD = ['aero6a_ffar', 'aero7a_ffar']
SPEC = {
    'wt': 'ah_1g', 'payload': 1450, 'weapon_seat': 0,
    # No turret gun is modelled; both crew members can fire the wing stores, the pilot normally, the gunner as backup.
    'crew': {'unguided': [0, 1], 'guided': [0]},
    'slots': [
        {'id': 'slot_1', 'name': 'Left outboard', 'pos': [2.4492, 2.1164, -0.2881], 'stores': _POD},
        {'id': 'slot_2', 'name': 'Left inboard', 'pos': [1.7288, 2.1164, 0.3152], 'stores': _POD + ['xm18_m134', 'xm35_m195']},
        {'id': 'slot_3', 'name': 'Right inboard', 'pos': [-1.7288, 2.1164, 0.3152], 'stores': _POD + ['xm18_m134']},
        {'id': 'slot_4', 'name': 'Right outboard', 'pos': [-2.4492, 2.1164, -0.2881], 'stores': _POD},
    ],
}
