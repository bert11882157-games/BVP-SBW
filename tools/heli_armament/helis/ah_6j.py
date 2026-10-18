# AH-6J Little Bird: WT's AH-6M stands in (ah_6m). The outboard ends of the crossbeam (1, 4) carry the pods,
# Hellfires or the GAU-19; the inboard stations (2, 3) the M134D miniguns. Pylon points: tops of the baked stores
# outboard; the miniguns hang from the same crossbeam (its underside, y 1.31) just outside the cabin.
_OUT = ['lau131_m247', 'm261_m247', 'lau131_apkws_m151', 'lau131_apkws_m282', 'm279_agm114b', 'm279_agm114k', 'gau19']
SPEC = {
    'wt': 'ah_6m', 'payload': 815, 'weapon_seat': 0,
    'slots': [
        {'id': 'slot_1', 'name': 'Left outboard', 'pos': [1.999, 1.306, 1.6838], 'stores': _OUT},
        {'id': 'slot_2', 'name': 'Left inboard', 'pos': [1.15, 1.31, 1.90], 'stores': ['m134d']},
        {'id': 'slot_3', 'name': 'Right inboard', 'pos': [-1.15, 1.31, 1.90], 'stores': ['m134d']},
        {'id': 'slot_4', 'name': 'Right outboard', 'pos': [-1.999, 1.306, 1.6838], 'stores': _OUT},
    ],
}
