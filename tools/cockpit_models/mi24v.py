#!/usr/bin/env python3
"""Mi-24V cockpits, built by hand for this model (heli_station.py stations): the weapons operator in the nose, the
pilot behind and above him, each at his own panel and sight.

Measured off the model with the cuts and rays (metres from each crewman's eye; the model is about 1.1 times life
size). The operator's eye is main's (0, 1.85, 5.15); the pilot's (0, 2.589, 3.647) is the same on both branches.
  * pilot: walls at x +-0.62-0.68 under the sill (about y -0.25), the floor at y -1.12, the old panel 0.57 ahead with
    its top at y -0.33; his flat windscreen 0.68 ahead at eye height, its base frame 0.70 ahead at y -0.26;
  * operator: walls at x +-0.45-0.47, the floor at y -1.10, his old sight 0.36 ahead with its top at y -0.31, his
    canopy's frame ring ahead and above.
The model's canopies are bare frames (no glass): glaze fits a flat pane in every opening between them, which is what
the Mi-24V carries (flat armoured windscreens in front of both crewmen, flat side panes).
Built (the Mi-24V's cockpits as photographed and described):
  * pilot: the attitude and situation indicators, airspeed, altimeter, vertical speed, radio altimeter, the rotor
    and the engines' dials, fuel and the warning lights; the ASP-17V gunsight on the coaming (its reticle the game's
    gyro gunsight picture, live for the pilot);
  * operator: the KPS-53AV sight for the nose gun before his face (its eyepiece hood, the grips either side, the
    reflector glass over its head carrying the reticle, live for the operator), the missile guidance and weapons
    selector panel, standby instruments and the rounds counter;
  * both: consoles, armoured seats, the pilot's cyclic, collective and pedals (the operator has his own stick and
    pedals too).
Instruments the game draws (CockpitGauges) are live, each to its own seat.

Run on the committed originals (work/handbuilt/run_heli.ps1 restores them first).
Usage: python3 tools/cockpit_models/mi24v.py [--dry-run]
"""
import os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import cockpit_lib as L   # noqa: E402
import handbuilt as H     # noqa: E402
import free_atlas as FA   # noqa: E402
import heli_station as HS  # noqa: E402

VID = 'mi24v'
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
    ('guidance', 0,  0.000, -0.560, ('paint_rect', 0.200, 0.080), 'Raduga-Sh missile guidance panel'),
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
    pilot = dict(seat=0, E=ac.eye(0), S=1.1, ppm=240, console_tile=(34, 140), colours=COLOURS, layout=PILOT_LAYOUT, consoles=CONSOLES_PILOT,
                 PANEL_Z=0.52, LEAN=12, HINGE=0.17, WING=30, WALL_X=0.47, TOP_C=-0.330, TOP_W=-0.370,
                 BOT_C=-0.820, BOT_W=-0.780, FRONT_Z=0.66, FRONT_Y_C=-0.330, FRONT_Y_W=-0.360, COAMING_X=0.34,
                 CONSOLE_Y=-0.620, CONSOLE_IN=0.32, CONSOLE_OUT=0.56, CONSOLE_FRONT=0.36, CONSOLE_FOOT=-1.08,
                 CONSOLE_BACK=-0.30, CUSHION_Y=-0.90, SEAT_Z=0.02, SEAT_FOOT=-1.10, BACK_TOP=0.04,
                 seat_kind='armoured', seat_label='Ми-24В', STICK_Z=0.36, STICK_FOOT=-1.12, PEDAL_Z=0.66,
                 PEDAL_Y=-1.00, PEDAL_HANG=False, FLOOR_Y=-1.12,
                 hud=dict(z=0.55, w=0.12, h=0.11, lean=30, body_top=-0.255, body_w=0.075, body_d=0.035, style='GYRO'))
    operator = dict(seat=1, E=ac.eye(1), S=1.1, ppm=240, console_tile=(34, 140), colours=COLOURS, layout=OPERATOR_LAYOUT, consoles=CONSOLES_OPERATOR,
                    PANEL_Z=0.52, LEAN=12, HINGE=0.15, WING=32, WALL_X=0.35, TOP_C=-0.360, TOP_W=-0.400,
                    BOT_C=-0.780, BOT_W=-0.740, FRONT_Z=0.70, FRONT_Y_C=-0.380, FRONT_Y_W=-0.410, COAMING_X=0.30,
                    CONSOLE_Y=-0.600, CONSOLE_IN=0.27, CONSOLE_OUT=0.43, CONSOLE_FRONT=0.35, CONSOLE_FOOT=-1.05,
                    CONSOLE_BACK=-0.30, CUSHION_Y=-0.86, SEAT_Z=0.02, SEAT_FOOT=-1.08, BACK_TOP=0.02,
                    seat_kind='armoured', seat_label='Ми-24В', STICK_Z=0.32, STICK_FOOT=-1.05, PEDAL_Z=0.48,
                    PEDAL_Y=-0.88, PEDAL_HANG=True, FLOOR_Y=-1.10, COLLECTIVE=False,
                    sight=dict(z=0.42, y=-0.120, w=0.12, h=0.08, d=0.12,
                               glass=dict(z=0.38, w=0.075, h=0.065, lean=25, style='REFLECTOR')))
    return pilot, operator


def main(argv):
    dry = '--dry-run' in argv
    ac = L.Aircraft(VID)
    E0, E1 = ac.eye(0), ac.eye(1)
    removed = {'old interior': HS.remove_old(ac, [
        (E0, (-0.62, -1.10, -0.60), (0.62, -0.26, 0.95), 0.62, -0.26),    # the pilot's tub up to his sill
        (E0, (-0.36, -0.26, -0.60), (0.36, 0.30, -0.10), 0.36, -0.26),    # his old seat's head
        (E0, (-0.22, -0.26, 0.40), (0.22, 0.15, 0.68), 0.22, -0.26),      # the old sight's side plates over his panel
        (E1, (-0.50, -1.08, -0.60), (0.50, -0.24, 0.85), 0.50, -0.24),    # the operator's tub up to his sill
        (E1, (-0.36, -0.24, -0.60), (0.36, 0.25, -0.10), 0.36, -0.24)])}  # his old seat's head
    pilot, operator = stations(ac)
    atlas = H.A.Atlas(VID)
    removed['floor faces repainted'] = HS.repaint_floor(ac, atlas, FLOOR, [
        (E0 + [-0.6, -1.25, -0.6], E0 + [0.6, -1.00, 1.0]), (E1 + [-0.5, -1.22, -0.6], E1 + [0.5, -0.98, 0.9])])
    m = H.Mesh(atlas)
    painters = {'weapons': HS.p_buttons('ВООРУЖЕНИЕ', ['НАР', 'УР', 'БОМБ'], lit=0),
                'guidance': HS.p_buttons('РАДУГА-Ш', ['ПУСК', 'НАВЕД', 'ГОТОВ', 'СБРОС'], lit=2),
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
