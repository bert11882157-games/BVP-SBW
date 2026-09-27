"""Specs for tools/vehgen/fit_existing.py. Points are model px of the vehicle's original model (tools/vehgen/replaced/
<id>/), after the component moves and the gun stretch and before the uniform scale: +X is the vehicle's left, +Y up,
the nose at -Z."""

SPECS = {
    'm1a1_abrams': {
        'id': 'm1a1_abrams',
        'template': 'm1a2_abrams_sep_v2',
        'hitbox': (4.07, 2.42),
        'geometry': {
            # the commander's weapon station: the cupola ring turns (yaw, about the cupola centre), the M2 elevates
            # on its cradle at the top of the pintle post (pitch); same bone chain as the M1A2
            'newBones': {
                'passengerWeaponStation': {'parent': 'turret', 'pivot': [-10.7, 50.4, 16.6]},
                'passengerWeaponStationYaw': {'parent': 'passengerWeaponStation', 'pivot': [-10.7, 50.4, 16.6]},
                'passengerWeaponStationPitch': {'parent': 'passengerWeaponStationYaw', 'pivot': [-10.7, 56.0, 4.8]},
            },
            'moves': [
                # M2 receiver, barrel, ammunition box, belt, spade grips and sight
                {'from': 'turret', 'to': 'passengerWeaponStationPitch', 'box': [[-14.5, 53.5, -10.5], [-1.5, 60.0, 12.4]]},
                # the front of the M2 barrel the source still parented to the main gun
                {'from': 'barell', 'to': 'passengerWeaponStationPitch', 'box': [[-14.5, 56.5, -23.0], [-10.0, 60.0, 4.5]]},
                # pintle post, cupola, periscopes and hatch ring (the fixed base ring stays on the turret)
                {'from': 'turret', 'to': 'passengerWeaponStationYaw', 'box': [[-21.0, 49.2, 0.7], [-1.2, 56.1, 26.3]]},
            ],
            # the right road wheels sat 2.1 px outboard and the right track 1.9 px inboard of the left side's mirror
            'mirrorRunningGear': 'L',
            # the source gun stopped at the hull front: the tube is lengthened so the muzzle sits where the M1A2's
            # (same 120 mm L44 M256) does
            'stretch': {'bone': 'barell', 'zBelow': -67.0, 'muzzleZ': -103.0},
            'scale': 'templateHullLength',
        },
        'attachments': {
            # gunner's primary sight: on top of the GPS housing, right front of the cupola
            'gunner1_camera': {'parent': 'Turret', 'at': [-16.0, 54.4, -13.5]},
            'gunner1_zoom': {'parent': 'Turret', 'at': [-16.0, 54.4, -13.5]},
            # commander at the M2, head out of the cupola, behind the receiver
            'gunner2_camera': {'parent': 'WeaponStationBarrel', 'at': [-10.7, 60.5, 17.0]},
            'main_muzzle': {'parent': 'Barrel', 'at': [0.55, 40.75, 'tip']},
            'coax_muzzle': {'parent': 'Barrel', 'at': [-5.6, 44.3, -46.5]},
            'hmg_muzzle': {'parent': 'WeaponStationBarrel', 'at': [-10.7, 57.35, -22.5]},
        },
        'seats': [
            {'frame': 'Turret', 'at': [-14.0, 40.0, -4.0]},      # gunner, under the GPS
            {'frame': 'Turret', 'at': [-10.7, 42.0, 16.6]},      # commander, under the cupola
            {'frame': 'Turret', 'at': [10.6, 40.0, 7.7]},        # loader, left
        ],
        'muzzleRename': {'roof_coax_muzzle': 'coax_muzzle'},
        'dropData': ['RoofCoaxPitch'],
        'rounds': {'m829a2_apfsds': {'id': 'm829a1_apfsds', 'combat': {'PenetrationMm': 600}}},
        'armorSet': {'ap_penetration_mm': 600},
        'weaponSet': {'Cannon': {'Velocity': 78.75}},     # M829A1, 1575 m/s
        'lang': {'weapon.berts_vehicle_pack.m829a1_apfsds': 'M829A1 APFSDS'},
    },
}
