"""Cameras of the twelve helicopters: third-person chase views and the crew's sight views.

Chase views. Every seat that uses the aircraft camera gets AircraftCameraPos [0, up, -back]: centred, 0.4 of the
airframe's length (rotor disc included) behind its tail, and high enough to look down 12 degrees at the middle of the
airframe. VehicleCameraResolver keeps a helicopter's chase camera level (heading and free-look only, no body pitch)
and aims it at that point. The other seats (gunners, passengers) orbit the player with ThirdPersonCameraPos
[0, up, extra distance], centred and scaled to the airframe as well.

Sight views. The crew member who aims the guided weapons (and the Ka-50 pilot) gets a sight: holding the vehicle zoom
moves his view from his eye in the cockpit to the real sensor (TADS, TSU, Shkval, the Mi-24 missile sight, the Tiger's
mast sight...), measured on the airframe model. SIGHTS below writes, per helicopter:
  * Attachments[<name>] = {Parent: Vehicle, Position: the sensor window (just ahead of its glass), Direction: forward};
  * the seat's CameraPos ZoomEyeAttachment / ZoomDirectionAttachment = <name> (EyeAttachment, the head view in the
    cockpit, is left alone);
  * CameraPos ZoomYawRange / ZoomPitchRange: the sensor's field of regard, [min, max] degrees from the airframe's
    forward axis, yaw positive to the right, pitch positive up. VehicleEntity holds the zoomed look inside it; the
    head view stays free.
ArmoredVehicleEntity gives a helicopter seat whose ZoomEyeAttachment differs from its EyeAttachment the
EMPLACEMENT_OPTIC role, so the zoomed camera moves to that anchor (the gunner seats with a hull weapon station already
had a seat-authored zoom; their station keeps its own CameraAttachment, which must stay on the station). The guns keep
their own traverse limits: the sight can look past them, the gun stops at its stops.

    python3 tools/heli_armament/camera.py [--root DIR] [vid ...]      # after helicopters.py and build.py

--root points at another copy of bvp/src/generated/resources (a scratch copy to try a change on).
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

# Crew sights. pos: vehicle-local blocks (+X left, +Y up, +Z forward), measured on custom_geo/<vid>.geo.json (the
# sensor window, with the eye ~0.1 ahead of the glass so the view starts outside the airframe). yaw / pitch: field of
# regard, degrees (yaw positive right, pitch positive up).
SIGHTS = {
    # TADS turret on the nose, under the PNVS: window faces at z 5.05, centred between the two windows at y 1.67.
    # Field of regard +-120 az, +30/-60 el (the figures commonly published for the AN/ASQ-170 TADS).
    'ah_64d': dict(seat=1, name='gunner_sight', pos=[0.0, 1.67, 5.15], yaw=(-120, 120), pitch=(-60, 30)),
    # M65 telescopic sight unit in the nose turret, windows at the nose tip (z 7.05, y 1.5-1.95); the M197 turret
    # is under the chin. +-110 az, +30/-60 el (figures commonly given for the M65 TSU; approximate).
    'ah_1f': dict(seat=1, name='gunner_sight', pos=[0.0, 1.75, 7.2], yaw=(-110, 110), pitch=(-60, 30)),
    # The AH-1G gunner aims the M28 turret with the pantograph-mounted reflex sight he pulls in front of his face:
    # the sight view is his eye at the sight glass, 0.3 forward of the head view. The commonly given M28 turret
    # coverage, about +-107 az, +17.5/-50 el (approximate), which the sight follows.
    'ah_1g_cobra': dict(seat=1, name='gunner_sight', pos=[0.0, 3.17, 4.55], yaw=(-107, 107), pitch=(-50, 17.5)),
    # Mi-24V: the 9Sh121 Raduga-Sh missile sight sits in the fairing on the port (left) chin, the Shturm command
    # antenna on the starboard one (the Mi-24D has them the other way round; from photos of Mi-24V/P noses). The
    # model's chin has no separate fairing, so the eye is ahead of the port cheek face beside the USPU-24 turret.
    # +-60 az, +20/-60 el: the turret's coverage (judgement, no published figure for the sight).
    'mi24v': dict(seat=1, name='gunner_sight', pos=[0.3, 0.92, 6.42], yaw=(-60, 60), pitch=(-60, 20)),
    # Mi-24A: no chin sight; the operator in the glazed nose has the KPS-53AV periscopic sight head on top of his
    # panel (the post at z 7.67-7.76, y 2.82-3.04); the eye is just ahead of its top, 0.15 left of the glazing's centre
    # bar and above the frame arch. Coverage as the A-12.7 mount.
    'mi_24a': dict(seat=1, name='gunner_sight', pos=[0.15, 3.08, 7.85], yaw=(-60, 60), pitch=(-60, 20)),
    # Mi-24D: Raduga-F sight pod on the starboard (right) chin, window x -0.81..-0.44, y 0.65-0.9, face at z 7.97;
    # the black Falanga radome is the port pod. +-60 az, +20/-60 el as the USPU-24 (judgement).
    'mi_24d': dict(seat=1, name='gunner_sight', pos=[-0.62, 0.78, 8.1], yaw=(-60, 60), pitch=(-60, 20)),
    # Mi-28N: the operator's sight ball under the nose drum, above the NPPU-28 gun (windows at y 2.65-2.82, ball
    # front at z 6.66). +-110 az as the gun turret, +20/-60 el (judgement).
    'mi28n': dict(seat=1, name='gunner_sight', pos=[0.0, 2.75, 6.76], yaw=(-110, 110), pitch=(-60, 20)),
    # Tiger UHT: the Osiris mast-mounted sight above the rotor head (box x +-0.67, y 6.45-7.29, front face z 0.42).
    # +-120 az, +30/-45 el (judgement: a mast sight looks over the rotor disc, not down through it).
    'eurocopter_tiger': dict(seat=1, name='gunner_sight', pos=[0.0, 6.88, 0.55], yaw=(-120, 120), pitch=(-45, 30)),
    # Ka-50: the I-251 Shkval window in the flat nose (x +-0.3, y 1.6-2.1, face at z 7.38). Shkval +-35 az, +15/-80 el
    # (the published Shkval field of regard, as given e.g. in Eagle Dynamics' DCS Ka-50 manual).
    'ka50': dict(seat=0, name='shkval_sight', pos=[0.0, 1.85, 7.5], yaw=(-35, 35), pitch=(-80, 15)),
}


def set_root(root):
    global GEN
    GEN = os.path.abspath(root)


def chase(veh, vid):
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


def sight(veh, vid):
    s = SIGHTS.get(vid)
    if not s:
        return
    cam = veh['Seats'][s['seat']]['CameraPos']
    if cam.get('EyeAttachment') == s['name']:
        raise SystemExit(f'{vid}: the sight {s["name"]} must not be the head-view eye')
    veh.setdefault('Attachments', {})[s['name']] = {'Parent': 'Vehicle', 'Position': list(s['pos']),
                                                    'Direction': [0, 0, 1]}
    cam['ZoomEyeAttachment'] = s['name']
    cam['ZoomDirectionAttachment'] = s['name']
    cam['ZoomYawRange'] = list(s['yaw'])
    cam['ZoomPitchRange'] = list(s['pitch'])


def apply(vid):
    path = os.path.join(GEN, 'data', 'berts_vehicle_pack', 'sbw', 'vehicles', vid + '.json')
    raw = open(path, encoding='utf-8').read()
    veh = json.loads(raw)
    chase(veh, vid)
    sight(veh, vid)
    with open(path, 'w', encoding='utf-8', newline='\n') as f:
        f.write(json.dumps(veh, indent=2, ensure_ascii=False) + ('\n' if raw.endswith('\n') else ''))


def main(argv):
    if '--root' in argv:
        root = argv[argv.index('--root') + 1]
        argv = [a for a in argv if a not in ('--root', root)]
        set_root(root)
    for v in argv or EXTENTS:
        apply(v)
        print('camera', v, 'sight ' + SIGHTS[v]['name'] if v in SIGHTS else '')


if __name__ == '__main__':
    main(sys.argv[1:])
