#!/usr/bin/env python3
"""Mi-24D cockpits, built by hand for this model (heli_station.py stations, as mi24v.py): the weapons operator in the
nose, the pilot behind and above him, each at his own panel and sight.

Measured off the model with the cuts and rays (metres from each crewman's eye; the model is about 1.3 times life
size). The pilot's eye is main's (0, 3.56, 5.75); the operator's (0, 2.678, 7.75) is the same on both branches.
  * pilot: walls at x +-0.50-0.56 under the sill (about y -0.35), the floor at y -1.12, the old panel 0.62-0.75 ahead
    with the old sight on its top, the bulkhead behind the operator from 1.0 ahead;
  * operator: walls at x +-0.62 under the sill (about y -0.30), the nose narrowing to +-0.38 ahead of him, the floor
    at y -1.05, the old sight's column 0.5 ahead.
The model's canopies are bare frames; canopy_mi_24d.py (run after this) cuts them out and remakes both canopies to
their exact shape, framed and glazed (bert, 2026-10-23).
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

# the pilot's panel as photographed (Cottbus Mi-24D, b-domke 112-1210 / 1211; the 2026-10-23 reference notes): a flat
# centre over his knees, wings angled out to the walls; rows (y from his eye) top to bottom: the G-meter top left and
# the warning lamps under it, the SPO-10 at right; the DISS hover indicator, US-450 airspeed, the attitude indicator,
# the VAR-30 and the big DISS map box filling the upper right wing; VD-10 altimeter, RV-5, the HSI under the attitude
# indicator, the standby horizon; rotor speed, the twin gas temperature, fuel; the hydraulics gauges low on the left
# wing, the engines' oil low on the right. (a < 0: his left; the centre facet +-0.30, the wings beyond)
def _col(a):
    return (0, a) if abs(a) <= 0.30 else ((-1 if a < 0 else 1), abs(a) - 0.30)


def _row(y, items):
    return [(n, *_col(a), y, spec, lab) for n, a, spec, lab in items]


PILOT_LAYOUT = (
    # the left wing, one column: the warning lamps, G-meter, DISS hover indicator, rotor pitch, hydraulics
    _row(-0.375, [('warning', -0.40, ('annunciator', 0.150, 0.050), 'warning lamps')]) +
    _row(-0.460, [('gmeter',  -0.40, ('game', 'G_METER', 0.075), 'G-meter')]) +
    _row(-0.565, [('diss',    -0.40, ('game', 'TURN_SLIP', 0.090), 'DISS-15 hover indicator')]) +
    _row(-0.668, [('pitch',   -0.40, ('game', 'FILLER_TEMP', 0.070), 'rotor pitch')]) +
    _row(-0.765, [('hyd',     -0.40, ('game', 'FILLER_HYDRAULIC', 0.075), 'hydraulics')]) +
    # the centre, a 4 x 3 grid with the attitude indicator and HSI one above the other
    _row(-0.470, [('ias',     -0.22, ('game', 'AIRSPEED', 0.100), 'US-450 airspeed'),
                  ('adi',     -0.08, ('game', 'ATTITUDE', 0.120), 'PKP-72 attitude indicator'),
                  ('vvi',      0.06, ('game', 'VVI', 0.100), 'VAR-30 vertical speed'),
                  ('rwr',      0.20, ('game', 'RWR', 0.090), 'SPO-10 radar warning')]) +
    _row(-0.610, [('alt',     -0.22, ('game', 'ALTIMETER', 0.100), 'VD-10 altimeter'),
                  ('hsi',     -0.08, ('game', 'HEADING', 0.110), 'HSI'),
                  ('radalt',   0.06, ('game', 'RADAR_ALT', 0.090), 'RV-5 radio altimeter'),
                  ('sadi',     0.20, ('game', 'ATTITUDE', 0.080), 'standby attitude indicator')]) +
    _row(-0.740, [('rotor',   -0.22, ('game', 'FILLER_RPM', 0.090), 'rotor speed, %'),
                  ('egt',     -0.08, ('game', 'EGT', 0.090), 'gas temperature, both engines'),
                  ('fuel',     0.06, ('game', 'FUEL_QTY', 0.085), 'fuel'),
                  ('clock',    0.20, ('game', 'CLOCK', 0.070), 'clock')]) +
    _row(-0.822, [('weapons', -0.08, ('paint_rect', 0.200, 0.050), 'weapons selector')]) +
    # the right wing: the DISS navigation box, the engines' oil
    _row(-0.500, [('map',      0.40, ('paint_rect', 0.165, 0.150), 'DISS map / navigation box')]) +
    _row(-0.645, [('oil_t',    0.40, ('game', 'FILLER_TEMP', 0.070), 'engine oil temperature')]) +
    _row(-0.745, [('oil_p',    0.40, ('game', 'FILLER_OIL', 0.070), 'engine oil pressure')]))


def p_diss_map(d, im):
    """the DISS-15 navigation box: a black face with the drum counters for distance and bearing, the map-angle
    window and its setting knobs"""
    S_, Hh = im.width, im.height
    d.rectangle([0, 0, S_, Hh], fill=(24, 26, 26, 255))
    d.rectangle([S_ * .04, Hh * .06, S_ * .96, Hh * .94], outline=(80, 84, 84, 255), width=3)
    for k, lab in enumerate(('ПУТЬ', 'БОКОВ', 'УГОЛ КАРТЫ')):
        y = Hh * (0.16 + 0.26 * k)
        d.rectangle([S_ * .30, y, S_ * .78, y + Hh * .14], fill=(8, 8, 9, 255), outline=(120, 120, 120, 255), width=2)
        for j in range(4):
            H.text(d, (S_ * (.36 + .12 * j), y + Hh * .07), '0472'[j] if k != 2 else '1385'[j], Hh * .09,
                   fill=(225, 225, 215))
        H.text(d, (S_ * .16, y + Hh * .07), lab, Hh * .055)
        d.ellipse([S_ * .84, y + Hh * .01, S_ * .84 + Hh * .12, y + Hh * .13], fill=(14, 14, 15, 255),
                  outline=(140, 140, 140, 255), width=2)

# the operator's panels sit on the two wings either side of the sight column (CWAM Mi-24D "Bord 118" gunner cockpit,
# seen from behind his seat): flight instruments on the left, the Raduga-F guidance and gun panels on the right
OPERATOR_LAYOUT = [
    ('adi',     -1,  0.075, -0.440, ('game', 'ATTITUDE', 0.080), 'UKT-2 attitude indicator'),
    ('ias',     -1,  0.180, -0.440, ('game', 'AIRSPEED', 0.065), 'airspeed'),
    ('alt',     -1,  0.075, -0.560, ('game', 'ALTIMETER', 0.065), 'altimeter'),
    ('clock',   -1,  0.180, -0.560, ('game', 'CLOCK', 0.050), 'clock'),
    ('warning', -1,  0.125, -0.665, ('annunciator', 0.170, 0.050), 'warning lights'),
    ('guidance', 1,  0.125, -0.450, ('paint_rect', 0.190, 0.075), 'Raduga-F missile guidance panel'),
    ('ammo',     1,  0.075, -0.575, ('game', 'AMMO', 0.060), 'rounds remaining, YakB-12.7'),
    ('selector', 1,  0.180, -0.575, ('paint_rect', 0.090, 0.060), 'weapons selector'),
]
CONSOLES_PILOT = ([(0.16, 'ЗАПУСК', 'guarded'), (0.16, 'ТОПЛИВО', 'toggles'), (0.16, 'Р-863', 'freq'),
                   (0.18, 'АВТОПИЛОТ', 'lamps'), (0.16, 'ОСВЕЩ', 'rotary'), (0.18, 'ГИДРО', 'toggles')],
                  [(0.16, 'АРК-15', 'freq'), (0.20, 'ЭЛЕКТРОПИТАНИЕ', 'toggles'), (0.18, 'АЗС', 'breakers'),
                   (0.16, 'СИГНАЛ', 'lamps'), (0.14, 'КОНДИЦ', 'rotary'), (0.16, 'СПУ', 'rotary')])
CONSOLES_OPERATOR = ([(0.22, 'ОРУЖИЕ', 'guarded'), (0.22, 'ПУЛЕМЕТ', 'toggles'), (0.28, 'ОСВЕЩ', 'rotary'),
                      (0.28, 'СПУ', 'rotary')],
                     [(0.24, 'РАДУГА', 'toggles'), (0.22, 'АЗС', 'breakers'), (0.26, 'СИГНАЛ', 'lamps'),
                      (0.28, 'КИСЛОРОД', 'rotary')])


def operator_details(m, atlas, st):
    """the operator's sights (CWAM Mi-24D gunner cockpit photos; the 2026-10-23 operator brief): the KPS-53AV sighting
    station's black column on the centre line from the floor, its head with the rubber eyepiece raised toward him and
    the two grips with their red triggers either side; the Raduga-F periscope's eyepiece and tube in the right front
    corner. Returns his sight (the HeadUpDisplays entry: the KS-53 reticle in the eyepiece)"""
    import numpy as np
    E = np.array(st['E'], float)
    X, Y, Z = np.array([1.0, 0, 0]), np.array([0, 1.0, 0]), np.array([0, 0, 1.0])
    black, dgrey, grey, red = (atlas.swatch(c) for c in ((22, 23, 24), (40, 42, 44), (118, 122, 118), (180, 40, 30)))
    P = lambda x, y, z: E + np.array([x, y, z])
    foot = st['FLOOR_Y'] + 0.002
    top = -0.42
    m.box(P(0, (foot + top) / 2, 0.50), (X, Y, Z), (0.075, (top - foot) / 2, 0.045), black)      # the column
    m.box(P(0, -0.37, 0.49), (X, Y, Z), (0.085, 0.05, 0.055), dgrey)                             # its head
    tilt = (X, H.unit([0, 1, 0.35]), H.unit([0, -0.35, 1]))
    m.box(P(0, -0.30, 0.47), tilt, (0.03, 0.03, 0.03), dgrey)                                    # the eyepiece mount
    m.ring(P(0, -0.275, 0.437), -tilt[2], tilt[1], 0.022, 0.032, 0.025, black, seg=16)          # its rubber eyecup
    for s in (1, -1):
        g_ax = (X, H.unit([0, 1, -0.45]), H.unit([0, 0.45, 1]))
        m.box(P(s * 0.115, -0.37, 0.45), g_ax, (0.018, 0.055, 0.022), black)                    # the grips
        m.box(P(s * 0.115, -0.315, 0.425), g_ax, (0.012, 0.01, 0.012), red)                     # their triggers' caps
        m.box(P(s * 0.095, -0.37, 0.49), (X, Y, Z), (0.012, 0.013, 0.018), dgrey)               # the grips' arms
    # the Raduga-F periscope: a grey eyepiece box in the right front corner and its tube up toward the roof
    m.box(P(-0.27, -0.04, 0.38), (X, Y, Z), (0.04, 0.055, 0.05), grey)
    m.ring(P(-0.27, -0.03, 0.327), -Z, Y, 0.02, 0.03, 0.02, black, seg=14)
    m.box(P(-0.27, 0.06, 0.40), (X, Y, Z), (0.026, 0.05, 0.026), grey)
    c = P(0, -0.275 + 0.35 * 0.012, 0.437 + 0.012)                    # (in the eyecup, within its opening)
    H.combiner(m, c - tilt[1] * 0.017, tilt[1], -tilt[2], 0.03, 0.034, 'rect', 0.0, (), 0.004, black)
    return dict(centre=c, normal=-tilt[2], up=tilt[1], width=0.03, height=0.034, seat=st['seat'], style='REFLECTOR')


def stations(ac):
    pilot = dict(seat=0, E=ac.eye(0), S=1.25, colours=COLOURS, layout=PILOT_LAYOUT, consoles=CONSOLES_PILOT,
                 PANEL_Z=0.66, LEAN=12, HINGE=0.30, WING=25, WALL_X=0.45, TOP_C=-0.300, TOP_W=-0.320,
                 BOT_C=-0.860, BOT_W=-0.840, FRONT_Z=0.76, FRONT_Y_C=-0.300, FRONT_Y_W=-0.320, COAMING_X=0.40,
                 CONSOLE_Y=-0.700, CONSOLE_IN=0.33, CONSOLE_OUT=0.48, CONSOLE_FRONT=0.42, CONSOLE_FOOT=-1.08,
                 CONSOLE_BACK=-0.25, CUSHION_Y=-0.84, SEAT_Z=0.05, SEAT_FOOT=-1.10, BACK_TOP=0.04,
                 seat_kind='armoured', seat_label='Ми-24Д', STICK_Z=0.40, STICK_FOOT=-1.10, PEDAL_Z=0.78,
                 PEDAL_Y=-1.00, PEDAL_HANG=False, FLOOR_Y=-1.12, COLLECTIVE_X=0.30,
                 hud=dict(z=0.60, w=0.10, h=0.07, lean=30, body_top=-0.09, body_w=0.045, body_d=0.055,
                          style='REFLECTOR'))   # the D's PKV collimator: a compact body on the coaming, its glass above
    operator = dict(seat=1, E=ac.eye(1), S=1.25, colours=COLOURS, layout=OPERATOR_LAYOUT, consoles=CONSOLES_OPERATOR,
                    PANEL_Z=0.56, LEAN=12, HINGE=0.13, WING=32, WALL_X=0.36, TOP_C=-0.370, TOP_W=-0.410,
                    BOT_C=-0.850, BOT_W=-0.810, FRONT_Z=0.62, FRONT_Y_C=-0.390, FRONT_Y_W=-0.410, COAMING_X=0.26,
                    CONSOLE_Y=-0.600, CONSOLE_IN=0.30, CONSOLE_OUT=0.55, CONSOLE_FRONT=0.36, CONSOLE_FOOT=-1.00,
                    CONSOLE_BACK=-0.30, CUSHION_Y=-0.84, SEAT_Z=0.04, SEAT_FOOT=-1.03, BACK_TOP=0.02,
                    seat_kind='plain', seat_label='Ми-24Д', STICK_Z=0.27, STICK_FOOT=-1.03, PEDAL_Z=0.52,
                    PEDAL_Y=-0.86, PEDAL_HANG=True, FLOOR_Y=-1.05, COLLECTIVE=False,
                    sight=None)    # (his KPS-53AV and Raduga-F: operator_details; his seat is not armoured)
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
        (E1, (-0.15, -0.42, 0.30), (0.15, 0.00, 0.72), 0.15, -0.42),      # his old sight's arms and the crossbar they hang on
        (E1, (-0.40, -0.20, 0.28), (-0.12, 0.25, 0.70), 0.40, -0.20)])}   # its grey head hanging at his right
    pilot, operator = stations(ac)
    atlas = H.A.Atlas(VID)
    removed['floor faces repainted'] = HS.repaint_floor(ac, atlas, FLOOR, [
        (E0 + [-0.6, -1.25, -0.6], E0 + [0.6, -1.00, 1.0]), (E1 + [-0.6, -1.17, -0.6], E1 + [0.6, -0.93, 0.9])])
    m = H.Mesh(atlas)
    painters = {'weapons': HS.p_buttons('ВООРУЖЕНИЕ', ['НАР', 'УР', 'БОМБ'], lit=0),
                'guidance': HS.p_buttons('РАДУГА-Ф', ['ПУСК', 'НАВЕД', 'ГОТОВ', 'СБРОС'], lit=2),
                'selector': HS.p_buttons('ВЫБОР', ['УР', 'НАР', 'ПУЛ'], lit=2), 'map': p_diss_map}
    gauges, displays, huds = [], [], []
    for st in (pilot, operator):
        g, d, h = HS.build(m, atlas, st, painters=painters)
        gauges += g; displays += d; huds.append(h)
    HS.bezels(m, atlas, pilot)
    HS.bezels(m, atlas, operator)
    huds[1] = operator_details(m, atlas, operator)
    pm, line = HS.install(ac, m, gauges, displays, huds, [E0, E1], glaze=False)
    print(f'{VID}: removed {removed}; new cockpit {len(pm["polys"])} faces, {len(gauges)} gauges, '
          f'{len(displays)} displays, {len([h for h in huds if h])} sights; {line}')
    if not dry:
        H.write(ac, atlas)


if __name__ == '__main__':
    main(sys.argv[1:])
