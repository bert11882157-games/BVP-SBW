#!/usr/bin/env python3
"""AH-6J cockpit, built by hand for this model (heli_station.py station): the pilot in the right seat of the Little
Bird's bubble cabin.

Measured off the model with the cuts and rays (metres from the pilot's eye; the model is close to life size). The
eye is main's (-0.44, 2.42, 2.45).
  * the right wall 0.50 out from the eye, the cabin's centre console 0.22 in from it below y -0.7; the seat cushion at
    y -0.74, the floor at y -1.0 to -1.05; the old panel a column 0.9-1.25 ahead from y +0.25 down to the floor, the
    bubble's front frame about 1.2 ahead.
Built (the AH-6J's cockpit as photographed and described): a compact panel before the pilot with two multifunction
displays (the flight page and the stores / map page, live), the standby attitude, airspeed and altimeter, engine and
rotor speed, gas temperature, fuel and the caution panel; the reflex gunsight on its top (its reticle live for the
pilot); a crew seat, the cyclic, the collective and the pedals. The left seat gets its own station too (a display,
the standby dials and the caution panel, a seat and full controls), built about the pilot's eye mirrored across the
centre line since that seat has no camera of its own; the old central panel pod, its backboard, the centre console and
both old seats are gone. No canopy is made: the bubble keeps its frames and gets glass (model_glass.glaze).

Run on the committed originals (work/handbuilt/run_heli.ps1 restores them first).
Usage: python3 tools/cockpit_models/ah_6j.py [--dry-run]
"""
import os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import cockpit_lib as L   # noqa: E402
import handbuilt as H     # noqa: E402
import free_atlas as FA   # noqa: E402
import heli_station as HS  # noqa: E402

VID = 'ah_6j'
L.INTERIOR_BONES.add('hull')
FA.also_used(VID, 'ad56207c', 'suspended_')

COLOURS = dict(panel=(40, 42, 44), panel_d=(28, 29, 30), wall=(52, 56, 58))
FLOOR = (44, 46, 48)

LAYOUT = [
    ('mfd_l',    0, -0.070, -0.250, ('display', 'PFD', 0.120, 'LCD'), 'left multifunction display'),
    ('mfd_r',    0,  0.070, -0.250, ('display', 'STORES', 0.120, 'LCD'), 'right multifunction display'),
    ('warning',  0,  0.000, -0.420, ('annunciator', 0.200, 0.055), 'caution panel'),
    ('adi',     -1,  0.060, -0.230, ('game', 'ATTITUDE', 0.060), 'standby attitude'),
    ('ias',     -1,  0.060, -0.340, ('game', 'AIRSPEED', 0.052), 'airspeed'),
    ('alt',     -1,  0.060, -0.450, ('game', 'ALTIMETER', 0.052), 'altimeter'),
    ('rotor',    1,  0.060, -0.230, ('game', 'ENGINE_RPM', 0.052), 'engine and rotor speed'),
    ('egt',      1,  0.060, -0.340, ('game', 'EGT', 0.048), 'turbine outlet temperature'),
    ('fuel',     1,  0.060, -0.450, ('game', 'FUEL_QTY', 0.048), 'fuel quantity'),
]
CONSOLES = ([(0.30, 'ARMAMENT', 'guarded'), (0.36, 'RADIO', 'freq'), (0.34, 'LIGHTS', 'rotary')],
            [(0.34, 'ELEC', 'toggles'), (0.33, 'CB', 'breakers'), (0.33, 'ICS', 'rotary')])


LEFT_LAYOUT = [
    ('mfd',      0,  0.000, -0.250, ('display', 'PFD', 0.120, 'LCD'), 'multifunction display'),
    ('warning',  0,  0.000, -0.420, ('annunciator', 0.180, 0.050), 'caution panel'),
    ('adi',     -1,  0.060, -0.230, ('game', 'ATTITUDE', 0.058), 'standby attitude'),
    ('ias',     -1,  0.060, -0.340, ('game', 'AIRSPEED', 0.050), 'airspeed'),
    ('alt',      1,  0.060, -0.230, ('game', 'ALTIMETER', 0.050), 'altimeter'),
    ('clock',    1,  0.060, -0.340, ('game', 'CLOCK', 0.042), 'clock'),
]


