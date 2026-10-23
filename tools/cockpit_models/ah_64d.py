#!/usr/bin/env python3
"""AH-64D cockpits, built by hand for this model (heli_station.py stations): the copilot/gunner (CPG) in front, the
pilot behind and above him, each at his own panel.

Measured off the model with the cuts and rays (metres from each crewman's eye; the model is close to life size). The
CPG's eye is main's (0, 2.72, 2.70); the pilot's (0, 2.952, 0.993) is the same on both branches.
  * pilot: canopy sides at x +-0.50-0.65, the sill near y -0.50, the floor at y -1.02, the old seat's cushion at
    y -0.75; the old panel 0.95-1.12 ahead under the blast shield, the CPG's seat back beyond it;
  * CPG: walls at x +-0.42-0.47, the floor at y -1.00, his old seat back 0.20-0.56 behind the eye, the old panel and
    the optical relay tube about 0.78 ahead; his windscreen and roof are open in the model (glazed here by glaze).
Built (the AH-64D's cockpits as photographed and described):
  * pilot: two multipurpose displays either side of the centre over the up-front display, the standby attitude,
    airspeed and altimeter, the engine and rotor dials, fuel and the warning lights; the IHADSS monocle: a small
    combiner just ahead of his eye on his straight-ahead line carrying the game's HUD picture (the pilot's only);
  * CPG: the TADS optical relay tube in the middle of his panel (its eyepiece hood toward his face, the handgrips
    either side), the two multipurpose displays beside it, standby instruments and the rounds counter;
  * both: consoles, armoured crew seats, the cyclic, the collective on the left and the pedals.
Instruments the game draws (CockpitGauges, FlightDisplays) are live, each to its own seat. No canopy is made: the
model keeps its own canopy and frames; they get glass (model_glass.glaze).

Run on the committed originals (work/handbuilt/run_heli.ps1 restores them first).
Usage: python3 tools/cockpit_models/ah_64d.py [--dry-run]
"""
import os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import cockpit_lib as L   # noqa: E402
import handbuilt as H     # noqa: E402
import free_atlas as FA   # noqa: E402
import heli_station as HS  # noqa: E402

VID = 'ah_64d'
L.INTERIOR_BONES.add('hull')
FA.also_used(VID, 'ad56207c', 'suspended_')
INTERIOR_FROM = 4800           # the model's shell and frames come first, its old interior parts after them

COLOURS = dict(panel=(40, 42, 44), panel_d=(28, 29, 30), wall=(70, 74, 76))
FLOOR = (44, 46, 48)

