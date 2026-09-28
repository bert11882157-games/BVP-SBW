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
            # gunner's primary sight: at the GPS window, front face of the housing right of the cupola (r46: from
            # on top of the housing the commander's M2 barrel crossed the upper left of the view)
            'gunner1_camera': {'parent': 'Turret', 'at': [-18.5, 51.5, -15.6]},
            'gunner1_zoom': {'parent': 'Turret', 'at': [-18.5, 51.5, -15.6]},
            # commander at the M2, head out of the cupola, eye over the receiver and sight
            'gunner2_camera': {'parent': 'WeaponStationBarrel', 'at': [-10.7, 62.5, 17.0]},
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
    'type_90': {
        'id': 'type_90',
        'template': 'leopard_2a4',
        'hitbox': (3.9, 2.4),
        'geometry': {
            # the roof M2HB on its pintle, left of the gun: the post turns (yaw), the gun elevates on the post top
            'newBones': {
                'passengerWeaponStationYaw': {'parent': 'turret', 'pivot': [3.0, 49.3, -14.5]},
                'passengerWeaponStationPitch': {'parent': 'passengerWeaponStationYaw', 'pivot': [3.0, 53.8, -14.5]},
            },
            'moves': [
                # receiver, barrel, ammunition box, grips, sight
                {'from': 'turret', 'to': 'passengerWeaponStationPitch', 'box': [[-2.0, 52.8, -38.5], [9.0, 58.1, 3.5]]},
                # pintle post and its brace
                {'from': 'turret', 'to': 'passengerWeaponStationYaw', 'box': [[0.5, 49.0, -19.0], [5.5, 54.3, -9.0]]},
            ],
            'scale': 1.0,
        },
        'attachments': {
            # gunner's sight: right front of the turret roof, just over the roof
            'gunnerCamera': {'parent': 'Turret', 'at': [-12.0, 49.0, -20.0]},
            'driverCamera': {'parent': 'Vehicle', 'at': [8.8, 33.12, -41.6]},
            'driverSeat': {'parent': 'Vehicle', 'at': [8.8, 19.2, -38.0]},
            'gunnerSeat': {'parent': 'Turret', 'at': [-12.0, 35.0, -10.0]},
            'turretPivot': {'parent': 'Vehicle', 'at': 'pivot:turret'},
            'barrelPivot': {'parent': 'Turret', 'at': 'pivot:barell'},
            'mainMuzzle': {'parent': 'Barrel', 'at': [1.7, 39.3, -110.0]},
            # Type 74 coax, left of the gun
            'coaxMuzzle': {'parent': 'Barrel', 'at': [8.1, 40.55, -36.1]},
            'hmgMuzzle': {'parent': 'WeaponStationBarrel', 'at': [2.95, 55.45, -38.4]},
            'hmgSeat': {'parent': 'Turret', 'at': [3.0, 34.0, 7.0]},
            'hmgCamera': {'parent': 'WeaponStation', 'at': [3.0, 59.5, 6.0]},
        },
        'seats': [
            {'frame': 'Turret', 'at': [-12.0, 35.0, -10.0]},     # gunner (drives, the pack's tank convention)
            {'frame': 'Turret', 'at': [3.0, 34.0, 7.0]},         # at the roof M2HB
        ],
        'weaponFrom': {'PassengerMachineGun': ('m1a2_abrams_sep_v2', 'PassengerMachineGun')},
        'dropRounds': ['dm13_apfsds'],
        'rounds': {'dm23_apfsds': {'id': 'jm33_apfsds', 'combat': {'PenetrationMm': 481}},
                   'dm12_heat_fs': {'id': 'jm12a1_heat_fs', 'combat': {'PenetrationMm': 480}}},
        'defaultFromFirst': True,
        # autoloader, 4.0 s (War Thunder)
        'dataSet': {'HasAutoloader': True},
        'weaponSet': {'Cannon': {'EmptyReloadTime': 80, 'Velocity': 82.0},
                      'MachineGun': {'Name': 'weapon.berts_vehicle_pack.type_90_machinegun'}},
        'armorSet': {'ap_penetration_mm': 481},
        'lang': {'weapon.berts_vehicle_pack.jm33_apfsds': 'JM33 APFSDS',
                 'weapon.berts_vehicle_pack.jm12a1_heat_fs': 'JM12A1 HEAT-FS',
                 'weapon.berts_vehicle_pack.type_90_machinegun': '7.62 mm Type 74'},
    },
    '9k22_tunguska': {
        'id': '9k22_tunguska',
        'template': 'tunguska',
        'hitbox': (3.67, 4.46),
        'geometry': {
            # the early 2K22 (two 9M311 per side): the gun block, the two missile pods and the tracking radar were
            # one bone; split like the 2K22M template (pods and radar elevate with the guns, FittedGroundRig)
            'newBones': {
                'pod_L': {'parent': 'turret', 'pivot': [21.2, 48.0, 18.35]},
                'pod_R': {'parent': 'turret', 'pivot': [-21.2, 48.0, 18.35]},
                'tracking_radar': {'parent': 'turret', 'pivot': [0.0, 47.3, -27.5]},
            },
            'moves': [
                {'from': 'barell', 'to': 'pod_L', 'box': [[17.5, 43.0, -31.0], [27.0, 56.0, 31.0]],
                 'centreX': [18.5, 30.0]},
                {'from': 'barell', 'to': 'pod_R', 'box': [[-27.0, 43.0, -31.0], [-17.5, 56.0, 31.0]],
                 'centreX': [-30.0, -18.5]},
                {'from': 'barell', 'to': 'tracking_radar', 'box': [[-7.6, 42.4, -35.7], [7.6, 57.5, -26.3]]},
            ],
            # the guns elevate about the trunnions at the rear of the gun block, like the template's
            'setPivots': {'barell': [0.0, 48.0, 18.35]},
            # the left road wheels (16 px wide, the right 7) and track links (2.9 px, the right 8.2) were malformed
            'mirrorMeshes': 'R',
            'symmetrizeRunningGear': True,
            'scale': 'templateHullLength',
        },
        'rig': ['pod_L', 'pod_R', 'tracking_radar'],
        'attachments': {
            'fitted_pod_L': {'parent': 'Turret', 'at': 'pivot:pod_L', 'RotationChannel': 'TURRET_PITCH'},
            'fitted_pod_R': {'parent': 'Turret', 'at': 'pivot:pod_R', 'RotationChannel': 'TURRET_PITCH'},
            'fitted_tracking_radar': {'parent': 'Turret', 'at': 'pivot:tracking_radar',
                                      'RotationChannel': 'TURRET_PITCH'},
            'gunnerCamera': {'parent': 'Turret', 'at': [7.97, 60.6, 0.4]},
            'driverCamera': {'parent': 'Vehicle', 'at': [10.4, 40.32, -44.0]},
            'driverSeat': {'parent': 'Vehicle', 'at': [10.4, 27.5, -40.0]},
            'gunnerSeat': {'parent': 'Turret', 'at': [5.74, 43.3, 2.3]},
            'turretPivot': {'parent': 'Vehicle', 'at': 'pivot:turret'},
            'barrelPivot': {'parent': 'Turret', 'at': 'pivot:barell'},
            'mainMuzzle': {'parent': 'Barrel', 'at': [0.0, 49.9, -39.6]},
            'cannonMuzzle_1': {'parent': 'Barrel', 'at': [14.9, 49.9, -39.6]},
            'cannonMuzzle_2': {'parent': 'Barrel', 'at': [16.35, 49.9, -39.1]},
            'cannonMuzzle_3': {'parent': 'Barrel', 'at': [-14.9, 49.9, -39.6]},
            'cannonMuzzle_4': {'parent': 'Barrel', 'at': [-16.35, 49.9, -39.1]},
            'missileMuzzle_1': {'parent': 'fitted_pod_L', 'at': [21.2, 49.35, -29.8]},
            'missileMuzzle_2': {'parent': 'fitted_pod_L', 'at': [21.2, 45.3, -29.8]},
            'missileMuzzle_3': {'parent': 'fitted_pod_R', 'at': [-21.2, 49.35, -29.8]},
            'missileMuzzle_4': {'parent': 'fitted_pod_R', 'at': [-21.2, 45.3, -29.8]},
        },
        'seats': [
            {'frame': 'Turret', 'at': [5.74, 43.3, 2.3]},
        ],
        'muzzles': {'Missile': ['missileMuzzle_1', 'missileMuzzle_2', 'missileMuzzle_3', 'missileMuzzle_4']},
        'weaponSet': {'Missile': {'Magazine': 4}},
    },
    '9p149_shturm': {
        'id': '9p149_shturm',
        'template': '9p148',
        'hitbox': (2.9, 2.6),
        'geometry': {
            # MT-LB chassis: wheels, sprockets and tracks of both sides were 16-18 px off to one side (left wheels
            # inside the hull, right tracks inside the hull); flush on the hull side, which is the MT-LB's track line
            'symmetrizeRunningGear': True,
            'wheelOuter': 28.5,
            'scale': 1.0,
        },
        # the launcher arm turns and elevates about its base (the model's turret and barrel pivots)
        'attachments': {
            'driverCamera': {'parent': 'Vehicle', 'at': [9.6, 36.8, -36.8]},
            # operator's 1K11 sight: commander's cupola, front right of the cab
            'gunnerCamera': {'parent': 'Vehicle', 'at': [-10.0, 47.0, -40.0]},
            'gunnerSeat': {'parent': 'Vehicle', 'at': [-10.0, 21.0, -40.0]},
            'passengerSeat_1': {'parent': 'Vehicle', 'at': [9.6, 10.9, -36.8]},
            'turretPivot': {'parent': 'Vehicle', 'at': 'pivot:turret'},
            'barrelPivot': {'parent': 'Turret', 'at': 'pivot:barell'},
            'missileMuzzle_1': {'parent': 'Barrel', 'at': [-13.0, 49.7, 10.2]},
        },
        'seats': [
            # the operator's seat is in the turret frame: a hull-frame seat with a fixed head cannot slew the launcher
            {'frame': 'Turret', 'at': [-10.0, 21.0, -40.0]},            # operator (launcher)
            {'frame': 'Vehicle', 'at': [9.6, 10.9, -36.8]},             # driver, front left
        ],
        # Shturm-S: 9M114 (radio command), 12 in the autoloader, one on the rail
        'weaponFrom': {'Missile': ('mi24v', 'PilotMissile')},
        'muzzles': {'Missile': ['missileMuzzle_1']},
        'weaponSet': {'Missile': {'Magazine': 12, 'RPM': 6}},
        'obb': [
            {'bone': 'hull'},
            {'box': [[-8.0, 12.0, -50.0], [8.0, 32.0, -30.0]], 'part': 'MainEngine'},
            {'bone': 'barell', 'transform': 'Barrel', 'part': 'Turret'},
        ],
        # the MT-LB's tracked running data stays (the template is the wheeled BRDM-2 9P148)
        'keepData': ['EngineType', 'EngineInfo', 'Mass', 'MaxEnergy', 'EngineSound', 'UpStep', 'RotateOffsetHeight',
                     'ThirdPersonCameraPos', 'InertiaRotateRate', 'ParkingBrakeWhenUnoccupied', 'TurretTurnSpeed',
                     'TurretPitchRange', 'Type', 'HUDColor'],
    },
    't14_armata': {
        'id': 't14_armata',
        'template': 't90m',
        'hitbox': (3.6, 2.4),
        'geometry': {
            # the gun pitched about a point 5 px under its bore, ahead of the turret face: trunnion inside the mantlet
            'setPivots': {'barell': [0.0, 40.9, -20.0]},
            # the left sprocket and idler sat inside the hull (x 5-16 px) and the left track links were 2.7 px
            # narrower than the right: the left running gear is rebuilt as the mirror of the right
            'mirrorMeshes': 'R',
            'symmetrizeRunningGear': True,
            'scale': 1.0,
        },
        'attachments': {
            # gunner's sight head on the turret roof, front right (the crew sits in the hull capsule)
            'gunner1_camera': {'parent': 'Turret', 'at': [-10.0, 54.0, -16.0]},
            'gunner1_zoom': {'parent': 'Turret', 'at': [-10.0, 54.0, -16.0]},
            'main_muzzle': {'parent': 'Barrel', 'at': [0.0, 40.9, -126.8]},
            # PKTM coax, right of the gun in the mantlet
            'coax_muzzle': {'parent': 'Barrel', 'at': [-5.0, 42.0, -32.2]},
        },
        'seats': [
            # the gunner aims the turret, so the seat is in the turret frame (a hull-frame seat cannot traverse)
            {'frame': 'Turret', 'at': [0.0, 24.0, -44.8], 'fromTemplate': 0},      # gunner, capsule centre
            {'frame': 'Vehicle', 'at': [-8.0, 24.0, -44.8], 'fromTemplate': 2},    # commander, capsule right
        ],
        # the model has no remote weapon station on the turret: the T-90M's commander HMG station is left out
        'dropWeapons': ['PassengerMachineGun'],
        'dropData': ['PassengerWeaponStationControllerIndex', 'PassengerWeaponStationBinding',
                     'PassengerWeaponStationPos', 'PassengerWeaponStationBarrelPos', 'PassengerWeaponStationTurnSpeed',
                     'PassengerWeaponStationPitchRange', 'PassengerWeaponStationYawRange'],
        'dataSet': {'RemoteWeaponStation': False},
        'weaponSet': {'Cannon': {'Name': 'weapon.berts_vehicle_pack.t14_cannon'}},
        # Malachit ERA on the hull only; the unmanned turret carries none
        'armorDrop': {'explosive_reactive_armor': 'turret'},
        'lang': {'weapon.berts_vehicle_pack.t14_cannon': '125 mm 2A82-1M'},
    },
}
