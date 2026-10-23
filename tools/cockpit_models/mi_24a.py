#!/usr/bin/env python3
"""Mi-24A cockpits, built by hand for this model (heli_station.py stations, as mi24v.py): the early "greenhouse"
Mi-24, its weapons operator in the glazed nose and the pilot behind him on the left of the wide cabin.

Measured off the model with the cuts and rays (metres from each crewman's eye; the model is about 1.3 times life
size). The pilot's eye is main's (0.5, 3.4, 5.19); the operator's (0, 2.991, 6.938) is the same on both branches.
  * pilot: the cabin open to his right (the flight technician's side), its left wall 0.50-0.56 out from his eye under
    the greenhouse sill (about y -0.55), the floor at y -1.21, the old panel about 0.70 ahead;
  * operator: the nose's walls at x +-0.38-0.40, the floor at y -1.05, the nose glazing 1.0-1.4 ahead.
The model's greenhouse is bare frames: glaze fits a flat pane in every opening between them.
Built (the Mi-24A's cockpits as photographed and described):
  * pilot: the flight and engine instruments, the warning lights and the weapons panel; the PKV collimator gunsight on
    the coaming (its reticle the game's reflector sight, live for the pilot);
  * operator: the PKV sight on the A-12.7 gun's handles before his face (its reflector glass, live for him), a small
    panel with the Falanga guidance box, standby instruments and the rounds counter (he has no flight controls);
  * consoles, armoured seats, the pilot's cyclic, collective and pedals.

Run on the committed originals (work/handbuilt/run_heli.ps1 restores them first).
Usage: python3 tools/cockpit_models/mi_24a.py [--dry-run]
"""
import os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import cockpit_lib as L   # noqa: E402
import handbuilt as H     # noqa: E402
import free_atlas as FA   # noqa: E402
import heli_station as HS  # noqa: E402

VID = 'mi_24a'
PANE_FILL = '0,1'               # (run_heli.ps1: a flat pane in every window the crew sees out of with no glass)
L.INTERIOR_BONES.add('hull')
FA.also_used(VID, 'ad56207c', 'suspended_')

COLOURS = dict(panel=(78, 140, 146), panel_d=(54, 100, 106), wall=(64, 118, 122))   # the Mi-24's turquoise
FLOOR = (46, 50, 50)

# the pilot's panel as photographed head-on (flankers-site Samara Mi-24A, mi-24_11; Monino mi-24_35): one wide teal
# panel, three rows of instruments, the flight group left and centre (two attitude indicators), the engine and rotor
# group right, the external-stores block top left, the warning lights low in the middle, the clock on a post above
# the panel's top left. Columns measured off the photo (its panel 600 px wide = 1.1 m at this model's 1.3x scale);
# a < 0 is to the pilot's left, the centre facet spans +-0.36, the wings run on from there
def _col(a):
    return (0, a) if abs(a) <= 0.36 else ((-1 if a < 0 else 1), abs(a) - 0.36)


def _row(y, items):
    return [(n, *_col(a), y, spec, lab) for n, a, spec, lab in items]


PILOT_LAYOUT = (
    _row(-0.370, [('weapons', -0.405, ('paint_rect', 0.085, 0.075), 'external stores'),
                  ('ias',     -0.31, ('game', 'AIRSPEED', 0.100), 'US-450 airspeed'),
                  ('adi',     -0.16, ('game', 'ATTITUDE', 0.120), 'AGB-3K attitude indicator'),
                  ('vvi',     -0.02, ('game', 'VVI', 0.100), 'VR-30 vertical speed'),
                  ('adi2',     0.12, ('game', 'ATTITUDE', 0.100), 'standby attitude indicator'),
                  ('radalt',   0.23, ('game', 'RADAR_ALT', 0.080), 'RV-3 radio altimeter'),
                  ('volts',    0.33, ('game', 'FILLER_VOLTS', 0.075), 'voltmeter')]) +
    _row(-0.510, [('turn',    -0.41, ('game', 'TURN_SLIP', 0.080), 'turn and slip'),
                  ('hyd',     -0.31, ('game', 'FILLER_HYDRAULIC', 0.100), 'hydraulic pressure'),
                  ('hsi',     -0.16, ('game', 'HEADING', 0.115), 'compass / course indicator'),
                  ('alt',     -0.02, ('game', 'ALTIMETER', 0.120), 'VD-10 altimeter'),
                  ('nr',       0.12, ('game', 'ENGINE_RPM', 0.100), 'engine speed'),
                  ('egt_l',    0.23, ('game', 'EGT', 0.085), 'gas temperature, left engine'),
                  ('egt_r',    0.33, ('game', 'EGT', 0.085), 'gas temperature, right engine'),
                  ('oil_t',    0.43, ('game', 'FILLER_TEMP', 0.080), 'gearbox oil temperature')]) +
    _row(-0.650, [('rotor',   -0.41, ('game', 'FILLER_RPM', 0.085), 'rotor speed'),
                  ('fuelp',   -0.30, ('game', 'FILLER_FUEL', 0.090), 'fuel pressure'),
                  ('oxy',     -0.22, ('game', 'FILLER_OXYGEN', 0.070), 'oxygen'),
                  ('oil_p',   -0.11, ('game', 'FILLER_OIL', 0.090), 'oil pressure'),
                  ('temp2',    0.12, ('game', 'FILLER_TEMP', 0.090), 'engine oil temperature'),
                  ('oil_p2',   0.23, ('game', 'FILLER_OIL', 0.090), 'engine oil pressure'),
                  ('fuel',     0.42, ('game', 'FUEL_QTY', 0.090), 'fuel quantity')]) +
    _row(-0.780, [('warning',  0.02, ('annunciator', 0.300, 0.075), 'warning lights')]))
