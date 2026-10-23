# Mi-24V: WT mi_24v. Wingtip launchers (1, 6): two Shturm; outboard pylons (2, 5): R-60 / R-60M, pods, S-24,
# a second pair of Shturm, OFAB-250, UPK-23, GUV; inboard (3, 4): the same without missiles, plus FAB-500M-54.
# The operator (and the pilot) fire the guided stores. Pylon points: tops of the baked stores.
_POD = ['ub32_s5kp', 'b8v20_s8ko', 's24b', 'ofab250', 'upk23', 'guv8700']
_OUT = ['r60_x1', 'r60_x2', 'r60m_x1', 'r60m_x2'] + _POD[:3] + ['shturm_x2'] + _POD[3:]
_IN = _POD[:4] + ['fab500_m54'] + _POD[4:]
SPEC = {
    'wt': 'mi_24v', 'payload': 2400, 'weapon_seat': 1,
    # Pilot: rockets, bombs, gun pods, and the nose gun (stowed straight ahead while he alone fires it). Operator: the VSPU-24 nose turret and
    # the Shturm / R-60 he guides or locks.
    # Guided stores: the gunner's (WeaponSeat), and the pilot's too so the store keys work from both seats (owner).
    # Owner 2026-10-20: both crew members may select and fire every weapon (the operator the pods and bombs too);
    # a weapon both select is one weapon, fired at its own rate.
    'crew': {'unguided': [0, 1], 'guided': [1, 0], 'guns': {'Cannon': [1, 0]}},
    # VSPU-24 YaK-B-12.7: 60° either side, +20° / -60° (Wikipedia, YakB-12.7). TurretTurnSpeed [pitch, yaw] in °/s
    # before the native 1.15 factor: ~45 / 60 °/s (estimated; no published slew rate).
    'gun': {'TurretYawRange': [-60, 60], 'TurretPitchRange': [-60, 20], 'TurretTurnSpeed': [39, 52]},
    'slots': [
        {'id': 'slot_1', 'name': 'Left wingtip', 'pos': [3.7458, 1.216, -1.5127], 'stores': ['shturm_x2']},
        {'id': 'slot_2', 'name': 'Left outboard', 'pos': [2.8093, 1.6121, -1.4119], 'stores': _OUT},
        {'id': 'slot_3', 'name': 'Left inboard', 'pos': [2.017, 1.7202, -1.3723], 'stores': _IN},
        {'id': 'slot_4', 'name': 'Right inboard', 'pos': [-2.017, 1.7202, -1.3723], 'stores': _IN},
        {'id': 'slot_5', 'name': 'Right outboard', 'pos': [-2.8093, 1.6121, -1.4119], 'stores': _OUT},
        {'id': 'slot_6', 'name': 'Right wingtip', 'pos': [-3.7458, 1.216, -1.5127], 'stores': ['shturm_x2']},
    ],
}
