#!/usr/bin/env python3
"""Renders a helicopter's cockpit with its flight controls posed the way the aircraft rig poses them in the game
(AircraftRigAnimator: cyclic and pedals from the pilot's inputs, collective and throttle levers from the flight
snapshot), to check that each control moves the right way about the right pivot.

Usage: helicopter_controls.py VID OUT.png [--seat N] [--pose forward|back|left|right|pedal_right|pedal_left|
                                                         collective|throttle|neutral] [--look yaw,pitch] [--fov deg]

The rig's axes are in file pixels; the game mirrors X and applies (a0, -a1, -a2) in its frame, which is a turn
about (-a0, -a1, a2) in vehicle-local blocks (x left, y up, z forward).
"""
import json, math, os, sys
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import eyeview as EV  # noqa: E402

POSES = {   # elevator (aft stick +), aileron (right +), rudder (right +), collective, throttle
    'neutral': (0, 0, 0, 0, 0), 'forward': (-1, 0, 0, 0, 0), 'back': (1, 0, 0, 0, 0), 'left': (0, -1, 0, 0, 0),
    'right': (0, 1, 0, 0, 0), 'pedal_right': (0, 0, 1, 0, 0), 'pedal_left': (0, 0, -1, 0, 0),
    'collective': (0, 0, 0, 1, 0), 'throttle': (0, 0, 0, 0, 1),
}


def rotation(axis, deg):
    a = np.array(axis, float); a /= np.linalg.norm(a)
    t = math.radians(deg); c, s = math.cos(t), math.sin(t)
    K = np.array([[0, -a[2], a[1]], [a[2], 0, -a[0]], [-a[1], a[0], 0]])
    return np.eye(3) * c + s * K + (1 - c) * np.outer(a, a)


def pose(geo, rig, controls):
    elevator, aileron, rudder, collective, throttle = controls
    bones = {b['name']: b for b in geo['minecraft:geometry'][0]['bones']}
    turns = {}
    for sf in rig.get('Surfaces', []):
        w = sf['ControlWeights']
        u = w['ElevatorUp'] * elevator + w['RightRoll'] * aileron + w['RudderRight'] * rudder
        turns[sf['Bone']] = (sf, max(-1, min(1, u)) * sf['MaxDeflectionDegrees'])
    for fl in rig.get('Flaps') or []:
        u = {'PRESENTED_COLLECTIVE': collective, 'PRESENTED_THROTTLE': throttle}.get(fl['Input'], 0)
        turns[fl['Bone']] = (fl, u * fl['MaxDeflectionDegrees'] * fl.get('AngleSign', 1))

    def chain(name):
        out = []
        while name in bones:
            if name in turns:
                out.append(turns[name])
            name = bones[name].get('parent')
        return out

    for name, b in bones.items():
        pm = b.get('poly_mesh')
        if not pm or not pm.get('polys'):
            continue
        ch = chain(name)
        if not ch:
            continue
        P = np.array(pm['positions'], float) * [1, 1, -1] / 16.0
        for entry, deg in ch:                       # innermost first, then each parent
            a = entry['Axis']
            R = rotation([-a[0], -a[1], a[2]], deg)
            piv = np.array(entry['Pivot'], float) * [1, 1, -1] / 16.0
            P = (P - piv) @ R.T + piv
        pm['positions'] = (P * [16, 16, -16]).round(4).tolist()
    return geo


def main(argv):
    vid, out = argv[0], argv[1]
    opt = lambda k, d: argv[argv.index(k) + 1] if k in argv else d
    seat = int(opt('--seat', 0))
    which = opt('--pose', 'neutral')
    yaw, pitch = (float(v) for v in opt('--look', '0,-55').split(','))
    fov = float(opt('--fov', 95))
    geo = json.load(open(os.path.join(EV.GEO, vid + '.geo.json')))
    asset = json.load(open(os.path.join(EV.ASSET, vid + '.json')))
    data = json.load(open(os.path.join(EV.DATA, vid + '.json')))
    geo = pose(geo, asset['AircraftRig'], POSES[which])
    tmp = out + '.geo.json'
    json.dump(geo, open(tmp, 'w'))
    s = data['Seats'][seat]
    name = s.get('CameraPos', {}).get('EyeAttachment')
    eye = [float(v) for v in opt('--eye', '').split(',')] if '--eye' in argv else data['Attachments'][name]['Position']
    EV.render(vid, out, eye=np.array(eye) + [0, 0.05, -0.05], yaw=yaw, pitch=pitch, fov=fov, size=(700, 480),
              geo_path=tmp)
    os.remove(tmp)


if __name__ == '__main__':
    main(sys.argv[1:])