PILOT_CLOCK = (-0.34, -0.215)          # the AChS clock on its post above the panel's top left: (x from the eye, y)
PILOT_SWITCHES = [(a, -0.835) for a in (-0.30, -0.24, -0.18, 0.20, 0.26, 0.32)]   # guarded switches under the lamps
OPERATOR_LAYOUT = [
    ('guidance', 0,  0.000, -0.560, ('paint_rect', 0.200, 0.080), 'Falanga-M missile guidance box'),
    ('warning',  0,  0.000, -0.700, ('annunciator', 0.180, 0.060), 'warning lights'),
    ('adi',     -1,  0.070, -0.460, ('game', 'ATTITUDE', 0.070), 'standby attitude'),
    ('ias',     -1,  0.160, -0.460, ('game', 'AIRSPEED', 0.058), 'airspeed'),
    ('alt',     -1,  0.110, -0.600, ('game', 'ALTIMETER', 0.058), 'altimeter'),
    ('ammo',     1,  0.070, -0.460, ('game', 'AMMO', 0.058), 'rounds remaining, A-12.7'),
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
CABIN_CONSOLE = [(0.16, 'ЗАПУСК АИ-9', 'guarded'), (0.16, 'ПОЖАРН', 'guarded'), (0.18, 'ТОПЛИВО', 'toggles'),
                 (0.16, 'ПРОТИВООБЛЕД', 'toggles'), (0.16, 'АРК-15', 'freq'), (0.16, 'ОСВЕЩ', 'rotary')]
CABIN_WALL = [(0.18, 'ЭЛЕКТРОПИТАНИЕ', 'toggles'), (0.22, 'АЗС', 'breakers'), (0.20, 'АЗС', 'breakers'),
              (0.18, 'СИГНАЛИЗАЦИЯ', 'lamps'), (0.20, 'ГЕНЕРАТОРЫ', 'rotary')]


def cabin_panels(m, atlas, E0):
    """the right-hand side of the pilot's cabin (the flight technician's side), built by hand: a console along the
    wall (engine start, fire, fuel, de-icing, ARK-15, lighting) under the side window, and behind him the electrical
    rack facing forward (power, the circuit breakers, the warning lamps and the generators), where the old panels
    were"""
    import numpy as np
    x_in, x_wall = E0[0] - 1.30, E0[0] - 1.42                       # the console's inner face, the cabin wall
    cy, fy = E0[1] - 0.70, E0[1] - 1.33                             # the console top, the cabin floor (lower here)
    zb, zf = E0[2] - 0.55, E0[2] + 0.90
    top = atlas.paint(70, 360, HS.p_console(CABIN_CONSOLE, False, COLOURS['panel']))
    m.face([[x_in, cy, zb], [x_wall, cy, zb], [x_wall, cy, zf], [x_in, cy, zf]],
           [m.uv(top, 0, 0), m.uv(top, 1, 0), m.uv(top, 1, 1), m.uv(top, 0, 1)], normal=[0, 1, 0])
    wall, dark = atlas.swatch(COLOURS['wall']), atlas.swatch(COLOURS['panel_d'])
    m.flat([[x_in, fy, zb], [x_in, fy, zf], [x_in, cy, zf], [x_in, cy, zb]], wall, normal=[1, 0, 0])
    m.flat([[x_in, fy, zb], [x_wall, fy, zb], [x_wall, cy, zb], [x_in, cy, zb]], wall, normal=[0, 0, -1])
    m.flat([[x_in, fy, zf], [x_wall, fy, zf], [x_wall, cy, zf], [x_in, cy, zf]], wall, normal=[0, 0, 1])
    X, Y, Z = np.array([1.0, 0, 0]), np.array([0, 1.0, 0]), np.array([0, 0, 1.0])
    rack = (E0[0] - 1.42, E0[0] - 0.75, E0[1] - 1.30, E0[1] - 0.12, E0[2] - 0.98, E0[2] - 0.57)
    face = atlas.paint(220, 280, HS.p_console(CABIN_WALL, False, COLOURS['panel']))   # its rows top to bottom
    c = [(rack[0] + rack[1]) / 2, (rack[2] + rack[3]) / 2, (rack[4] + rack[5]) / 2]
    half = ((rack[1] - rack[0]) / 2, (rack[3] - rack[2]) / 2, (rack[5] - rack[4]) / 2)
    m.box(c, (X, Y, Z), half, wall, front=(2, 1, face, X, Y))       # the electrical rack behind him, its face forward


def pilot_details(m, atlas, st):
    """what makes the pilot's panel a panel and not a painted board: a raised bezel round every instrument, the clock
    on its post above the coaming, the row of guarded switches under the warning lights; returns the clock gauge"""
    import numpy as np
    S = HS.Station(st)
    E = S.E
    bezel, black, red, metal = (atlas.swatch(c) for c in ((30, 31, 32), (24, 25, 26), (176, 34, 28), (128, 132, 134)))
    for name, side, a, y, spec, _ in st['layout']:
        if spec[0] != 'game':
            continue
        f = S.fac[side]
        c = S.at(side, a, y)
        r0 = spec[2] / 2 * 1.06
        m.ring(c, f.N, f.U, r0, r0 + 0.009, 0.007, bezel, seg=20)
    # the clock on its post: a square case on a short column standing on the coaming's lip, its face to the pilot
    f = S.fac[0]
    cx, cy = PILOT_CLOCK
    c = S.at(0, -cx if False else cx, cy) + f.N * 0.03
    top = E[1] + st['TOP_C'] + 0.03
    m.box(c - f.N * 0.02, (f.R, f.U, f.N), (0.05, 0.05, 0.02), black)
    m.box(np.array([c[0], (c[1] - 0.05 + top) / 2, c[2] - 0.02 * f.N[2]]), (f.R, f.U, f.N),
          (0.012, max(0.005, (c[1] - 0.05 - top) / 2), 0.012), black)
    clock = {'Seat': st['seat'], 'Kind': 'CLOCK', 'Center': H.r5(c + f.N * 0.002), 'Normal': H.r5(f.N), 'Up': H.r5(f.U),
             'Diameter': 0.075, 'Depth': 0.02}
    # guarded switches: a base plate, the toggle standing up, the red guard hinged over it
    for a, y in PILOT_SWITCHES:
        side, aa = _col(a)
        f = S.fac[side]
        c = S.at(side, aa, y)
        m.box(c + f.N * 0.002, (f.R, f.U, f.N), (0.016, 0.022, 0.002), black)
        m.box(c + f.N * 0.014 + f.U * 0.004, (f.R, f.U, f.N), (0.003, 0.003, 0.012), metal)
        m.box(c + f.N * 0.012 - f.U * 0.006, (f.R, f.U, f.N), (0.012, 0.016, 0.003), red)
    return clock


def operator_details(m, atlas, st):
    """the navigator-operator's sighting gear (Samara and Monino photos, the 16va description): the Falanga guidance
    sight hanging from the roof frame on the centre line, its rubber eyecup at his eye 0.4 m ahead with the live
    reflector reticle in its window, two brown grips under it; the A-12.7 gun mount's grey box and grips low at the
    front left. Returns his sight (the HeadUpDisplays entry)"""
    import numpy as np
    E = np.array(st['E'], float)
    X, Y, Z = np.array([1.0, 0, 0]), np.array([0, 1.0, 0]), np.array([0, 0, 1.0])
    dgrey, black, grey, brown = (atlas.swatch(c) for c in ((44, 46, 48), (24, 25, 26), (108, 112, 108), (110, 66, 38)))
    P = lambda x, y, z: E + np.array([x, y, z])
    m.box(P(0, 0.02, 0.50), (X, Y, Z), (0.055, 0.075, 0.065), dgrey)              # the sight body
    m.box(P(0, 0.30, 0.52), (X, Y, Z), (0.016, 0.205, 0.016), dgrey)              # its hanger up to the roof frame
    m.ring(P(0, 0, 0.432), -Z, Y, 0.032, 0.045, 0.03, black, seg=16)              # the rubber eyecup toward him
    for s in (1, -1):                                                               # the two brown grips under it
        m.box(P(s * 0.085, -0.10, 0.46), (X, Y, Z), (0.018, 0.055, 0.022), brown)
        m.box(P(s * 0.05, -0.05, 0.47), (X, Y, Z), (0.035, 0.008, 0.012), dgrey)
    m.box(P(0.20, -0.36, 0.56), (X, Y, Z), (0.06, 0.09, 0.13), grey)              # the A-12.7 mount's box, front left
    for z in (0.40, 0.46):
        m.box(P(0.20, -0.24, z), (X, Y, Z), (0.012, 0.035, 0.012), black)         # its two grips
    g_up, g_n = Y, Z
    c = P(0, 0, 0.418)                  # (in the eyecup, within its opening as seen from his eye)
    H.combiner(m, c - g_up * 0.025, g_up, -g_n, 0.045, 0.05, 'rect', 0.0, (), 0.004, black)   # (no frame: the eyecup is its frame)
    return dict(centre=c, normal=-g_n, up=g_up, width=0.045, height=0.05, seat=st['seat'], style='REFLECTOR')


def remove_inner(ac, boxes):
    """the model's faces (not the new cockpit's) whose centre lies in an eye-relative (E, lo, hi) box, except those
    facing out to the right (stored normal within 45 degrees of -x) at least 1.40 m out from the eye: the skin"""
    import numpy as np
    n = 0
    for b in ac.bones:
        pm = b.get('poly_mesh')
        if not pm or not pm.get('polys') or b['name'].endswith('__cockpit') or b['name'] != 'hull':
            continue
        P = np.array(pm['positions'], float) * np.array([1, 1, -1]) / 16.0
        N = np.array(pm['normals'], float) * np.array([1, 1, -1])
        out = []
        for poly in pm['polys']:
            if poly:
                c = P[[v[0] for v in poly]].mean(0)
                nv = N[[v[1] for v in poly]].mean(0)
                nv = nv / (np.linalg.norm(nv) or 1)
                inside = any(np.all(c - np.array(E) >= lo) and np.all(c - np.array(E) <= hi) for E, lo, hi in boxes)
                if inside and (nv[0] > -0.7 or c[0] - boxes[0][0][0] > -1.40):   # (the skin: facing out, 1.40+ m out)
                    n += 1
                    continue
            out.append(poly)
        pm['polys'] = out
    return n


def stations(ac):
    pilot = dict(seat=0, E=ac.eye(0), S=1.25, colours=COLOURS, layout=PILOT_LAYOUT, consoles=CONSOLES_PILOT,
                 PANEL_Z=0.70, LEAN=8, HINGE=0.36, WING=10, WALL_X=0.45, TOP_C=-0.295, TOP_W=-0.300,
                 BOT_C=-0.870, BOT_W=-0.860, FRONT_Z=0.86, FRONT_Y_C=-0.330, FRONT_Y_W=-0.350, COAMING_X=0.42,
                 CONSOLE_Y=-0.700, CONSOLE_IN=0.33, CONSOLE_OUT=0.48, CONSOLE_FRONT=0.44, CONSOLE_FOOT=-1.15,
                 CONSOLE_BACK=-0.25, CUSHION_Y=-0.92, SEAT_Z=0.05, SEAT_FOOT=-1.19, BACK_TOP=0.04,
                 seat_kind='armoured', seat_label='Ми-24А', STICK_Z=0.40, STICK_FOOT=-1.19, PEDAL_Z=0.72,
                 PEDAL_Y=-1.02, PEDAL_HANG=True, FLOOR_Y=-1.21,
                 hud=dict(z=0.62, w=0.10, h=0.07, lean=30, body_top=-0.09, body_w=0.045, body_d=0.055, style='REFLECTOR'))   # the PKV: a compact collimator body on the coaming, its glass just above
    operator = dict(seat=1, E=ac.eye(1), S=1.25, colours=COLOURS, layout=OPERATOR_LAYOUT, consoles=CONSOLES_OPERATOR,
                    PANEL_Z=0.70, LEAN=12, HINGE=0.14, WING=32, WALL_X=0.32, TOP_C=-0.450, TOP_W=-0.480,
                    BOT_C=-0.900, BOT_W=-0.870, FRONT_Z=0.82, FRONT_Y_C=-0.470, FRONT_Y_W=-0.490, COAMING_X=0.24,
                    CONSOLE_Y=-0.620, CONSOLE_IN=0.23, CONSOLE_OUT=0.32, CONSOLE_FRONT=0.40, CONSOLE_FOOT=-0.95,
                    CONSOLE_BACK=-0.08, CUSHION_Y=-0.80, SEAT_Z=0.02, SEAT_FOOT=-1.03, BACK_TOP=0.02,
                    seat_kind='armoured', seat_label='Ми-24А', STICK_Z=0.30, STICK_FOOT=-1.03, COLLECTIVE=False,
                    FLOOR_Y=-1.05, sight=None)    # (his own stick in its boot, as photographed; his sight: operator_details)
    return pilot, operator

def main(argv):
    dry = '--dry-run' in argv
    ac = L.Aircraft(VID)
    E0, E1 = ac.eye(0), ac.eye(1)
    removed = {'old interior': HS.remove_old(ac, [
        (E0, (-0.50, -1.19, -0.62), (0.50, -0.55, 1.00), 0.50, -0.55),    # the pilot's tub up to the greenhouse sill
        (E0, (-0.36, -0.55, -0.62), (0.36, 0.30, 0.95), 0.36, -0.55),     # his old seat, panel and sight above it
        (E1, (-0.40, -1.03, -0.60), (0.40, -0.55, 0.95), 0.40, -0.55),    # the operator's nose up to its sill
        (E1, (-0.62, -1.03, -0.60), (0.62, -0.58, 0.40), 0.62, -0.58),    # its old consoles where the cabin is wider
        (E1, (-0.30, -0.55, -0.60), (0.30, 0.20, 0.70), 0.30, -0.55),     # his old seat and sight above it
        (E0, (-0.76, -0.20, 1.24), (-0.24, 0.03, 1.56), 2.0, -9.0)])}     # a loose box over the operator's head
    # the old right-hand cabin panels, the rack behind him and the boxes between, but not the cabin's right wall:
    # that side is built of boxes whose outer faces are the fuselage skin (1.44-1.69 m out), so every face facing out
    # to the right stays (bert's rule: the exterior never changes)
    removed['old right-hand cabin'] = remove_inner(ac, [
        (E0, (-1.72, -1.33, -0.62), (-1.36, 0.22, 1.25)),
        (E0, (-1.71, -1.65, -1.25), (-0.70, 0.45, -0.55)),
        (E0, (-1.36, -1.33, -0.62), (-0.40, 0.56, 1.25))])
    pilot, operator = stations(ac)
    atlas = H.A.Atlas(VID)
    removed['floor faces repainted'] = HS.repaint_floor(ac, atlas, FLOOR, [
        (E0 + [-0.5, -1.33, -0.6], E0 + [0.5, -1.10, 1.0]), (E1 + [-0.4, -1.17, -0.6], E1 + [0.4, -0.93, 0.9]),
        (E0 + [-1.7, -1.45, -0.6], E0 + [-0.4, -1.25, 1.25])])
    m = H.Mesh(atlas)
    painters = {'weapons': HS.p_buttons('ВООРУЖЕНИЕ', ['НАР', 'УР', 'БОМБ'], lit=0),
                'guidance': HS.p_buttons('ФАЛАНГА-М', ['ПУСК', 'НАВЕД', 'ГОТОВ', 'СБРОС'], lit=2),
                'selector': HS.p_buttons('ВЫБОР', ['УР', 'НАР', 'ПУЛ'], lit=2)}
    gauges, displays, huds = [], [], []
    for st in (pilot, operator):
        g, d, h = HS.build(m, atlas, st, painters=painters)
        gauges += g; displays += d; huds.append(h)
    gauges.append(pilot_details(m, atlas, pilot))
    huds[1] = operator_details(m, atlas, operator)
    cabin_panels(m, atlas, E0)
    pm, line = HS.install(ac, m, gauges, displays, huds, [E0, E1])
    print(f'{VID}: removed {removed}; new cockpit {len(pm["polys"])} faces, {len(gauges)} gauges, '
          f'{len(displays)} displays, {len([h for h in huds if h])} sights; {line}')
    if not dry:
        H.write(ac, atlas)


if __name__ == '__main__':
    main(sys.argv[1:])
