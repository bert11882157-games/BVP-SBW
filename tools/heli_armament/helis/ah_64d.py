# AH-64D Longbow: WT ah_64d. Six slots: the wingtips (1, 6) carry the ATAS or the countermeasure dispenser, the four
# wing pylons (2-5) the M261, LAU-131 (APKWS), M279 or M272. Pylon points: the bottom centre of each pylon fairing
# of the model (x 1.83-2.04 / 2.87-3.08, bottom y 1.55-1.57) at the M261's lug centre (z 0.18); the tips at the
# end of the wing (x 3.24, wing underside y 1.96).
_PYLON = ['m261_m247', 'lau131_apkws_m151', 'lau131_apkws_m282', 'm279_agm114k', 'm272_agm114k']
_TIP = ['atas_stinger', 'ltc']
SPEC = {
    'wt': 'ah_64d', 'payload': 5000, 'weapon_seat': 1,
    # Both crew members fly and fight: either seat fires the rockets, the Hellfires / APKWS and the Stingers, and
    # either can slave the M230 to his helmet sight (Wikipedia, Boeing AH-64 Apache). The CPG steers the gun; the
    # pilot's rounds go where it points, or straight ahead when nobody steers it.
    'crew': {'unguided': [0, 1], 'guided': [1, 0], 'guns': {'M230': [1, 0]}},
    # M230 on the Area Weapon Subsystem turret: ±110° azimuth, +11° / -60° elevation (the commonly published
    # limits; the spring-loaded stow at +11° is in Wikipedia, M230 chain gun)
    'gun': {'PassengerWeaponStationYawRange': [-110, 110], 'PassengerWeaponStationPitchRange': [-60, 11]},
    'slots': [
        {'id': 'slot_1', 'name': 'Left wingtip', 'pos': [3.30, 2.02, 0.05], 'stores': _TIP},
        {'id': 'slot_2', 'name': 'Left outboard', 'pos': [2.975, 1.57, 0.18], 'stores': _PYLON},
        {'id': 'slot_3', 'name': 'Left inboard', 'pos': [1.935, 1.55, 0.18], 'stores': _PYLON},
        {'id': 'slot_4', 'name': 'Right inboard', 'pos': [-1.935, 1.55, 0.18], 'stores': _PYLON},
        {'id': 'slot_5', 'name': 'Right outboard', 'pos': [-2.975, 1.57, 0.18], 'stores': _PYLON},
        {'id': 'slot_6', 'name': 'Right wingtip', 'pos': [-3.30, 2.02, 0.05], 'stores': _TIP},
    ],
}