PILOT_LAYOUT = [
    ('mpd_l',    0, -0.090, -0.330, ('display', 'PFD', 0.150, 'LCD'), 'left multipurpose display'),
    ('mpd_r',    0,  0.090, -0.330, ('display', 'STORES', 0.150, 'LCD'), 'right multipurpose display'),
    ('ufd',      0,  0.000, -0.500, ('paint_rect', 0.200, 0.070), 'up-front display'),
    ('warning',  0,  0.000, -0.630, ('annunciator', 0.200, 0.060), 'master caution / warning'),
    ('adi',     -1,  0.075, -0.320, ('game', 'ATTITUDE', 0.075), 'standby attitude'),
    ('ias',     -1,  0.185, -0.320, ('game', 'AIRSPEED', 0.060), 'standby airspeed'),
    ('alt',     -1,  0.075, -0.460, ('game', 'ALTIMETER', 0.060), 'standby altimeter'),
    ('radalt',  -1,  0.185, -0.460, ('game', 'RADAR_ALT', 0.056), 'radar altimeter'),
    ('clock',   -1,  0.130, -0.590, ('game', 'CLOCK', 0.045), 'clock'),
    ('rotor',    1,  0.075, -0.320, ('game', 'ENGINE_RPM', 0.060), 'rotor / engine speed'),
    ('vvi',      1,  0.185, -0.320, ('game', 'VVI', 0.056), 'vertical speed'),
    ('egt_l',    1,  0.075, -0.460, ('game', 'EGT', 0.050), 'TGT, engine 1'),
    ('egt_r',    1,  0.185, -0.460, ('game', 'EGT', 0.050), 'TGT, engine 2'),
    ('fuel',     1,  0.130, -0.590, ('game', 'FUEL_QTY', 0.055), 'fuel quantity'),
]
CPG_LAYOUT = [
    ('mpd_l',    0, -0.105, -0.520, ('display', 'STORES', 0.120, 'LCD'), 'left multipurpose display'),
    ('mpd_r',    0,  0.105, -0.520, ('display', 'RADAR', 0.120, 'LCD'), 'right multipurpose display'),
    ('warning',  0,  0.000, -0.700, ('annunciator', 0.200, 0.060), 'master caution / warning'),
    ('adi',     -1,  0.075, -0.380, ('game', 'ATTITUDE', 0.068), 'standby attitude'),
    ('ias',     -1,  0.170, -0.380, ('game', 'AIRSPEED', 0.056), 'standby airspeed'),
    ('alt',     -1,  0.075, -0.520, ('game', 'ALTIMETER', 0.056), 'standby altimeter'),
    ('clock',   -1,  0.170, -0.520, ('game', 'CLOCK', 0.044), 'clock'),
    ('ammo',     1,  0.075, -0.380, ('game', 'AMMO', 0.056), 'rounds remaining, M230'),
    ('fuel',     1,  0.170, -0.380, ('game', 'FUEL_QTY', 0.052), 'fuel quantity'),
    ('radalt',   1,  0.120, -0.520, ('game', 'RADAR_ALT', 0.054), 'radar altimeter'),
]
CONSOLES = ([(0.16, 'ENG START', 'guarded'), (0.16, 'FUEL', 'toggles'), (0.16, 'COMM', 'freq'),
             (0.18, 'AUTOPILOT', 'lamps'), (0.16, 'LIGHTS', 'rotary'), (0.18, 'ARMAMENT', 'toggles')],
            [(0.20, 'KU', 'keys'), (0.16, 'NAV', 'freq'), (0.16, 'ELEC', 'toggles'), (0.16, 'CB', 'breakers'),
             (0.16, 'NVS', 'lamps'), (0.16, 'ECS', 'rotary')])


def p_ufd(d, im):
    """the up-front display: a green character display with the frequencies and fuel, its keys round it"""
    S_, Hh = im.width, im.height
    d.rectangle([0, 0, S_, Hh], fill=(20, 22, 22, 255))
    d.rectangle([S_ * .12, Hh * .12, S_ * .88, Hh * .88], fill=(8, 18, 8, 255), outline=(90, 90, 90, 255), width=2)
    for k, line in enumerate(['VHF 121.50  UHF 243.00', 'FUEL 2450  ENG OK', 'CAUTION: NONE']):
        H.text(d, (S_ / 2, Hh * (0.3 + 0.2 * k)), line, Hh * .14, fill=(90, 230, 90))
    for k in range(5):
        d.rectangle([S_ * .02, Hh * (0.12 + 0.16 * k), S_ * .09, Hh * (0.24 + 0.16 * k)], fill=(60, 62, 60, 255))
        d.rectangle([S_ * .91, Hh * (0.12 + 0.16 * k), S_ * .98, Hh * (0.24 + 0.16 * k)], fill=(60, 62, 60, 255))


