#!/usr/bin/env python3
"""Mi-24D cockpits, built by hand for this model (heli_station.py stations, as mi24v.py): the weapons operator in the
nose, the pilot behind and above him, each at his own panel and sight.

Measured off the model with the cuts and rays (metres from each crewman's eye; the model is about 1.3 times life
size). The pilot's eye is main's (0, 3.56, 5.75); the operator's (0, 2.678, 7.75) is the same on both branches.
  * pilot: walls at x +-0.50-0.56 under the sill (about y -0.35), the floor at y -1.12, the old panel 0.62-0.75 ahead
    with the old sight on its top, the bulkhead behind the operator from 1.0 ahead;
  * operator: walls at x +-0.62 under the sill (about y -0.30), the nose narrowing to +-0.38 ahead of him, the floor
    at y -1.05, the old sight's column 0.5 ahead.
The model's canopies are bare frames: glaze fits a flat pane in every opening between them (the Mi-24D's flat
armoured windscreens and side panes).
Built (the Mi-24D's cockpits as photographed and described): as the Mi-24V's (mi24v.py): the pilot's instruments and
the ASP-17V gunsight (live for the pilot), the operator's KPS-53AV sight with its reflector glass (live for the
operator), the Raduga-F guidance panel, standby instruments, the rounds counter; consoles, armoured seats, controls.

Run on the committed originals (work/handbuilt/run_heli.ps1 restores them first).
Usage: python3 tools/cockpit_models/mi_24d.py [--dry-run]
"""
import os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import cockpit_lib as L   # noqa: E402
import handbuilt as H     # noqa: E402
import free_atlas as FA   # noqa: E402
import heli_station as HS  # noqa: E402

VID = 'mi_24d'
L.INTERIOR_BONES.add('hull')
FA.also_used(VID, 'ad56207c', 'suspended_')

COLOURS = dict(panel=(78, 140, 146), panel_d=(54, 100, 106), wall=(64, 118, 122))   # the Mi-24's turquoise
FLOOR = (46, 50, 50)

PILOT_LAYOUT = [
    ('adi',      0, -0.070, -0.420, ('game', 'ATTITUDE', 0.095), 'AGB-3K attitude indicator'),
    ('hsi',      0, -0.070, -0.570, ('game', 'HEADING', 0.090), 'PNP-72 situation indicator'),
    ('vvi',      0,  0.080, -0.420, ('game', 'VVI', 0.070), 'VR-30 vertical speed'),
    ('radalt',   0,  0.080, -0.560, ('game', 'RADAR_ALT', 0.065), 'RV-5 radio altimeter'),
    ('warning',  0,  0.000, -0.720, ('annunciator', 0.240, 0.070), 'warning lights'),
    ('ias',     -1,  0.080, -0.430, ('game', 'AIRSPEED', 0.070), 'US-450 airspeed'),
    ('alt',     -1,  0.200, -0.430, ('game', 'ALTIMETER', 0.070), 'VD-10 altimeter'),
    ('clock',   -1,  0.080, -0.570, ('game', 'CLOCK', 0.045), 'AChS clock'),
    ('weapons', -1,  0.200, -0.580, ('paint_rect', 0.100, 0.080), 'weapons panel'),
    ('rotor',    1,  0.080, -0.430, ('game', 'ENGINE_RPM', 0.070), 'rotor and engine speed'),
    ('egt_l',    1,  0.195, -0.430, ('game', 'EGT', 0.055), 'gas temperature, left engine'),
    ('egt_r',    1,  0.195, -0.560, ('game', 'EGT', 0.055), 'gas temperature, right engine'),
    ('fuel',     1,  0.080, -0.570, ('game', 'FUEL_QTY', 0.060), 'fuel quantity'),
]
OPERATOR_LAYOUT = [
    ('guidance', 0,  0.000, -0.560, ('paint_rect', 0.200, 0.080), 'Raduga-F missile guidance panel'),
    ('warning',  0,  0.000, -0.700, ('annunciator', 0.180, 0.060), 'warning lights'),
    ('adi',     -1,  0.070, -0.460, ('game', 'ATTITUDE', 0.070), 'standby attitude'),
    ('ias',     -1,  0.160, -0.460, ('game', 'AIRSPEED', 0.058), 'airspeed'),
    ('alt',     -1,  0.110, -0.600, ('game', 'ALTIMETER', 0.058), 'altimeter'),
    ('ammo',     1,  0.070, -0.460, ('game', 'AMMO', 0.058), 'rounds remaining, YakB-12.7'),
    ('clock',    1,  0.160, -0.460, ('game', 'CLOCK', 0.042), 'clock'),
    ('selector', 1,  0.110, -0.600, ('paint_rect', 0.110, 0.070), 'weapons selector'),
]
CONSOLES_PILOT = ([(0.16, 'ЗАПУСК', 'guarded'), (0.16, 'ТОПЛИВО', 'toggles'), (0.16, 'Р-863', 'freq'),
                   (0.18, 'АВТОПИЛОТ', 'lamps'), (0.16, 'ОСВЕЩ', 'rotary'), (0.18, 'ГИДРО', 'toggles')],
                  [(0.16, 'АРК-15', 'freq'), (0.20, 'ЭЛЕКТРОПИТАНИЕ', 'toggles'), (0.18, 'АЗС', 'breakers'),
                   (0.16, 'СИГНАЛ', 'lamps'), (0.14, 'КОНДИЦ', 'rotary'), (0.16, 'СПУ', 'rotary')])
