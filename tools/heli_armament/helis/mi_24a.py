# MI_24A: WT mi_24a. Wingtip rails (1, 6): two Falanga each; outboard (2, 5) and inboard (3, 4) pylons: UB-32 S-5K,
# OFAB-250, UPK-23 and, inboard, FAB-500M-54. The operator (and the pilot) fire the guided stores. Pylon points: tops of the
# baked stores; the inboard UB-32 pods were part of the hull mesh and are removed with it (hull_remove).
_OUT = ['ub32_s5k', 'ofab250', 'upk23']
_IN = ['ub32_s5k', 'ofab250', 'fab500_m54', 'upk23']
SPEC = {
    'wt': 'mi_24a', 'payload': 3017, 'weapon_seat': 1,
    # Pilot: rockets, bombs, gun pods, and the nose gun (stowed straight ahead while he alone fires it). Operator: the nose gun and the Falanga.
    # Guided stores: the gunner's (WeaponSeat), and the pilot's too so the store keys work from both seats (owner).
    # Owner 2026-10-20: both crew members may select and fire every weapon (the operator the pods and bombs too);
    # a weapon both select is one weapon, fired at its own rate.
    'crew': {'unguided': [0, 1], 'guided': [1, 0], 'guns': {'PassengerGun1': [1, 0]}},
    # A-12.7 on its flexible nose mount (the VSPU-24 envelope is assumed)
    'gun': {'PassengerWeaponStationYawRange': [-60, 60], 'PassengerWeaponStationPitchRange': [-60, 20]},
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
