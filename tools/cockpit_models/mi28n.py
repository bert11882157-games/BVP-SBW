#!/usr/bin/env python3
"""Mi-28N cockpits, built by hand for this model (heli_station.py stations, as ka50.py): the navigator-operator low in
the front cockpit, the pilot behind and above him, each at his own panel.

Measured off the model with the cuts (metres from each crewman's eye; the model is about 1.4 times life size round the
cockpits). The operator's eye is main's (0, 3.92, 3.45); the pilot's (0, 4.488, 1.583) is the same on both branches.
  * pilot: walls at x +-0.58, floor at y -1.43, the old panel a box 0.75-1.25 ahead (top y -0.25) against the
    operator's seat back; his windscreen comes down to y -0.35 at 1.25 ahead, over the operator's roof;
  * operator: walls at x +-0.59, floor at y -1.38, his old seat back 0.42-0.62 behind the eye, the old panel a box
    0.73-1.13 ahead (top y -0.23), the flat armoured windscreen's base at y -0.38, 1.08 ahead.
Built (the Mi-28N's cockpits as photographed and described):
  * pilot: two MFI-10 multifunction displays top centre over the warning lights; the flight instruments on the left
    wing (attitude, airspeed, altimeter, radio altimeter, situation indicator), the vertical speed, rotor speed, the
    engines' gas temperatures and fuel on the right; his HUD on the coaming, its glass centred on his eye line;
  * operator: the sight head of his sighting system on the coaming in front of him (the eyepiece hood toward his
    face, the grip handles either side), the TV / thermal and map displays under it, the weapons selector, standby
    flight instruments and the rounds counter;
  * both: side consoles, an armoured crew seat (the Mi-28 has no ejection seats), the cyclic, the collective on the
    left and the pedals.
Instruments the game draws (CockpitGauges, FlightDisplays) are live, each to its own seat; the HUD is the pilot's.
No canopy is made: the model keeps its own stepped canopy and frames (the flat armoured panes); they get glass
(model_glass.glaze: the model's original panes and flat panes in the openings, the operator's cockpit included).

Run on the committed originals (work/handbuilt/run_heli.ps1 restores them first).
Usage: python3 tools/cockpit_models/mi28n.py [--dry-run]
"""
import os, sys
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import cockpit_lib as L   # noqa: E402
import handbuilt as H     # noqa: E402
import free_atlas as FA   # noqa: E402
import heli_station as HS  # noqa: E402

VID = 'mi28n'
L.INTERIOR_BONES.add('hull')
FA.also_used(VID, 'ad56207c', 'suspended_')
INTERIOR_FROM = 4850           # the model's shell and frames are its first faces; its old interior parts come after

COLOURS = dict(panel=(42, 45, 48), panel_d=(30, 32, 34), wall=(84, 98, 106))
FLOOR = (46, 50, 52)

