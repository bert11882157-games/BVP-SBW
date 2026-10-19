"""Third-person chase cameras of the twelve helicopters.

Every seat that uses the aircraft camera gets AircraftCameraPos [0, up, -back]: centred, 0.4 of the airframe's length
(rotor disc included) behind its tail, and high enough to look down 12 degrees at the middle of the airframe.
VehicleCameraResolver keeps a helicopter's chase camera level (heading and free-look only, no body pitch) and aims
it at that point. The other seats (gunners, passengers) orbit the player with ThirdPersonCameraPos [0, up, extra
distance], centred and scaled to the airframe as well.

    python3 tools/heli_armament/camera.py [vid ...]      # after helicopters.py and build.py
"""
import json, math, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
GEN = os.path.join(HERE, '..', '..', 'bvp', 'src', 'generated', 'resources')
LOOK_DOWN = 12.0

# model extents (vehicle-local blocks): tail end z, nose end z, top y; measured on custom_geo/<vid>.geo.json
EXTENTS = {
    'ah_64d': (-7.3, 11.1, 6.3), 'ah_1f': (-10.1, 10.4, 5.8), 'ah_1g_cobra': (-9.1, 12.3, 6.6),
    'ah_6j': (-7.5, 6.6, 4.6), 'ka50': (-9.5, 11.6, 7.2), 'mi28n': (-12.9, 17.7, 7.7), 'mi24v': (-9.4, 12.4, 6.2),
    'mi_24a': (-13.0, 14.2, 9.4), 'mi_24d': (-13.0, 14.2, 9.4), 'mi_26': (-19.9, 30.7, 17.0),
    'ch_46e': (-8.6, 16.7, 5.6), 'eurocopter_tiger': (-7.0, 12.3, 7.3),
}


def apply(vid):
    path = os.path.join(GEN, 'data', 'berts_vehicle_pack', 'sbw', 'vehicles', vid + '.json')
    raw = open(path, encoding='utf-8').read()
    veh = json.loads(raw)
    tail, nose, top = EXTENTS[vid]
    length = nose - tail
    back = tail - 0.4 * length
    # the resolver aims at the middle of the airframe (half its height), measured from the rotation pivot
    target = max(0.0, top / 2 - veh.get('RotateOffsetHeight', 0))
    up = target + math.tan(math.radians(LOOK_DOWN)) * -back
    for seat in veh['Seats']:
        cam = seat.get('CameraPos')
        if cam and cam.get('UseAircraftCamera'):
            cam['AircraftCameraPos'] = [0, round(up, 2), round(back, 2)]
    veh['ThirdPersonCameraPos'] = [0, round(0.25 * length, 1), round(0.5 * length, 1)]
    with open(path, 'w', encoding='utf-8', newline='\n') as f:
        f.write(json.dumps(veh, indent=2, ensure_ascii=False) + ('\n' if raw.endswith('\n') else ''))


if __name__ == '__main__':
    for v in sys.argv[1:] or EXTENTS:
        apply(v)
        print('camera', v)
