"""Driver cameras for ground vehicles whose driver seat had no eye (only the simulated third-person camera).

    python3 tools/vehgen/driver_cameras.py [--check]

For each entry in driver_cameras.json the driver seat gets a first-person eye at the driver's real station, the same
way as the Toyota and UAZ drivers: an attachment `driver_camera` (parent VehicleCustomPitch) and a CameraPos that uses
it, with the hidden body placed 1.62 blocks under the eye. `eye` is in data blocks (+X left, +Y up, nose +Z); the
notes say where the driver sits in the real vehicle and how far the eye is lifted above the hull roof for visibility.
"""
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
DATA = os.path.join(HERE, '..', '..', 'bvp', 'src', 'generated', 'resources', 'data', 'berts_vehicle_pack', 'sbw',
                    'vehicles')
SITTING_EYE = 1.62


def camera():
    return {"UseSimulate3P": True, "Simulate3PPos": [6.6, 1.1], "UseFixedCameraPos": True,
            "Position": [0, 0, 0], "ZoomPosition": [0, 0, 0],
            "EyeAttachment": "driver_camera", "ZoomEyeAttachment": "driver_camera",
            "DirectionAttachment": "VehicleCustomPitch", "ZoomDirectionAttachment": "VehicleCustomPitch",
            "CameraMode": "PLAYER_LOOK_AIM", "AimCameraMode": "PLAYER_LOOK_AIM"}


def apply(d, spec):
    eye = [round(float(v), 5) for v in spec['eye']]
    seat = d['Seats'][spec.get('seat', 0)]
    if seat.get('Weapons'):
        raise SystemExit(f"{d['ID']}: seat {spec.get('seat', 0)} has weapons; its camera is the gunner's")
    seat['Transform'] = 'Vehicle'
    seat['Position'] = [eye[0], round(eye[1] - SITTING_EYE, 5), eye[2]]
    seat['CameraPos'] = camera()
    seat['BodyAttachment'] = 'Vehicle'
    d.setdefault('Attachments', {})['driver_camera'] = {"Parent": "VehicleCustomPitch", "Position": eye,
                                                        "Direction": [0, 0, 1]}
    return d


def main(argv):
    check = '--check' in argv
    specs = json.load(open(os.path.join(HERE, 'driver_cameras.json')))
    bad = 0
    for vid, spec in specs.items():
        if vid.startswith('_'):
            continue
        path = os.path.join(DATA, f'{vid}.json')
        text = open(path).read()
        d = apply(json.loads(text), spec)
        new = json.dumps(d, indent=2) + ('\n' if text.endswith('\n') else '')
        if new != text:
            if check:
                bad += 1
                print(f'{vid}: driver camera not applied')
            else:
                open(path, 'w').write(new)
                print(f'{vid}: driver eye {spec["eye"]}')
    return 1 if bad else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
