# Mi-24V: WT mi_24v. Wingtip launchers (1, 6): two Shturm; outboard pylons (2, 5): R-60 / R-60M, pods, S-24,
# a second pair of Shturm, OFAB-250, UPK-23, GUV; inboard (3, 4): the same without missiles, plus FAB-500M-54.
# The operator fires the guided stores. Pylon points: tops of the baked stores.
_POD = ['ub32_s5kp', 'b8v20_s8ko', 's24b', 'ofab250', 'upk23', 'guv8700']
_OUT = ['r60_x1', 'r60_x2', 'r60m_x2'] + _POD[:3] + ['shturm_x2'] + _POD[3:]
_IN = _POD[:4] + ['fab500_m54'] + _POD[4:]
SPEC = {
    'wt': 'mi_24v', 'payload': 2400, 'weapon_seat': 1,
    'slots': [
        {'id': 'slot_1', 'name': 'Left wingtip', 'pos': [3.7458, 1.216, -1.5127], 'stores': ['shturm_x2']},
        {'id': 'slot_2', 'name': 'Left outboard', 'pos': [2.8093, 1.6121, -1.4119], 'stores': _OUT},
        {'id': 'slot_3', 'name': 'Left inboard', 'pos': [2.017, 1.7202, -1.3723], 'stores': _IN},
        {'id': 'slot_4', 'name': 'Right inboard', 'pos': [-2.017, 1.7202, -1.3723], 'stores': _IN},
        {'id': 'slot_5', 'name': 'Right outboard', 'pos': [-2.8093, 1.6121, -1.4119], 'stores': _OUT},
        {'id': 'slot_6', 'name': 'Right wingtip', 'pos': [-3.7458, 1.216, -1.5127], 'stores': ['shturm_x2']},
    ],
}
