#!/usr/bin/env python3
"""Mi-26 flight deck, built by hand for this model (heli_station.py stations): the commander on the left and the
co-pilot on the right under the wide windscreen, the flight engineer and the navigator behind them.

Measured off the model with the cuts and rays (metres from each crewman's eye; the model is about 1.1 times life
size). The pilots' eyes are main's (+-0.75, 4.65, 11.95); the crewmen's (+-1.0, 4.303, 10.375) are the same on both
branches.
  * pilots: the floor at y -1.40, the side walls 0.63-0.79 outboard (the deck open between the two seats), the old
    panel 1.05-1.30 ahead with its top at y -0.55 under the windscreen, the old seats' backs 0.1-0.2 behind the eyes;
  * crewmen: the floor at y -1.05, the walls 0.71-0.74 outboard, an old desk 0.65-0.80 ahead.
Built (the Mi-26's flight deck as photographed and described):
  * each pilot his own panel: the attitude director over the situation indicator, airspeed, altimeter, vertical
    speed, radio altimeter, the rotor and engines' dials, fuel and his warning lights; the coaming, side consoles, a
    crew seat, the cyclic, the collective and the pedals;
  * the flight engineer (behind the commander): the engines' tachometers and gas temperatures, fuel, the hydraulic and
    electrical panels and his warning lights; the navigator (behind the co-pilot): the map display, the navigation
    computer's panel, the clock, airspeed and altimeter; a seat each, no flight controls.
Instruments the game draws (CockpitGauges, FlightDisplays) are live, each to its own seat. No canopy is made: the
windows keep their frames and get glass.

Run on the committed originals (work/handbuilt/run_heli.ps1 restores them first).
Usage: python3 tools/cockpit_models/mi_26.py [--dry-run]
"""
import os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import cockpit_lib as L   # noqa: E402
import handbuilt as H     # noqa: E402
import free_atlas as FA   # noqa: E402
import heli_station as HS  # noqa: E402

VID = 'mi_26'
L.INTERIOR_BONES.add('hull')
FA.also_used(VID, 'ad56207c', 'suspended_')

COLOURS = dict(panel=(78, 140, 146), panel_d=(54, 100, 106), wall=(64, 118, 122))   # Soviet cockpit turquoise
FLOOR = (46, 50, 50)

PILOT_LAYOUT = [
    ('adi',      0, -0.070, -0.640, ('game', 'ATTITUDE', 0.100), 'KPP attitude director'),
    ('hsi',      0, -0.070, -0.800, ('game', 'HEADING', 0.095), 'PNP situation indicator'),
    ('vvi',      0,  0.090, -0.640, ('game', 'VVI', 0.072), 'VR-30 vertical speed'),
    ('radalt',   0,  0.090, -0.790, ('game', 'RADAR_ALT', 0.068), 'radio altimeter'),
    ('warning',  0,  0.000, -0.950, ('annunciator', 0.260, 0.070), 'warning lights'),
    ('ias',     -1,  0.090, -0.650, ('game', 'AIRSPEED', 0.072), 'airspeed'),
    ('alt',     -1,  0.230, -0.650, ('game', 'ALTIMETER', 0.072), 'altimeter'),
    ('clock',   -1,  0.160, -0.800, ('game', 'CLOCK', 0.048), 'clock'),
    ('rotor',    1,  0.090, -0.650, ('game', 'ENGINE_RPM', 0.072), 'rotor and engine speed'),
    ('egt_l',    1,  0.215, -0.650, ('game', 'EGT', 0.058), 'gas temperature, left engine'),
    ('egt_r',    1,  0.215, -0.790, ('game', 'EGT', 0.058), 'gas temperature, right engine'),
    ('fuel',     1,  0.090, -0.800, ('game', 'FUEL_QTY', 0.062), 'fuel quantity'),
]
ENGINEER_LAYOUT = [
    ('rpm',      0, -0.070, -0.520, ('game', 'ENGINE_RPM', 0.065), 'engine tachometers'),
    ('egt',      0,  0.070, -0.520, ('game', 'EGT', 0.060), 'gas temperatures'),
    ('warning',  0,  0.000, -0.680, ('annunciator', 0.200, 0.060), 'engineer warning lights'),
    ('fuel',    -1,  0.070, -0.530, ('game', 'FUEL_QTY', 0.058), 'fuel quantity'),
    ('hyd',     -1,  0.070, -0.660, ('game', 'FILLER_HYDRAULIC', 0.050), 'hydraulic pressures'),
    ('elec',     1,  0.075, -0.590, ('paint_rect', 0.100, 0.090), 'electrical panel'),
]
NAVIGATOR_LAYOUT = [
    ('map',      0,  0.000, -0.540, ('display', 'RADAR', 0.140, 'CRT'), 'map display'),
    ('warning',  0,  0.000, -0.720, ('annunciator', 0.180, 0.050), 'navigator warning lights'),
    ('clock',   -1,  0.070, -0.520, ('game', 'CLOCK', 0.048), 'clock'),
    ('ias',     -1,  0.070, -0.640, ('game', 'AIRSPEED', 0.056), 'airspeed'),
    ('alt',      1,  0.070, -0.520, ('game', 'ALTIMETER', 0.056), 'altimeter'),
    ('navc',     1,  0.075, -0.650, ('paint_rect', 0.100, 0.070), 'navigation computer'),
]
CONSOLES = ([(0.20, 'ЗАПУСК', 'guarded'), (0.20, 'ТОПЛИВО', 'toggles'), (0.20, 'Р-828', 'freq'),
             (0.20, 'АВТОПИЛОТ', 'lamps'), (0.20, 'ОСВЕЩ', 'rotary')],
            [(0.20, 'АРК', 'freq'), (0.20, 'ЭЛЕКТРОПИТАНИЕ', 'toggles'), (0.20, 'АЗС', 'breakers'),
             (0.20, 'СИГНАЛ', 'lamps'), (0.20, 'ДОКУМЕНТ', 'rotary')])


