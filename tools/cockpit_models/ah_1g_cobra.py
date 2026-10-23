#!/usr/bin/env python3
"""AH-1G cockpits, built by hand for this model (heli_station.py stations, as ah_1f.py): the early Cobra's gunner in
front, the pilot behind and above him.

Measured off the model with the cuts and rays (metres from each crewman's eye; the model is about 1.3 times life
size). The eyes are main's: the pilot's (0, 3.55, 2.10), the gunner's (0, 3.22, 4.25).
  * pilot: the canopy coming down to about y -0.6 beside him, the walls at x +-0.50-0.71 under it, his old seat a
    plate pan at y -1.0, the old panel a box 0.65-1.0 ahead with its top at y -0.62; no floor under the pan;
  * gunner: the walls at x +-0.50-0.64, his old seat pan at y -1.12, the old panel a box 0.5-0.85 ahead with its
    top at y -0.44.
The model's canopy is bare frames: glaze fits a flat pane in every opening between them.
Built (the AH-1G's cockpits as photographed and described):
  * pilot: the flight and engine instruments, master caution and armament panels; the XM73 reflex sight over the
    coaming (its reticle the game's reflector sight, live for the pilot);
  * gunner: the M28 turret's pantograph sight before his face (its handgrips, the reflex glass carrying the reticle,
    live for the gunner), the armament panel, standby instruments and the rounds counter;
  * consoles, crew seats, the pilot's cyclic and collective, both crewmen's pedals hung under their panels.

Run on the committed originals (work/handbuilt/run_heli.ps1 restores them first).
Usage: python3 tools/cockpit_models/ah_1g_cobra.py [--dry-run]
"""
import os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import cockpit_lib as L   # noqa: E402
import handbuilt as H     # noqa: E402
import free_atlas as FA   # noqa: E402
import heli_station as HS  # noqa: E402

VID = 'ah_1g_cobra'
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
    ('turret',   0,  0.000, -0.640, ('paint_rect', 0.200, 0.070), 'turret control panel'),
    ('warning',  0,  0.000, -0.680, ('annunciator', 0.180, 0.055), 'master caution panel'),
    ('adi',     -1,  0.065, -0.500, ('game', 'ATTITUDE', 0.062), 'standby attitude'),
    ('ias',     -1,  0.150, -0.500, ('game', 'AIRSPEED', 0.052), 'airspeed'),
    ('alt',     -1,  0.105, -0.620, ('game', 'ALTIMETER', 0.052), 'altimeter'),
    ('ammo',     1,  0.065, -0.500, ('game', 'AMMO', 0.052), 'rounds remaining, M28 turret'),
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
    pilot = dict(seat=0, E=ac.eye(0), S=1.25, colours=COLOURS, layout=PILOT_LAYOUT, consoles=CONSOLES,
                 lamp_palette='nato',
                 PANEL_Z=0.74, LEAN=12, HINGE=0.18, WING=30, WALL_X=0.45, TOP_C=-0.420, TOP_W=-0.450,
                 BOT_C=-0.980, BOT_W=-0.940, FRONT_Z=0.96, FRONT_Y_C=-0.440, FRONT_Y_W=-0.460, COAMING_X=0.34,
                 CONSOLE_Y=-0.740, CONSOLE_IN=0.30, CONSOLE_OUT=0.46, CONSOLE_FRONT=0.52, CONSOLE_FOOT=-1.15,
                 CONSOLE_BACK=-0.25, CUSHION_Y=-0.92, SEAT_Z=0.05, SEAT_FOOT=-1.25, BACK_TOP=0.04,
                 seat_kind='armoured', seat_label='AH-1G', STICK_Z=0.40, STICK_FOOT=-1.30, PEDAL_Z=0.70,
                 PEDAL_Y=-1.18, PEDAL_HANG=True, FLOOR_Y=-1.40,
                 hud=dict(z=0.70, w=0.12, h=0.11, lean=25, body_top=-0.300, body_w=0.07, body_d=0.04,
                          style='REFLECTOR'))
    gunner = dict(seat=1, E=ac.eye(1), S=1.25, colours=COLOURS, layout=GUNNER_LAYOUT, consoles=CONSOLES_GUNNER,
                  lamp_palette='nato',
                  PANEL_Z=0.62, LEAN=12, HINGE=0.16, WING=32, WALL_X=0.40, TOP_C=-0.450, TOP_W=-0.480,
                  BOT_C=-0.980, BOT_W=-0.940, FRONT_Z=0.84, FRONT_Y_C=-0.470, FRONT_Y_W=-0.490, COAMING_X=0.30,
                  CONSOLE_Y=-0.700, CONSOLE_IN=0.28, CONSOLE_OUT=0.44, CONSOLE_FRONT=0.38, CONSOLE_FOOT=-1.15,
                  CONSOLE_BACK=-0.20, CUSHION_Y=-0.96, SEAT_Z=0.08, SEAT_FOOT=-1.30, BACK_TOP=0.02,
                  seat_kind='armoured', seat_label='AH-1G', STICK_Z=None, PEDAL_Z=0.56, PEDAL_Y=-1.20,
                  PEDAL_HANG=True, FLOOR_Y=-1.45,
                  sight=dict(z=0.42, y=-0.200, w=0.14, h=0.09, d=0.13,
                             glass=dict(z=0.37, w=0.08, h=0.07, lean=25, style='REFLECTOR')))
    return pilot, gunner

def main(argv):
    dry = '--dry-run' in argv
    ac = L.Aircraft(VID)
    E0, E1 = ac.eye(0), ac.eye(1)
    removed = {'old interior': HS.remove_old(ac, [
        (E0, (-0.60, -1.60, -0.50), (0.60, -0.62, 1.05), 0.60, -0.62),    # the pilot's tub up to the canopy's foot
        (E0, (-0.35, -0.62, -0.50), (0.35, 0.20, 1.05), 0.35, -0.62),     # his old seat back and panel
        (E1, (-0.60, -1.60, -0.50), (0.60, -0.60, 0.95), 0.60, -0.60),    # the gunner's tub
        (E1, (-0.35, -0.60, -0.50), (0.35, 0.10, 0.95), 0.35, -0.60),     # his old seat back and panel
        (E0, (-0.60, -0.62, 0.40), (0.60, -0.25, 1.05), 0.60, -0.62),     # old boxes beside the pilot's panel
        (E1, (-0.60, -0.60, 0.30), (0.60, -0.25, 0.95), 0.60, -0.60)])}   # and beside the gunner's
    pilot, gunner = stations(ac)
    atlas = H.A.Atlas(VID)
    removed['floor faces repainted'] = HS.repaint_floor(ac, atlas, FLOOR, [
        (E0 + [-0.5, -1.60, -0.5], E0 + [0.5, -1.00, 1.0]), (E1 + [-0.5, -1.65, -0.5], E1 + [0.5, -1.05, 1.6])])
    m = H.Mesh(atlas)
    painters = {'arm': HS.p_buttons('ARMAMENT', ['RKT', 'GUN', 'TOW'], lit=1), 'turret': HS.p_buttons('TURRET', ['STOW', 'MG', 'GL', 'FIRE'], lit=1)}
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
