#!/usr/bin/env python3
"""Tiger cockpits, built by hand for this model (heli_station.py stations): the gunner in front, the pilot behind and
above him, as the vehicle's seats are (seat 0 behind, seat 1 in front).

Measured off the model with the cuts and rays (metres from each crewman's eye; the model is about 1.2 times life
size). The gunner's eye is main's (0, 2.90, 3.65); the pilot's (0, 3.849, 1.547) is the same on both branches.
  * pilot (behind): the floor at y -1.35, the side glass down to about y -0.75 with the walls at x +-0.61 below it,
    his old seat back 0.5 behind the eye, the old display pod 1.03-1.18 ahead;
  * gunner (in front): the floor at y -1.14, the walls at x +-0.35-0.59, his old seat back 0.3-0.4 behind the eye,
    the old display pod 0.68-0.90 ahead; beyond it the nose's skin rises to the windscreen's base at his eye line (it\n    stays: the model's exterior).
The model's canopy is bare frames: glaze fits a flat pane in every opening between them.
Built (the Tiger's cockpits as photographed and described): for each crewman two large multifunction displays (the
flight page and the stores / map page, live) over the control and caution panels, the standby attitude, airspeed and
altimeter, engine and rotor dials and fuel; the gunner's rounds counter; consoles, crash-resistant seats, the cyclic,
collective and pedals for both. The gunner's sight is the mast-mounted one (main's zoom camera): no eyepiece here.

Run on the committed originals (work/handbuilt/run_heli.ps1 restores them first).
Usage: python3 tools/cockpit_models/eurocopter_tiger.py [--dry-run]
"""
import os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import cockpit_lib as L   # noqa: E402
import handbuilt as H     # noqa: E402
import free_atlas as FA   # noqa: E402
import heli_station as HS  # noqa: E402

VID = 'eurocopter_tiger'
L.INTERIOR_BONES.add('hull')
FA.also_used(VID, 'ad56207c', 'suspended_')

COLOURS = dict(panel=(40, 42, 44), panel_d=(28, 29, 30), wall=(56, 60, 60))
FLOOR = (44, 46, 48)

PILOT_LAYOUT = [
    ('mfd_l',    0, -0.095, -0.330, ('display', 'PFD', 0.150, 'LCD'), 'left multifunction display'),
    ('mfd_r',    0,  0.095, -0.330, ('display', 'STORES', 0.150, 'LCD'), 'right multifunction display'),
    ('ctrl',     0,  0.000, -0.520, ('paint_rect', 0.220, 0.060), 'display control panel'),
    ('warning',  0,  0.000, -0.640, ('annunciator', 0.220, 0.060), 'caution panel'),
    ('adi',     -1,  0.080, -0.330, ('game', 'ATTITUDE', 0.070), 'standby attitude'),
    ('ias',     -1,  0.080, -0.460, ('game', 'AIRSPEED', 0.058), 'standby airspeed'),
    ('alt',     -1,  0.080, -0.580, ('game', 'ALTIMETER', 0.058), 'standby altimeter'),
    ('rotor',    1,  0.080, -0.330, ('game', 'ENGINE_RPM', 0.058), 'rotor and engine speed'),
    ('egt',      1,  0.080, -0.460, ('game', 'EGT', 0.052), 'turbine temperature'),
    ('fuel',     1,  0.080, -0.580, ('game', 'FUEL_QTY', 0.052), 'fuel quantity'),
]
GUNNER_LAYOUT = [
    ('mfd_l',    0, -0.090, -0.420, ('display', 'STORES', 0.140, 'LCD'), 'sight / stores display'),
    ('mfd_r',    0,  0.090, -0.420, ('display', 'RADAR', 0.140, 'LCD'), 'map display'),
    ('ctrl',     0,  0.000, -0.600, ('paint_rect', 0.200, 0.055), 'weapons control panel'),
    ('warning',  0,  0.000, -0.710, ('annunciator', 0.200, 0.055), 'caution panel'),
    ('adi',     -1,  0.075, -0.420, ('game', 'ATTITUDE', 0.062), 'standby attitude'),
    ('alt',     -1,  0.075, -0.540, ('game', 'ALTIMETER', 0.054), 'standby altimeter'),
    ('ammo',     1,  0.075, -0.420, ('game', 'AMMO', 0.054), 'rounds remaining, 30 mm'),
    ('fuel',     1,  0.075, -0.540, ('game', 'FUEL_QTY', 0.050), 'fuel quantity'),
]
CONSOLES = ([(0.20, 'ENGINE', 'guarded'), (0.20, 'FUEL', 'toggles'), (0.20, 'COM', 'freq'), (0.20, 'AFCS', 'lamps'),
             (0.20, 'LIGHTS', 'rotary')],
            [(0.22, 'NAV', 'keys'), (0.20, 'ELEC', 'toggles'), (0.20, 'CB', 'breakers'), (0.18, 'IFF', 'rotary'),
             (0.20, 'ECS', 'rotary')])