PILOT_LAYOUT = [
    ('mfd_l',    0, -0.105, -0.420, ('display', 'PFD', 0.170, 'LCD'), 'MFI-10 left'),
    ('mfd_r',    0,  0.105, -0.420, ('display', 'STORES', 0.170, 'LCD'), 'MFI-10 right'),
    ('warning',  0,  0.000, -0.640, ('annunciator', 0.260, 0.080), 'warning lights'),
    ('clock',    0, -0.170, -0.800, ('game', 'CLOCK', 0.045), 'clock'),
    ('gear',     0,  0.170, -0.790, ('paint_rect', 0.055, 0.075), 'wheel brake and lamps'),
    ('adi',     -1,  0.095, -0.440, ('game', 'ATTITUDE', 0.100), 'attitude director'),
    ('ias',     -1,  0.225, -0.440, ('game', 'AIRSPEED', 0.075), 'airspeed'),
    ('hsi',     -1,  0.095, -0.610, ('game', 'HEADING', 0.095), 'situation indicator'),
    ('alt',     -1,  0.225, -0.600, ('game', 'ALTIMETER', 0.075), 'barometric altimeter'),
    ('radalt',  -1,  0.160, -0.760, ('game', 'RADAR_ALT', 0.070), 'radio altimeter'),
    ('vvi',      1,  0.095, -0.440, ('game', 'VVI', 0.075), 'vertical speed'),
    ('rotor',    1,  0.220, -0.440, ('game', 'ENGINE_RPM', 0.075), 'rotor speed'),
    ('egt_l',    1,  0.085, -0.600, ('game', 'EGT', 0.060), 'gas temperature, left engine'),
    ('egt_r',    1,  0.180, -0.600, ('game', 'EGT', 0.060), 'gas temperature, right engine'),
    ('fuel',     1,  0.140, -0.760, ('game', 'FUEL_QTY', 0.065), 'fuel quantity'),
]
OPERATOR_LAYOUT = [
    ('tv',       0, -0.095, -0.470, ('display', 'STORES', 0.160, 'LCD'), 'TV / thermal display'),
    ('map',      0,  0.095, -0.470, ('display', 'RADAR', 0.160, 'LCD'), 'map / radar display'),
    ('weapons',  0,  0.000, -0.680, ('paint_rect', 0.300, 0.075), 'weapons selector'),
    ('warning',  0,  0.000, -0.840, ('annunciator', 0.220, 0.070), 'warning lights'),
    ('adi',     -1,  0.085, -0.430, ('game', 'ATTITUDE', 0.085), 'standby attitude'),
    ('ias',     -1,  0.190, -0.440, ('game', 'AIRSPEED', 0.064), 'airspeed'),
    ('alt',     -1,  0.085, -0.590, ('game', 'ALTIMETER', 0.068), 'altimeter'),
    ('radalt',  -1,  0.190, -0.590, ('game', 'RADAR_ALT', 0.060), 'radio altimeter'),
    ('vvi',      1,  0.085, -0.430, ('game', 'VVI', 0.068), 'vertical speed'),
    ('clock',    1,  0.190, -0.440, ('game', 'CLOCK', 0.045), 'clock'),
    ('fuel',     1,  0.085, -0.590, ('game', 'FUEL_QTY', 0.062), 'fuel quantity'),
    ('ammo',     1,  0.190, -0.590, ('game', 'AMMO', 0.058), 'rounds remaining, 2A42'),
]

CONSOLE_PILOT = ([(0.14, 'ЗАПУСК', 'guarded'), (0.14, 'ТОПЛИВО', 'toggles'), (0.14, 'Р-800', 'freq'),
                  (0.16, 'АВТОПИЛОТ', 'lamps'), (0.20, 'ОСВЕЩ', 'rotary'), (0.20, 'ГИДРО', 'toggles')],
                 [(0.18, 'НАВИГАЦИЯ', 'keys'), (0.14, 'АРК', 'freq'), (0.18, 'ЭЛЕКТРОПИТАНИЕ', 'toggles'),
                  (0.18, 'АЗС', 'breakers'), (0.16, 'СИГНАЛ', 'lamps'), (0.14, 'КОНДИЦ', 'rotary')])
CONSOLE_OPERATOR = ([(0.18, 'ОРУЖИЕ', 'guarded'), (0.18, 'ПУШКА', 'toggles'), (0.16, 'Р-800', 'freq'),
                     (0.24, 'ОСВЕЩ', 'rotary'), (0.24, 'КИСЛОРОД', 'rotary')],
                    [(0.22, 'ПРИЦЕЛ', 'keys'), (0.18, 'ТЕПЛОВИЗОР', 'toggles'), (0.20, 'АЗС', 'breakers'),
                     (0.20, 'СИГНАЛ', 'lamps'), (0.20, 'ДОКУМЕНТ', 'rotary')])