def stations(ac):
    pilot = dict(S=1.1, colours=COLOURS, layout=PILOT_LAYOUT, consoles=CONSOLES,
                 PANEL_Z=1.12, LEAN=15, HINGE=0.25, WING=28, WALL_X=0.46, TOP_C=-0.530, TOP_W=-0.560,
                 BOT_C=-1.100, BOT_W=-0.880, FRONT_Z=1.24, FRONT_Y_C=-0.540, FRONT_Y_W=-0.555, COAMING_X=0.38,
                 CONSOLE_Y=-0.780, CONSOLE_IN=0.36, CONSOLE_OUT=0.58, CONSOLE_FRONT=0.55, CONSOLE_FOOT=-1.20,
                 CONSOLE_BACK=-0.15, CUSHION_Y=-0.95, SEAT_Z=0.12, SEAT_FOOT=-1.38, BACK_TOP=0.06,
                 seat_kind='armoured', seat_label='Ми-26', STICK_Z=0.48, STICK_FOOT=-1.40, PEDAL_Z=0.98,
                 PEDAL_Y=-1.26, PEDAL_HANG=False, FLOOR_Y=-1.40, COLLECTIVE_X=0.30)
    crew = dict(S=1.1, colours=COLOURS, consoles=None, LEAN=15, HINGE=0.14, WING=30, WALL_X=0.28,
                PANEL_Z=0.56, TOP_C=-0.440, TOP_W=-0.460, BOT_C=-0.800, BOT_W=-0.780, FRONT_Z=0.66,
                FRONT_Y_C=-0.440, FRONT_Y_W=-0.450, COAMING_X=0.26, CUSHION_Y=-0.70, SEAT_Z=0.10, SEAT_FOOT=-1.03,
                BACK_TOP=0.04, seat_kind='armoured', seat_label='Ми-26', STICK_Z=None, COLLECTIVE=False,
                FLOOR_Y=-1.05, hoods=False)
    return [dict(pilot, seat=0, E=ac.eye(0)), dict(pilot, seat=1, E=ac.eye(1)),
            dict(crew, seat=2, E=ac.eye(2), layout=ENGINEER_LAYOUT), dict(crew, seat=3, E=ac.eye(3), layout=NAVIGATOR_LAYOUT)]


def main(argv):
    dry = '--dry-run' in argv
    ac = L.Aircraft(VID)
    eyes = [ac.eye(k) for k in range(4)]
    boxes = []
    for E in eyes[:2]:
        boxes += [(E, (-0.62, -1.38, -0.45), (0.62, -0.50, 1.45), 0.62, -0.50),   # the pilots' tubs and old panel
                  (E, (-0.35, -0.50, -0.45), (0.35, 0.30, 0.60), 0.35, -0.50)]    # their old seats
    for E in eyes[2:]:
        boxes += [(E, (-0.45, -1.03, -0.50), (0.45, -0.40, 1.40), 0.45, -0.40),   # the crewmen's desks and seats
                  (E, (-0.30, -0.40, -0.50), (0.30, 0.30, 0.00), 0.30, -0.40),
                  (E, (-0.60, -0.40, 0.00), (0.60, 0.15, 1.40), 0.60, -0.40)]     # the old shelves over their desks
    removed = {'old interior': HS.remove_old(ac, boxes)}
    atlas = H.A.Atlas(VID)
    removed['floor faces repainted'] = HS.repaint_floor(ac, atlas, FLOOR, [
        (eyes[0] + [-2.0, -1.55, -0.5], eyes[0] + [0.6, -1.25, 1.4]), (eyes[2] + [-2.5, -1.20, -0.6], eyes[2] + [0.6, -0.90, 1.0])])
    m = H.Mesh(atlas)
    painters = {'elec': HS.p_buttons('ЭЛЕКТРО', ['ГЕН1', 'ГЕН2', 'АКК', 'ВСУ'], lit=0),
                'navc': HS.p_buttons('НАВИГАЦИЯ', ['ДИСС', 'ГМК', 'КУРС'], lit=1)}
    gauges, displays = [], []
    for st in stations(ac):
        g, d, h = HS.build(m, atlas, st, painters=painters)
        gauges += g; displays += d
    pm, line = HS.install(ac, m, gauges, displays, [], eyes)
    print(f'{VID}: removed {removed}; new cockpit {len(pm["polys"])} faces, {len(gauges)} gauges, '
          f'{len(displays)} displays; {line}')
    if not dry:
        H.write(ac, atlas)


if __name__ == '__main__':
    main(sys.argv[1:])
