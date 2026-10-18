# Tiger: WT's Tiger UHT. Outboard (1, 4): ATAS or two-round Trigat; inboard (2, 3): FZ225, APKWS, HOT 3, four-round
# Trigat, HMP400 or RMP-LC. Pylon points: tops of the baked stores.
_IN = ['fz225_ffar', 'lau131_apkws_m151', 'lau131_apkws_m282', 'hot3_x2', 'hot3_x4', 'trigat_x4', 'hmp400', 'rmp_lc']
_OUT = ['atas_stinger_tiger', 'trigat_x2']
SPEC = {
    'wt': 'tiger_uht', 'payload': 1000, 'weapon_seat': 1,
    # three tube mouths of the old right-hand baked launcher were left in the hull
    'hull_remove': [[2.9, 3.2, 1.7, 2.1, 1.0, 1.3]],
    'slots': [
        {'id': 'slot_1', 'name': 'Left outboard', 'pos': [2.8122, 2.4433, 0.4921], 'stores': _OUT},
        {'id': 'slot_2', 'name': 'Left inboard', 'pos': [1.7928, 2.1972, 0.2109], 'stores': _IN},
        {'id': 'slot_3', 'name': 'Right inboard', 'pos': [-1.7928, 2.1972, 0.2109], 'stores': _IN},
        {'id': 'slot_4', 'name': 'Right outboard', 'pos': [-2.8122, 2.4433, 0.4921], 'stores': _OUT},
    ],
}
