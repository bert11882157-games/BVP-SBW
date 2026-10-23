#!/usr/bin/env python3
"""CH-46E flight deck, built by hand for this model (heli_station.py stations): the pilot on the right and the
co-pilot on the left, side by side under the wide windscreen.

Measured off the model with the cuts and rays (metres from each pilot's eye at x -0.52 / +0.52; the model is close to
life size). The co-pilot's eye is main's (0.52, 2.10, 4.04); the pilot's (-0.5175, 2.0959, 4.0366) is the same on
both branches.
  * each pilot: the floor at y -1.00, the old seat's cushion at y -0.80 with its back 0.2-0.3 behind the eye, the
    centre pedestal 0.34 inboard below y -0.5, the side console 0.28 outboard; the old photo-textured panel 0.78-0.93
    ahead under a glareshield at y -0.20; the windscreen and the chin windows below the panel are the model's.
Built (the CH-46E's cockpit as photographed and described): for each pilot his own panel half with the attitude and
situation indicators, airspeed, altimeter, vertical speed, radar altimeter, the dual tachometer, the engines' gas
temperature, fuel and his caution panel, the glareshield over it, a crew seat, the cyclic, the collective and the
pedals. The centre pedestal stays the model's. No canopy is made: the windows keep their frames and get glass.

Run on the committed originals (work/handbuilt/run_heli.ps1 restores them first).
Usage: python3 tools/cockpit_models/ch_46e.py [--dry-run]
"""
import os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import cockpit_lib as L   # noqa: E402
import handbuilt as H     # noqa: E402
import free_atlas as FA   # noqa: E402
import heli_station as HS  # noqa: E402

VID = 'ch_46e'
L.INTERIOR_BONES.add('hull')
FA.also_used(VID, 'ad56207c', 'suspended_')

COLOURS = dict(panel=(40, 42, 44), panel_d=(28, 29, 30), wall=(68, 72, 72))
FLOOR = (46, 48, 48)

LAYOUT = [
    ('adi',      0, -0.050, -0.330, ('game', 'ATTITUDE', 0.085), 'attitude indicator'),
    ('hsi',      0, -0.050, -0.470, ('game', 'HEADING', 0.080), 'horizontal situation indicator'),
    ('vvi',      0,  0.080, -0.330, ('game', 'VVI', 0.062), 'vertical speed'),
    ('radalt',   0,  0.080, -0.460, ('game', 'RADAR_ALT', 0.058), 'radar altimeter'),
    ('warning',  0,  0.000, -0.600, ('annunciator', 0.220, 0.055), 'caution panel'),
    ('ias',     -1,  0.080, -0.330, ('game', 'AIRSPEED', 0.062), 'airspeed'),
    ('alt',     -1,  0.080, -0.460, ('game', 'ALTIMETER', 0.062), 'altimeter'),
    ('clock',   -1,  0.080, -0.580, ('game', 'CLOCK', 0.045), 'clock'),
    ('rotor',    1,  0.080, -0.330, ('game', 'ENGINE_RPM', 0.062), 'dual tachometer'),
    ('egt',      1,  0.080, -0.460, ('game', 'EGT', 0.055), 'gas temperature'),
    ('fuel',     1,  0.080, -0.580, ('game', 'FUEL_QTY', 0.055), 'fuel quantity'),
]
CONSOLES = ([(0.34, 'LIGHTS', 'rotary'), (0.33, 'ICS', 'rotary'), (0.33, 'UHF', 'freq')],
            [(0.34, 'ELEC', 'toggles'), (0.33, 'CB', 'breakers'), (0.33, 'AFCS', 'lamps')])


def station(ac, seat):
    return dict(seat=seat, E=ac.eye(seat), S=1.0, colours=COLOURS, layout=LAYOUT, consoles=None,   # (no side consoles:
                #                                            the centre pedestal and the overhead carry them)
                lamp_palette='nato',
                PANEL_Z=0.80, LEAN=12, HINGE=0.20, WING=20, WALL_X=0.50, TOP_C=-0.230, TOP_W=-0.250,
                BOT_C=-0.700, BOT_W=-0.430, FRONT_Z=1.05, FRONT_Y_C=-0.220, FRONT_Y_W=-0.235, COAMING_X=0.50,
                CONSOLE_Y=-0.620, CONSOLE_IN=0.30, CONSOLE_OUT=0.36, CONSOLE_FRONT=0.40, CONSOLE_FOOT=-0.98,
                CONSOLE_BACK=-0.10, CUSHION_Y=-0.80, SEAT_Z=0.15, SEAT_FOOT=-0.98, BACK_TOP=0.06, SEAT_LEAN=10,
                seat_kind='armoured', seat_label='CH-46E', STICK_Z=0.45, STICK_FOOT=-1.00, PEDAL_Z=0.80,
                PEDAL_Y=-0.90, PEDAL_HANG=False, FLOOR_Y=-1.00, COLLECTIVE_X=0.25)


