#!/usr/bin/env python3
"""AH-1F cockpits, built by hand for this model (heli_station.py stations): the gunner in front, the pilot behind and
above him in the Cobra's narrow tandem tub.

Measured off the model with the cuts and rays (metres from each crewman's eye; the model is close to life size). The
gunner's eye is main's (0, 2.80, 5.35); the pilot's (0, 3.156, 3.688) is the same on both branches.
  * pilot: walls at x +-0.44 under the sill (about y -0.22), the floor at y -1.28, the old panel 0.55-0.70 ahead with
    its top at y -0.27, the gunner's seat back about 1.0 ahead;
  * gunner: walls at x +-0.44-0.48 under the sill (about y -0.38), the floor at y -1.30, his old telescopic sight
    0.28-0.54 ahead, the panel's frame about 0.7 ahead.
The model's canopy is bare frames: glaze fits a flat pane in every opening between them (the Cobra's flat-plate
canopy).
Built (the AH-1F's cockpits as photographed and described):
  * pilot: attitude and situation indicators, airspeed, altimeter, vertical speed, radar altimeter, the engine and
    rotor dials, fuel, the master caution panel and the armament panel; his head-up display on the coaming (the
    game's HUD picture, live for the pilot);
  * gunner: the M65 telescopic sight unit before his face (the eyepiece hood, the handgrips either side), the TOW
    control and armament panels, standby instruments, the rounds counter; his collective on the left;
  * consoles, crew seats, the pilot's cyclic and both crewmen's pedals.
Instruments the game draws (CockpitGauges) are live, each to its own seat.

Run on the committed originals (work/handbuilt/run_heli.ps1 restores them first).
Usage: python3 tools/cockpit_models/ah_1f.py [--dry-run]
"""
import os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import cockpit_lib as L   # noqa: E402
import handbuilt as H     # noqa: E402
import free_atlas as FA   # noqa: E402
import heli_station as HS  # noqa: E402

VID = 'ah_1f'
L.INTERIOR_BONES.add('hull')
FA.also_used(VID, 'ad56207c', 'suspended_')

COLOURS = dict(panel=(42, 44, 46), panel_d=(30, 31, 32), wall=(70, 74, 72))     # US Army instrument black, grey tub
FLOOR = (46, 48, 48)

PILOT_LAYOUT = [
    ('adi',      0, -0.065, -0.360, ('game', 'ATTITUDE', 0.085), 'attitude indicator'),
    ('hsi',      0, -0.065, -0.500, ('game', 'HEADING', 0.080), 'horizontal situation indicator'),
    ('vvi',      0,  0.075, -0.360, ('game', 'VVI', 0.062), 'vertical speed'),
    ('radalt',   0,  0.075, -0.490, ('game', 'RADAR_ALT', 0.058), 'radar altimeter'),
    ('warning',  0,  0.000, -0.640, ('annunciator', 0.220, 0.060), 'master caution panel'),
    ('ias',     -1,  0.070, -0.370, ('game', 'AIRSPEED', 0.062), 'airspeed'),
    ('alt',     -1,  0.170, -0.380, ('game', 'ALTIMETER', 0.062), 'altimeter'),
    ('arm',     -1,  0.120, -0.520, ('paint_rect', 0.140, 0.070), 'armament panel'),
    ('rotor',    1,  0.070, -0.370, ('game', 'ENGINE_RPM', 0.062), 'engine and rotor tachometer'),
    ('egt',      1,  0.170, -0.380, ('game', 'EGT', 0.055), 'exhaust gas temperature'),
    ('fuel',     1,  0.070, -0.510, ('game', 'FUEL_QTY', 0.055), 'fuel quantity'),
    ('clock',    1,  0.165, -0.510, ('game', 'CLOCK', 0.042), 'clock'),
]
GUNNER_LAYOUT = [
    ('tow',      0,  0.000, -0.560, ('paint_rect', 0.200, 0.070), 'TOW control panel'),
    ('warning',  0,  0.000, -0.680, ('annunciator', 0.180, 0.055), 'master caution panel'),
    ('adi',     -1,  0.065, -0.500, ('game', 'ATTITUDE', 0.062), 'standby attitude'),
    ('ias',     -1,  0.150, -0.500, ('game', 'AIRSPEED', 0.052), 'airspeed'),
    ('alt',     -1,  0.105, -0.620, ('game', 'ALTIMETER', 0.052), 'altimeter'),
    ('ammo',     1,  0.065, -0.500, ('game', 'AMMO', 0.052), 'rounds remaining, M197'),
    ('arm',      1,  0.140, -0.610, ('paint_rect', 0.100, 0.060), 'armament panel'),
]
CONSOLES = ([(0.16, 'ENGINE', 'guarded'), (0.18, 'FUEL', 'toggles'), (0.16, 'UHF', 'freq'), (0.16, 'FM', 'freq'),
             (0.16, 'LIGHTS', 'rotary'), (0.18, 'SCAS', 'lamps')],
            [(0.18, 'DOPPLER', 'keys'), (0.16, 'ADF', 'freq'), (0.16, 'DC POWER', 'toggles'), (0.18, 'CB', 'breakers'),
             (0.16, 'IFF', 'rotary'), (0.16, 'ECS', 'rotary')])
CONSOLES_GUNNER = ([(0.24, 'ARMAMENT', 'guarded'), (0.24, 'TOW', 'toggles'), (0.26, 'UHF', 'freq'),
                    (0.26, 'LIGHTS', 'rotary')],
                   [(0.26, 'TSU', 'toggles'), (0.24, 'CB', 'breakers'), (0.26, 'ICS', 'rotary'), (0.24, 'IFF', 'lamps')])