def stations(ac):
    common = dict(S=1.15, ppm=230, console_tile=(30, 128), colours=COLOURS, consoles=CONSOLES, lamp_palette='nato',
                  seat_kind='armoured', seat_label='TIGER', LEAN=12, WING=30)
    pilot = dict(common, seat=0, E=ac.eye(0), layout=PILOT_LAYOUT,
                 PANEL_Z=0.95, HINGE=0.18, WALL_X=0.40, TOP_C=-0.220, TOP_W=-0.250,
                 BOT_C=-0.780, BOT_W=-0.740, FRONT_Z=1.04, FRONT_Y_C=-0.240, FRONT_Y_W=-0.255, COAMING_X=0.24,
                 CONSOLE_Y=-0.720, CONSOLE_IN=0.23, CONSOLE_OUT=0.33, CONSOLE_FRONT=0.60, CONSOLE_FOOT=-1.20,
                 CONSOLE_BACK=-0.30, CUSHION_Y=-0.95, SEAT_Z=0.00, SEAT_FOOT=-1.33, BACK_TOP=0.04,
                 STICK_Z=0.45, STICK_FOOT=-1.31, PEDAL_Z=0.76, PEDAL_Y=-1.05, PEDAL_HANG=True, FLOOR_Y=-1.35,
                 COLLECTIVE_X=0.25)
    gunner = dict(common, seat=1, E=ac.eye(1), layout=GUNNER_LAYOUT,
                  PANEL_Z=0.70, HINGE=0.17, WALL_X=0.32, TOP_C=-0.300, TOP_W=-0.330,
                  BOT_C=-0.860, BOT_W=-0.820, FRONT_Z=0.90, FRONT_Y_C=-0.320, FRONT_Y_W=-0.340, COAMING_X=0.28,
                  CONSOLE_Y=-0.620, CONSOLE_IN=0.25, CONSOLE_OUT=0.33, CONSOLE_FRONT=0.45, CONSOLE_FOOT=-1.08,
                  CONSOLE_BACK=-0.20, CUSHION_Y=-0.88, SEAT_Z=0.11, SEAT_FOOT=-1.12, BACK_TOP=-0.02,
                  STICK_Z=0.38, STICK_FOOT=-1.10, PEDAL_Z=0.58, PEDAL_Y=-0.98, PEDAL_HANG=True, FLOOR_Y=-1.14,
                  COLLECTIVE_X=0.26)
    return pilot, gunner


def main(argv):
    dry = '--dry-run' in argv
    ac = L.Aircraft(VID)
    E0, E1 = ac.eye(0), ac.eye(1)
    removed = {'old interior': HS.remove_old(ac, [
        (E0, (-0.62, -1.33, -0.55), (0.62, -0.75, 1.25), 0.62, -0.75),    # the pilot's tub up to his sill
        (E0, (-0.36, -0.75, -0.55), (0.36, 0.25, 1.25), 0.36, -0.75),     # his old seat and display pod
        (E1, (-0.60, -1.12, -0.45), (0.60, -0.40, 0.95), 0.60, -0.40),    # the gunner's tub up to his sill
        (E1, (-0.30, -0.40, -0.45), (0.30, 0.12, 0.95), 0.30, -0.40)])}   # his old seat and display pod with its hood
    pilot, gunner = stations(ac)
    atlas = H.A.Atlas(VID)
    removed['floor faces repainted'] = HS.repaint_floor(ac, atlas, FLOOR, [
        (E0 + [-0.6, -1.48, -0.5], E0 + [0.6, -1.20, 1.2]), (E1 + [-0.6, -1.27, -0.4], E1 + [0.6, -1.00, 0.9])])
    m = H.Mesh(atlas)
    painters = {'ctrl': HS.p_buttons('MFD', ['FLT', 'NAV', 'WPN', 'SYS', 'MAP'], lit=0)}
    gauges, displays = [], []
    for st in (pilot, gunner):
        g, d, h = HS.build(m, atlas, st, painters=painters)
        gauges += g; displays += d
    pm, line = HS.install(ac, m, gauges, displays, [], [E0, E1])
    removed['old display glass'] = HS.drop_glass(ac, [(E0, (-0.40, -0.60, 0.50), (0.40, 0.05, 1.15))])
    print(f'{VID}: removed {removed}; new cockpit {len(pm["polys"])} faces, {len(gauges)} gauges, '
          f'{len(displays)} displays; {line}')
    if not dry:
        H.write(ac, atlas)


if __name__ == '__main__':
    main(sys.argv[1:])
