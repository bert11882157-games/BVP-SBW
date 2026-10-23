# AH-1G Cobra: WT ah_1g. Four stub-wing slots of Aero 6A / 7A FFAR launchers; the XM18 minigun pods on the inboard
# slots and the XM35 20 mm on the left inboard (slot 2) only. Pylon points: tops of the baked stores.
# The M28 chin turret (WT slot 0: two M134 or two M129) carries its usual mixed fit here, one 7.62 mm M134 (4000
# rounds) and one 40 mm M129 (300 rounds), at WT's 4000 and 350 rounds/min. The airframe model had no turret, so
# build.py draws the M28 into its turret / barell bones (catalog.m28_turret, m28_guns).
_POD = ['aero6a_ffar', 'aero7a_ffar']
SPEC = {
    'wt': 'ah_1g', 'payload': 1450, 'weapon_seat': 0,
    # The gunner aims the M28 (the pilot fires it stowed straight ahead); both crew members fire every wing store.
    'crew': {'unguided': [0, 1], 'guided': [0, 1], 'guns': {'M134Turret': [1, 0], 'M129Turret': [1, 0]}},
    'turret_guns': [
        {'name': 'M134Turret', 'kind': 'm134', 'title': '7.62 mm M134 (M28 turret)', 'muzzle': [0.13, -0.08, 0.80]},
        {'name': 'M129Turret', 'kind': 'm129', 'title': '40 mm M129 (M28 turret)', 'muzzle': [-0.13, -0.08, 0.62]},
    ],
    # under the nose, the housing's top against the fuselage underside (y 1.7-1.9 at z 4.9)
    'turret_model': {'turret': 'm28_turret', 'barrel': 'm28_guns',
                     'uv': {'olive': [0.06933, 0.98828], 'dark': [0.42968, 0.89356], 'grey': [0.80567, 0.92089]}},
    # M28: about ±107° of azimuth, +21° / -50° (estimated from the published M28A1 envelope); slew as the others
    'gun': {'TurretPos': [0, 1.55, 4.9], 'BarrelPos': [0, 0, 0], 'TurretYawRange': [-107, 107],
            'TurretPitchRange': [-50, 21], 'TurretTurnSpeed': [39, 52], 'TurretControllerIndex': 1},
    'slots': [
        {'id': 'slot_1', 'name': 'Left outboard', 'pos': [2.4492, 2.1164, -0.2881], 'stores': _POD},
        {'id': 'slot_2', 'name': 'Left inboard', 'pos': [1.7288, 2.1164, 0.3152], 'stores': _POD + ['xm18_m134', 'xm35_m195']},
        {'id': 'slot_3', 'name': 'Right inboard', 'pos': [-1.7288, 2.1164, 0.3152], 'stores': _POD + ['xm18_m134']},
        {'id': 'slot_4', 'name': 'Right outboard', 'pos': [-2.4492, 2.1164, -0.2881], 'stores': _POD},
    ],
}