PEDESTAL_ROWS = [(0.16, 'UHF-1', 'freq'), (0.14, 'VHF', 'freq'), (0.14, 'TACAN', 'freq'), (0.16, 'AFCS', 'lamps'),
                 (0.12, 'IFF', 'rotary'), (0.14, 'ICS', 'rotary'), (0.14, 'LIGHTS', 'toggles')]


def pedestal(m, atlas, E0, E1):
    """the centre pedestal between the seats, built by hand: its top sloping up from behind the seats to under the panel
    halves (the radio, navigation, AFCS, IFF, intercom and lighting panels painted on it), its sides and front face down
    to the floor; the two speed-trim levers stand on its front"""
    import numpy as np
    X, Y, Z = np.array([1.0, 0, 0]), np.array([0, 1.0, 0]), np.array([0, 0, 1.0])
    c = (np.array(E0) + np.array(E1)) / 2                           # the centre line at the pilots' eye height and station
    hw = 0.17                                                       # half width (the seats' inboard sides 0.34 out)
    zb, zf = c[2] - 0.15, c[2] + 0.62                               # its back behind the seats, its front under the panels
    yb, yf, fl = c[1] - 0.66, c[1] - 0.52, c[1] - 0.99              # its top behind and in front, the floor
    top = atlas.paint(60, 300, HS.p_console(PEDESTAL_ROWS, False, COLOURS['panel']))
    q = [[c[0] - hw, yb, zb], [c[0] + hw, yb, zb], [c[0] + hw, yf, zf], [c[0] - hw, yf, zf]]
    m.face(q, [m.uv(top, 0, 0), m.uv(top, 1, 0), m.uv(top, 1, 1), m.uv(top, 0, 1)], normal=[0, 1, 0])
    wall = atlas.swatch(COLOURS['wall']); dark = atlas.swatch(COLOURS['panel_d'])
    for s in (1, -1):                                               # its sides, down to the floor
        x = c[0] + s * hw
        m.flat([[x, fl, zb], [x, fl, zf], [x, yf, zf], [x, yb, zb]], wall, normal=[s, 0, 0])
    m.flat([[c[0] - hw, fl, zf], [c[0] + hw, fl, zf], [c[0] + hw, yf, zf], [c[0] - hw, yf, zf]], dark, normal=[0, 0, 1])
    m.flat([[c[0] - hw, fl, zb], [c[0] + hw, fl, zb], [c[0] + hw, yb, zb], [c[0] - hw, yb, zb]], dark, normal=[0, 0, -1])
    metal, black = atlas.swatch((128, 132, 134)), atlas.swatch((24, 25, 26))
    up = np.array([0, 1.0, -0.15]); up /= np.linalg.norm(up)
    for s in (1, -1):                                               # the speed-trim levers near its front
        foot = np.array([c[0] + s * 0.06, yf - 0.01 + (yb - yf) * 0.12, zf - 0.09])
        m.box(foot + up * 0.06, (X, up, np.cross(X, up)), (0.006, 0.06, 0.006), metal)
        m.box(foot + up * 0.125, (X, up, np.cross(X, up)), (0.016, 0.014, 0.016), black)


def main(argv):
    dry = '--dry-run' in argv
    ac = L.Aircraft(VID)
    E0, E1 = ac.eye(0), ac.eye(1)
    boxes = []
    for E in (E0, E1):
        boxes += [(E, (-0.30, -1.00, -0.40), (0.30, 0.40, 0.62), 0.30, -1.00),   # the old seat and controls
                  (E, (-0.55, -1.00, 0.55), (0.55, -0.15, 1.25), 0.55, -0.15),   # the old panel under its glareshield
                  (E, (-0.55, -0.15, 0.55), (0.55, 0.05, 1.25), 0.55, -0.15)]    # and the glareshield's top
    boxes.append((E0, (0.30, -1.00, -0.45), (0.74, -0.40, 1.10), 0.74, -0.40))   # the old centre pedestal
    removed = {'old interior': HS.remove_old(ac, boxes)}
    atlas = H.A.Atlas(VID)
    removed['floor faces repainted'] = HS.repaint_floor(ac, atlas, FLOOR, [
        (E0 + [-0.4, -1.12, -0.4], E0 + [0.4, -0.90, 1.0]), (E1 + [-0.4, -1.12, -0.4], E1 + [0.4, -0.90, 1.0])])
    m = H.Mesh(atlas)
    gauges, displays = [], []
    for seat in (0, 1):
        g, d, h = HS.build(m, atlas, station(ac, seat))
        gauges += g; displays += d
    pedestal(m, atlas, E0, E1)
    pm, line = HS.install(ac, m, gauges, displays, [], [E0, E1])
    print(f'{VID}: removed {removed}; new cockpit {len(pm["polys"])} faces, {len(gauges)} gauges, '
          f'{len(displays)} displays; {line}')
    if not dry:
        H.write(ac, atlas)


if __name__ == '__main__':
    main(sys.argv[1:])