def stations(ac):
    pilot = dict(seat=0, E=ac.eye(0), S=1.0, colours=COLOURS, layout=PILOT_LAYOUT, consoles=CONSOLES,
                 lamp_palette='nato',
                 PANEL_Z=0.86, LEAN=15, HINGE=0.18, WING=30, WALL_X=0.42, TOP_C=-0.220, TOP_W=-0.260,
                 BOT_C=-0.700, BOT_W=-0.660, FRONT_Z=1.06, FRONT_Y_C=-0.225, FRONT_Y_W=-0.255, COAMING_X=0.32,
                 CONSOLE_Y=-0.560, CONSOLE_IN=0.30, CONSOLE_OUT=0.50, CONSOLE_FRONT=0.55, CONSOLE_FOOT=-0.98,
                 CONSOLE_BACK=-0.25, CUSHION_Y=-0.76, SEAT_Z=0.11, SEAT_FOOT=-1.00, BACK_TOP=0.04, SEAT_LEAN=10,
                 seat_kind='armoured', seat_label='AH-64D', STICK_Z=0.42, STICK_FOOT=-1.02, PEDAL_Z=0.80,
                 PEDAL_Y=-0.92, PEDAL_HANG=False, FLOOR_Y=-1.02,
                 monocle=dict(z=0.075, w=0.036, h=0.036, arm_x=0.07, arm_y=0.035, arm_z=-0.03))
    cpg = dict(seat=1, E=ac.eye(1), S=1.0, colours=COLOURS, layout=CPG_LAYOUT, consoles=CONSOLES, lamp_palette='nato',
               PANEL_Z=0.66, LEAN=15, HINGE=0.17, WING=30, WALL_X=0.38, TOP_C=-0.260, TOP_W=-0.300,
               BOT_C=-0.800, BOT_W=-0.750, FRONT_Z=0.88, FRONT_Y_C=-0.230, FRONT_Y_W=-0.260, COAMING_X=0.30,
               CONSOLE_Y=-0.620, CONSOLE_IN=0.28, CONSOLE_OUT=0.44, CONSOLE_FRONT=0.45, CONSOLE_FOOT=-0.98,
               CONSOLE_BACK=-0.12, CUSHION_Y=-0.84, SEAT_Z=0.16, SEAT_FOOT=-0.99, BACK_TOP=0.02, SEAT_LEAN=10,
               seat_kind='armoured', seat_label='AH-64D', STICK_Z=0.42, STICK_FOOT=-1.00, PEDAL_Z=0.60,
               PEDAL_Y=-0.86, PEDAL_HANG=False, FLOOR_Y=-1.00,
               sight=dict(z=0.46, y=-0.175, w=0.18, h=0.10, d=0.16))
    return pilot, cpg


def main(argv):
    dry = '--dry-run' in argv
    ac = L.Aircraft(VID)
    E0, E1 = ac.eye(0), ac.eye(1)
    removed = {'old interior': HS.remove_old(ac, [(E0, (-0.55, -1.00, -0.45), (0.55, 0.30, 1.16), 0.55, -0.45),
                                                  (E1, (-0.55, -0.98, -0.60), (0.55, 0.25, 0.95), 0.55, -0.45)],
                                             min_index=INTERIOR_FROM)}
    pilot, cpg = stations(ac)
    atlas = H.A.Atlas(VID)
    removed['floor faces repainted'] = HS.repaint_floor(ac, atlas, FLOOR, [
        (E0 + [-0.5, -1.12, -0.5], E0 + [0.5, -0.90, 1.1]), (E1 + [-0.5, -1.10, -0.3], E1 + [0.5, -0.90, 0.9])])
    m = H.Mesh(atlas)
    gauges, displays, hud = [], [], None
    for st in (pilot, cpg):
        g, d, h = HS.build(m, atlas, st, painters={'ufd': p_ufd})
        gauges += g; displays += d; hud = hud or h
    pm, line = HS.install(ac, m, gauges, displays, hud, [E0, E1])
    print(f'{VID}: removed {removed}; new cockpit {len(pm["polys"])} faces, {len(gauges)} gauges, '
          f'{len(displays)} displays; {line}')
    if not dry:
        H.write(ac, atlas)


if __name__ == '__main__':
    main(sys.argv[1:])