CONSOLES_OPERATOR = ([(0.22, 'ОРУЖИЕ', 'guarded'), (0.22, 'ПУЛЕМЕТ', 'toggles'), (0.28, 'ОСВЕЩ', 'rotary'),
                      (0.28, 'СПУ', 'rotary')],
                     [(0.24, 'РАДУГА', 'toggles'), (0.22, 'АЗС', 'breakers'), (0.26, 'СИГНАЛ', 'lamps'),
                      (0.28, 'КИСЛОРОД', 'rotary')])


def stations(ac):
    pilot = dict(seat=0, E=ac.eye(0), S=1.25, colours=COLOURS, layout=PILOT_LAYOUT, consoles=CONSOLES_PILOT,
                 PANEL_Z=0.64, LEAN=12, HINGE=0.18, WING=30, WALL_X=0.44, TOP_C=-0.300, TOP_W=-0.340,
                 BOT_C=-0.900, BOT_W=-0.860, FRONT_Z=0.80, FRONT_Y_C=-0.300, FRONT_Y_W=-0.330, COAMING_X=0.32,
                 CONSOLE_Y=-0.700, CONSOLE_IN=0.33, CONSOLE_OUT=0.48, CONSOLE_FRONT=0.42, CONSOLE_FOOT=-1.08,
                 CONSOLE_BACK=-0.25, CUSHION_Y=-0.84, SEAT_Z=0.05, SEAT_FOOT=-1.10, BACK_TOP=0.04,
                 seat_kind='armoured', seat_label='Ми-24Д', STICK_Z=0.40, STICK_FOOT=-1.10, PEDAL_Z=0.78,
                 PEDAL_Y=-1.00, PEDAL_HANG=False, FLOOR_Y=-1.12, COLLECTIVE_X=0.30,
                 hud=dict(z=0.62, w=0.13, h=0.12, lean=30, body_top=-0.235, body_w=0.08, body_d=0.04, style='GYRO'))
    operator = dict(seat=1, E=ac.eye(1), S=1.25, colours=COLOURS, layout=OPERATOR_LAYOUT, consoles=CONSOLES_OPERATOR,
                    PANEL_Z=0.56, LEAN=12, HINGE=0.15, WING=32, WALL_X=0.36, TOP_C=-0.370, TOP_W=-0.410,
                    BOT_C=-0.850, BOT_W=-0.810, FRONT_Z=0.62, FRONT_Y_C=-0.390, FRONT_Y_W=-0.410, COAMING_X=0.26,
                    CONSOLE_Y=-0.600, CONSOLE_IN=0.30, CONSOLE_OUT=0.55, CONSOLE_FRONT=0.36, CONSOLE_FOOT=-1.00,
                    CONSOLE_BACK=-0.30, CUSHION_Y=-0.84, SEAT_Z=0.04, SEAT_FOOT=-1.03, BACK_TOP=0.02,
                    seat_kind='armoured', seat_label='Ми-24Д', STICK_Z=0.34, STICK_FOOT=-1.03, PEDAL_Z=0.52,
                    PEDAL_Y=-0.86, PEDAL_HANG=True, FLOOR_Y=-1.05, COLLECTIVE=False,
                    sight=dict(z=0.44, y=-0.125, w=0.14, h=0.09, d=0.13,
                               glass=dict(z=0.39, w=0.08, h=0.07, lean=25, style='REFLECTOR')))
    return pilot, operator

def main(argv):
    dry = '--dry-run' in argv
    ac = L.Aircraft(VID)
    E0, E1 = ac.eye(0), ac.eye(1)
    removed = {'old interior': HS.remove_old(ac, [
        (E0, (-0.60, -1.10, -0.60), (0.60, -0.30, 0.98), 0.60, -0.30),    # the pilot's tub up to his sill
        (E0, (-0.36, -0.30, -0.60), (0.36, 0.40, -0.10), 0.36, -0.30),    # his old seat's head and armour
        (E0, (-0.22, -0.30, 0.45), (0.22, 0.12, 0.80), 0.22, -0.30),      # the old sight on his panel
        (E1, (-0.60, -1.03, -0.60), (0.60, -0.28, 0.90), 0.60, -0.28),    # the operator's tub up to his sill
        (E1, (-0.36, -0.28, -0.60), (0.36, 0.30, -0.12), 0.36, -0.28),    # his old seat's head
        (E1, (-0.15, -0.28, 0.30), (0.15, 0.00, 0.62), 0.15, -0.28)])}    # his old sight's column
    pilot, operator = stations(ac)
    atlas = H.A.Atlas(VID)
    removed['floor faces repainted'] = HS.repaint_floor(ac, atlas, FLOOR, [
        (E0 + [-0.6, -1.25, -0.6], E0 + [0.6, -1.00, 1.0]), (E1 + [-0.6, -1.17, -0.6], E1 + [0.6, -0.93, 0.9])])
    m = H.Mesh(atlas)
    painters = {'weapons': HS.p_buttons('ВООРУЖЕНИЕ', ['НАР', 'УР', 'БОМБ'], lit=0),
                'guidance': HS.p_buttons('РАДУГА-Ф', ['ПУСК', 'НАВЕД', 'ГОТОВ', 'СБРОС'], lit=2),
                'selector': HS.p_buttons('ВЫБОР', ['УР', 'НАР', 'ПУЛ'], lit=2)}
    gauges, displays, huds = [], [], []
    for st in (pilot, operator):
        g, d, h = HS.build(m, atlas, st, painters=painters)
        gauges += g; displays += d; huds.append(h)
    pm, line = HS.install(ac, m, gauges, displays, huds, [E0, E1])
    print(f'{VID}: removed {removed}; new cockpit {len(pm["polys"])} faces, {len(gauges)} gauges, '
          f'{len(displays)} displays, {len([h for h in huds if h])} sights; {line}')
    if not dry:
        H.write(ac, atlas)


if __name__ == '__main__':
    main(sys.argv[1:])
