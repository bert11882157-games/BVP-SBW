# MI_24D: WT mi_24d. Wingtip rails (1, 6): two Falanga each; outboard (2, 5) and inboard (3, 4) pylons: UB-32 S-5K,
# S-24, OFAB-250, UPK-23 and, inboard, FAB-500M-54. The operator fires the guided stores. Pylon points: tops of the
# baked stores; the inboard UB-32 pods were part of the hull mesh and are removed with it (hull_remove).
_OUT = ['ub32_s5k', 's24b', 'ofab250', 'upk23']
_IN = ['ub32_s5k', 's24b', 'ofab250', 'fab500_m54', 'upk23']
SPEC = {
    'wt': 'mi_24d', 'payload': 3017, 'weapon_seat': 1,
    'hull_remove': [[1.9, 2.7, 1.9, 2.65, -1.5, 1.9]],
    'slots': [
        {'id': 'slot_1', 'name': 'Left wingtip', 'pos': [4.5938, 2.25, 0.0781], 'stores': ['falanga_x2']},
        {'id': 'slot_2', 'name': 'Left outboard', 'pos': [3.1875, 2.3438, 0.2188], 'stores': _OUT},
        {'id': 'slot_3', 'name': 'Left inboard', 'pos': [2.315, 2.59, 0.22], 'stores': _IN},
        {'id': 'slot_4', 'name': 'Right inboard', 'pos': [-2.315, 2.59, 0.22], 'stores': _IN},
        {'id': 'slot_5', 'name': 'Right outboard', 'pos': [-3.1875, 2.3438, 0.2188], 'stores': _OUT},
        {'id': 'slot_6', 'name': 'Right wingtip', 'pos': [-4.5938, 2.25, 0.0781], 'stores': ['falanga_x2']},
    ],
}
