# Ka-50: WT ka_50. Outboard (1, 4): the six-round Vikhr launchers (the Igla only on the left, slot 1); every slot
# takes the B-8V20, OFAB-250 and UPK-23-250, the inboard (2, 3) also the FAB-500M-62. The pilot fires everything.
# Pylon points: tops of the baked stores.
_ANY = ['b8v20_s8ko', 'ofab250', 'upk23']
SPEC = {
    'wt': 'ka_50', 'payload': 3017, 'weapon_seat': 0,
    'slots': [
        {'id': 'slot_1', 'name': 'Left outboard', 'pos': [3.9979, 1.7562, 0.0], 'stores': ['igla_x2'] + _ANY + ['vikhr_x6']},
        {'id': 'slot_2', 'name': 'Left inboard', 'pos': [2.6293, 1.6842, 0.2701], 'stores': _ANY + ['fab500_m62']},
        {'id': 'slot_3', 'name': 'Right inboard', 'pos': [-2.6293, 1.6842, 0.2701], 'stores': _ANY + ['fab500_m62']},
        {'id': 'slot_4', 'name': 'Right outboard', 'pos': [-3.9979, 1.7562, 0.0], 'stores': _ANY + ['vikhr_x6']},
    ],
}