def stations(ac):
    pilot = dict(seat=0, E=ac.eye(0), S=1.25, colours=COLOURS, layout=PILOT_LAYOUT, consoles=CONSOLE_PILOT,
                 PANEL_Z=0.72, LEAN=12, HINGE=0.22, WING=30, WALL_X=0.50, TOP_C=-0.320, TOP_W=-0.360,
                 BOT_C=-0.880, BOT_W=-0.850, FRONT_Z=0.97, FRONT_Y_C=-0.360, FRONT_Y_W=-0.400, COAMING_X=0.36,
                 CONSOLE_Y=-0.820, CONSOLE_IN=0.36, CONSOLE_OUT=0.56, CONSOLE_FRONT=0.55, CONSOLE_FOOT=-1.30,
                 CONSOLE_BACK=-0.30, CUSHION_Y=-1.08, SEAT_Z=-0.05, SEAT_FOOT=-1.42, BACK_TOP=0.04,
                 seat_kind='armoured', seat_label='Ми-28Н', STICK_Z=0.40, STICK_FOOT=-1.43, PEDAL_Z=0.58,
                 PEDAL_Y=-1.30, PEDAL_HANG=False, FLOOR_Y=-1.43, labels=[(-0.17, -0.70, 'ИЛС'), (0.17, -0.70, 'МФИ')],
                 hud=dict(z=0.64, w=0.20, h=0.15, lean=20, body_top=-0.205, body_w=0.10, body_d=0.07))
    operator = dict(seat=1, E=ac.eye(1), S=1.25, colours=COLOURS, layout=OPERATOR_LAYOUT, consoles=CONSOLE_OPERATOR,
                    PANEL_Z=0.70, LEAN=12, HINGE=0.20, WING=30, WALL_X=0.43, TOP_C=-0.270, TOP_W=-0.340,
                    BOT_C=-0.930, BOT_W=-0.880, FRONT_Z=1.00, FRONT_Y_C=-0.330, FRONT_Y_W=-0.370, COAMING_X=0.34,
                    CONSOLE_Y=-0.780, CONSOLE_IN=0.34, CONSOLE_OUT=0.55, CONSOLE_FRONT=0.50, CONSOLE_FOOT=-1.25,
                    CONSOLE_BACK=-0.33, CUSHION_Y=-1.04, SEAT_Z=-0.08, SEAT_FOOT=-1.24, BACK_TOP=0.02,
                    seat_kind='armoured', seat_label='Ми-28Н', STICK_Z=0.36, STICK_FOOT=-1.35, PEDAL_Z=0.56,
                    PEDAL_Y=-1.22, PEDAL_HANG=False, FLOOR_Y=-1.35,
                    sight=dict(z=0.62, y=-0.150, w=0.17, h=0.09, d=0.14))
    return pilot, operator


def main(argv):
    dry = '--dry-run' in argv
    ac = L.Aircraft(VID)
    E0, E1 = ac.eye(0), ac.eye(1)
    removed = {'old interior': HS.remove_old(ac, [(E0, (-0.62, -1.40, -0.62), (0.62, 0.30, 1.24), 0.62, -0.45),
                                                  (E1, (-0.62, -1.35, -0.64), (0.62, 0.25, 1.15), 0.62, -0.45)],
                                             min_index=INTERIOR_FROM)}
    pilot, operator = stations(ac)
    atlas = H.A.Atlas(VID)
    removed['floor faces repainted'] = HS.repaint_floor(ac, atlas, FLOOR, [
        (E0 + [-0.6, -1.55, -0.6], E0 + [0.6, -1.20, 1.2]), (E1 + [-0.6, -1.50, -0.6], E1 + [0.6, -1.15, 1.1])])
    m = H.Mesh(atlas)
    painters = {'weapons': HS.p_buttons('ВЫБОР ОРУЖИЯ', ['НАР', 'ПТУР', 'ПУШКА', 'УР', 'ЗАЛП'], lit=2)}
    gauges, displays, hud = [], [], None
    for st in (pilot, operator):
        g, d, h = HS.build(m, atlas, st, painters=painters)
        gauges += g; displays += d; hud = hud or h
    pm, line = HS.install(ac, m, gauges, displays, hud, [E0, E1])
    print(f'{VID}: removed {removed}; new cockpit {len(pm["polys"])} faces, {len(gauges)} gauges, '
          f'{len(displays)} displays; {line}')
    if not dry:
        H.write(ac, atlas)


if __name__ == '__main__':
    main(sys.argv[1:])