def stations(ac):
    pilot = dict(seat=0, E=ac.eye(0), S=1.05, colours=COLOURS, layout=PILOT_LAYOUT, consoles=CONSOLES,
                 lamp_palette='nato',
                 PANEL_Z=0.60, LEAN=12, HINGE=0.16, WING=30, WALL_X=0.38, TOP_C=-0.280, TOP_W=-0.310,
                 BOT_C=-0.800, BOT_W=-0.760, FRONT_Z=0.82, FRONT_Y_C=-0.300, FRONT_Y_W=-0.320, COAMING_X=0.30,
                 CONSOLE_Y=-0.560, CONSOLE_IN=0.27, CONSOLE_OUT=0.37, CONSOLE_FRONT=0.40, CONSOLE_FOOT=-0.95,
                 CONSOLE_BACK=-0.12, CUSHION_Y=-0.84, SEAT_Z=0.05, SEAT_FOOT=-1.26, BACK_TOP=0.04,
                 seat_kind='armoured', seat_label='AH-1F', STICK_Z=0.38, STICK_FOOT=-1.27, PEDAL_Z=0.74,
                 PEDAL_Y=-1.12, PEDAL_HANG=False, FLOOR_Y=-1.28,
                 hud=dict(z=0.56, w=0.12, h=0.11, lean=25, body_top=-0.215, body_w=0.07, body_d=0.04))
    gunner = dict(seat=1, E=ac.eye(1), S=1.05, colours=COLOURS, layout=GUNNER_LAYOUT, consoles=CONSOLES_GUNNER,
                  lamp_palette='nato',
                  PANEL_Z=0.62, LEAN=12, HINGE=0.15, WING=32, WALL_X=0.36, TOP_C=-0.430, TOP_W=-0.460,
                  BOT_C=-0.950, BOT_W=-0.910, FRONT_Z=0.76, FRONT_Y_C=-0.450, FRONT_Y_W=-0.470, COAMING_X=0.28,
                  CONSOLE_Y=-0.620, CONSOLE_IN=0.27, CONSOLE_OUT=0.42, CONSOLE_FRONT=0.32, CONSOLE_FOOT=-1.20,
                  CONSOLE_BACK=-0.15, CUSHION_Y=-0.88, SEAT_Z=0.11, SEAT_FOOT=-1.28, BACK_TOP=0.02,
                  seat_kind='armoured', seat_label='AH-1F', STICK_Z=None, PEDAL_Z=0.58, PEDAL_Y=-1.14,
                  PEDAL_HANG=False, FLOOR_Y=-1.30,
                  sight=dict(z=0.44, y=-0.230, w=0.13, h=0.09, d=0.14))
    return pilot, gunner


def p_tow(d, im):
    """the TOW control panel: the missile select and launch lamps, the arm switch, the wire cut"""
    S_, Hh = im.width, im.height
    d.rectangle([0, 0, S_, Hh], fill=(20, 22, 22, 255))
    for k, lab in enumerate(['MSL 1', 'MSL 2', 'ARM', 'LAUNCH', 'WIRE CUT']):
        x = S_ * (0.1 + k * 0.2)
        H.lamp(d, [x - 14, Hh * .15, x + 14, Hh * .45], (210, 190, 120) if k < 3 else (200, 60, 50), None, lit=(k == 2))
        H.text(d, (x, Hh * .7), lab, Hh * .13)


def main(argv):
    dry = '--dry-run' in argv
    ac = L.Aircraft(VID)
    E0, E1 = ac.eye(0), ac.eye(1)
    removed = {'old interior': HS.remove_old(ac, [
        (E0, (-0.46, -1.27, -0.50), (0.46, -0.22, 0.95), 0.46, -0.22),    # the pilot's tub up to his sill
        (E0, (-0.30, -0.22, -0.50), (0.30, 0.20, -0.20), 0.30, -0.22),    # his old seat's head
        (E0, (-0.25, -0.22, 0.45), (0.25, 0.05, 0.95), 0.25, -0.22),      # the old panel's top and sight
        (E1, (-0.50, -1.28, -0.25), (0.50, -0.38, 0.85), 0.50, -0.38),    # the gunner's tub up to his sill
        (E1, (-0.30, -0.38, -0.40), (0.30, 0.20, -0.15), 0.30, -0.38),    # his old seat's head
        (E1, (-0.20, -0.38, 0.20), (0.20, 0.00, 0.70), 0.20, -0.38)])}    # his old telescopic sight
    pilot, gunner = stations(ac)
    atlas = H.A.Atlas(VID)
    removed['floor faces repainted'] = HS.repaint_floor(ac, atlas, FLOOR, [
        (E0 + [-0.45, -1.40, -0.5], E0 + [0.45, -1.15, 0.9]), (E1 + [-0.5, -1.42, -0.3], E1 + [0.5, -1.17, 0.8])])
    m = H.Mesh(atlas)
    painters = {'arm': HS.p_buttons('ARMAMENT', ['RKT', 'GUN', 'TOW'], lit=1), 'tow': p_tow}
    gauges, displays, huds = [], [], []
    for st in (pilot, gunner):
        g, d, h = HS.build(m, atlas, st, painters=painters)
        gauges += g; displays += d; huds.append(h)
    pm, line = HS.install(ac, m, gauges, displays, huds, [E0, E1])
    print(f'{VID}: removed {removed}; new cockpit {len(pm["polys"])} faces, {len(gauges)} gauges, '
          f'{len(displays)} displays, {len([h for h in huds if h])} sights; {line}')
    if not dry:
        H.write(ac, atlas)


if __name__ == '__main__':
    main(sys.argv[1:])
