# Mi-28N: WT mi_28n. Outboard (1, 4): four Igla, four or eight Ataka, B-8V20 or B-13L; inboard (2, 3): pods and
# UPK-23-250. The operator fires the guided stores. Pylon points: tops of the baked stores.
_OUT = ['igla_x4', 'b8v20_s8ko', 'b13l_s13of', 'ataka_x4', 'ataka_x8']
SPEC = {
    'wt': 'mi_28n', 'payload': 2050, 'weapon_seat': 1,
    'slots': [
        {'id': 'slot_1', 'name': 'Left outboard', 'pos': [3.5657, 2.0804, -1.5127], 'stores': _OUT},
        {'id': 'slot_2', 'name': 'Left inboard', 'pos': [2.5572, 2.1706, -1.2426], 'stores': ['b8v20_s8ko', 'b13l_s13of', 'upk23']},
        {'id': 'slot_3', 'name': 'Right inboard', 'pos': [-2.5572, 2.1706, -1.2426], 'stores': ['b8v20_s8ko', 'b13l_s13of', 'upk23']},
        {'id': 'slot_4', 'name': 'Right outboard', 'pos': [-3.5657, 2.0804, -1.5127], 'stores': _OUT},
    ],
}