def left_eye(ac):
    """the left seat's eye: it has no camera of its own (no crewman's view), so the pilot's eye mirrored across the
    centre line, over the left seat (seat 1 sits at the mirror of seat 0)"""
    e = ac.eye(0).copy()
    e[0] = -e[0]
    return e


def left_station(ac):
    st = station(ac)
    st.update(seat=1, E=left_eye(ac), layout=LEFT_LAYOUT, hud=None)
    return st


def station(ac):
    return dict(seat=0, E=ac.eye(0), S=1.0, colours=COLOURS, layout=LAYOUT, consoles=CONSOLES, lamp_palette='nato',
                PANEL_Z=0.86, LEAN=12, HINGE=0.14, WING=32, WALL_X=0.26, TOP_C=-0.130, TOP_W=-0.150,
                BOT_C=-0.560, BOT_W=-0.530, FRONT_Z=1.02, FRONT_Y_C=-0.150, FRONT_Y_W=-0.165, COAMING_X=0.22,
                CONSOLE_Y=-0.600, CONSOLE_IN=0.25, CONSOLE_OUT=0.40, CONSOLE_FRONT=0.40, CONSOLE_FOOT=-0.98,
                CONSOLE_BACK=-0.05, CUSHION_Y=-0.72, SEAT_Z=0.10, SEAT_FOOT=-1.00, BACK_TOP=0.02, SEAT_LEAN=10,
                seat_kind='armoured', seat_label='AH-6J', STICK_Z=0.40, STICK_FOOT=-1.03, PEDAL_Z=0.78,
                PEDAL_Y=-0.88, PEDAL_HANG=False, FLOOR_Y=-1.03,
                hud=dict(z=0.80, w=0.09, h=0.085, lean=25, body_top=-0.090, body_w=0.06, body_d=0.04,
                         style='REFLECTOR'))


def main(argv):
    dry = '--dry-run' in argv
    ac = L.Aircraft(VID)
    E0 = ac.eye(0)
    removed = {'old interior': HS.remove_old(ac, [
        (E0, (-0.42, -1.02, -0.55), (0.62, -0.30, 1.20), 0.62, -0.30),    # the pilot's side and the centre console
        (E0, (-0.40, -0.30, 0.65), (0.75, 0.30, 1.20), 0.75, -0.30),      # the old central panel pod and its sight
        (E0, (-0.30, -0.30, 0.30), (0.30, 0.10, 0.70), 0.30, -0.30),      # the old cyclic's grip
        (left_eye(ac), (-0.42, -1.02, -0.55), (0.42, -0.30, 1.20), 0.42, -0.30),   # the left seat, its controls and
        (left_eye(ac), (-0.40, -0.30, -0.60), (0.40, 0.30, 1.20), 0.40, -0.30),    # its old stick column and grip
        (E0, (-0.11, -0.35, 1.20), (0.99, 0.11, 1.55), 0.99, -0.35),     # the old panel's backboard and housing on the
        #                                                              centre line (the windscreen's centre post above it stays)
        (E0, (0.29, -1.02, 0.95), (0.59, -0.30, 1.45), 0.59, -0.30),     # the old centre console's front column
        (E0, (-0.40, -0.30, -0.60), (0.62, 0.30, -0.05), 0.62, -0.30)],   # the old seats' backs and the plate between
        glass_gap=0.005)}                       # (the old sight's own glass lies against it: only true frames stay)
    atlas = H.A.Atlas(VID)
    removed['floor faces repainted'] = HS.repaint_floor(ac, atlas, FLOOR, [
        (E0 + [-0.45, -1.15, -0.5], E0 + [0.45, -0.90, 1.2]), (left_eye(ac) + [-0.45, -1.15, -0.5], left_eye(ac) + [0.45, -0.90, 1.2])])
    m = H.Mesh(atlas)
    g, d, h = HS.build(m, atlas, station(ac))
    g1, d1, _ = HS.build(m, atlas, left_station(ac))           # the left seat: its own panel, seat and controls
    pm, line = HS.install(ac, m, g + g1, d + d1, [h], [E0, left_eye(ac)])
    print(f'{VID}: removed {removed}; new cockpit {len(pm["polys"])} faces, {len(g + g1)} gauges, {len(d + d1)} displays; '
          f'{line}')
    if not dry:
        H.write(ac, atlas)


if __name__ == '__main__':
    main(sys.argv[1:])
