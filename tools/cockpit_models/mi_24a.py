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
    x_in, x_wall = E0[0] - 1.40, E0[0] - 1.66                       # the console's inner face, the cabin wall
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
    rack = (E0[0] - 1.58, E0[0] - 0.75, E0[1] - 1.30, E0[1] - 0.12, E0[2] - 0.98, E0[2] - 0.57)
    face = atlas.paint(220, 280, HS.p_console(CABIN_WALL, False, COLOURS['panel']))   # its rows top to bottom
    c = [(rack[0] + rack[1]) / 2, (rack[2] + rack[3]) / 2, (rack[4] + rack[5]) / 2]
    half = ((rack[1] - rack[0]) / 2, (rack[3] - rack[2]) / 2, (rack[5] - rack[4]) / 2)
    m.box(c, (X, Y, Z), half, wall, front=(2, 1, face, X, Y))       # the electrical rack behind him, its face forward


def remove_inner(ac, boxes):
    """the model's faces (not the new cockpit's) whose centre lies in an eye-relative (E, lo, hi) box, except those
    facing out to the right (stored normal within 45 degrees of -x): the skin"""
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
                if inside and nv[0] > -0.7:
                    n += 1
                    continue
            out.append(poly)
        pm['polys'] = out
    return n


def stations(ac):
    pilot = dict(seat=0, E=ac.eye(0), S=1.25, colours=COLOURS, layout=PILOT_LAYOUT, consoles=CONSOLES_PILOT,
                 PANEL_Z=0.66, LEAN=12, HINGE=0.18, WING=30, WALL_X=0.44, TOP_C=-0.330, TOP_W=-0.370,
                 BOT_C=-0.950, BOT_W=-0.910, FRONT_Z=0.80, FRONT_Y_C=-0.370, FRONT_Y_W=-0.390, COAMING_X=0.32,
                 CONSOLE_Y=-0.700, CONSOLE_IN=0.33, CONSOLE_OUT=0.48, CONSOLE_FRONT=0.44, CONSOLE_FOOT=-1.15,
                 CONSOLE_BACK=-0.25, CUSHION_Y=-0.92, SEAT_Z=0.05, SEAT_FOOT=-1.19, BACK_TOP=0.04,
                 seat_kind='armoured', seat_label='Ми-24А', STICK_Z=0.40, STICK_FOOT=-1.19, PEDAL_Z=0.72,
                 PEDAL_Y=-1.02, PEDAL_HANG=True, FLOOR_Y=-1.21,
                 hud=dict(z=0.66, w=0.12, h=0.11, lean=30, body_top=-0.255, body_w=0.075, body_d=0.04, style='REFLECTOR'))
    operator = dict(seat=1, E=ac.eye(1), S=1.25, colours=COLOURS, layout=OPERATOR_LAYOUT, consoles=CONSOLES_OPERATOR,
                    PANEL_Z=0.70, LEAN=12, HINGE=0.14, WING=32, WALL_X=0.32, TOP_C=-0.450, TOP_W=-0.480,
                    BOT_C=-0.900, BOT_W=-0.870, FRONT_Z=0.82, FRONT_Y_C=-0.470, FRONT_Y_W=-0.490, COAMING_X=0.24,
                    CONSOLE_Y=-0.620, CONSOLE_IN=0.23, CONSOLE_OUT=0.32, CONSOLE_FRONT=0.40, CONSOLE_FOOT=-0.95,
                    CONSOLE_BACK=-0.08, CUSHION_Y=-0.80, SEAT_Z=0.02, SEAT_FOOT=-1.03, BACK_TOP=0.02,
                    seat_kind='armoured', seat_label='Ми-24А', STICK_Z=None, COLLECTIVE=False, FLOOR_Y=-1.05,
                    sight=dict(z=0.46, y=-0.120, w=0.12, h=0.08, d=0.10,
                               glass=dict(z=0.42, w=0.075, h=0.065, lean=25, style='REFLECTOR')))
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
        (E1, (-0.30, -0.55, -0.60), (0.30, 0.20, 0.70), 0.30, -0.55)])}   # his old seat and sight above it
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
    cabin_panels(m, atlas, E0)
    pm, line = HS.install(ac, m, gauges, displays, huds, [E0, E1])
    print(f'{VID}: removed {removed}; new cockpit {len(pm["polys"])} faces, {len(gauges)} gauges, '
          f'{len(displays)} displays, {len([h for h in huds if h])} sights; {line}')
    if not dry:
        H.write(ac, atlas)


if __name__ == '__main__':
    main(sys.argv[1:])
