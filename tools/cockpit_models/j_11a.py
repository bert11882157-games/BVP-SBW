#!/usr/bin/env python3
"""Shenyang J-11A cockpit, built by hand for this model (as su_27.py: the J-11A is the licence-built Su-27SK, its
cockpit the Su-27's analogue one), after the real cockpit.

Measured off the model with sections.py and glass_sections.py (all heights and distances from the pilot's eye at
(0, 3.4275, 6.05); 1 block = 1 m on this model):
  * the canopy's sill at y -0.26 beside the eye, the walls sloping out under it; the side consoles at y -0.70; the
    floor at y -1.08 (11 cm lower than the Su-27 model's: the seat stands on a taller base, the stick's column and
    the pedals reach down to it);
  * the old panel face 0.47 m ahead with its top at y -0.10; a short windscreen, falling to the nose 1.05 m ahead.
Built: the Su-27's panel, coaming, HUD, consoles, walls, pedals, throttle, centre stick and K-36DM seat, with English
placards (the game's fonts have no Chinese). Instruments the game draws (CockpitGauges, FlightDisplays,
HeadUpDisplay) are live. No glass.

Run on the committed originals (git checkout be357476 -- the j_11a geo, asset and both textures first).
Usage: python3 tools/cockpit_models/j_11a.py [--dry-run]
"""
import math, os, sys
import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import cockpit_lib as L   # noqa: E402
import handbuilt as H     # noqa: E402

VID = 'j_11a'
SOURCE_BONE = 'wreck_fuselage_0__hull'
STICK_BONE = 'wreck_fuselage_0__control_stick_pitch'

TURQ = (76, 100, 90)           # the reference cockpit's dark grey-green (was the Soviet turquoise)
TURQ_D = (52, 70, 63)
TURQ_L = (112, 134, 124)
DECK = (48, 60, 55)            # the model's old turquoise tub and rear deck, repainted
BLACK = (24, 25, 26)
DGREY = (44, 46, 48)
MGREY = (88, 92, 94)
FRAME = (56, 62, 58)           # K-36 seat structure
CUSHION = (74, 70, 56)         # the K-36DM's olive-brown cushions
HEADPAD = (40, 42, 40)
YELLOW = (226, 186, 30)
RED = (196, 36, 30)
METAL = (128, 132, 134)
FLOOR = (46, 50, 50)

# ---------------------------------------------------------------- the measured cockpit (metres from the eye)
WALL_X = 0.440                 # panel wings and consoles end here (the walls' inner faces at 0.47)
SILL_Y = -0.27
FLOOR_Y = -1.08                # (1 cm over the old white floor slab at -1.078)
BACK_Z = -0.55                 # behind the seat
CONSOLE_Y = -0.70
CONSOLE_IN = 0.27
PANEL_Z = 0.47                 # centre facet reference plane (measured off the reference model: 0.57 m ahead)
LEAN = math.radians(8)         # the reference panel's lean (0.05 m back over its height)
HINGE = 0.32                   # centre facet half width: the reference panel is flat across +-0.32 m
WING = math.radians(30)
WING_LEN = (WALL_X - HINGE) / math.cos(WING)
TOP_C, TOP_W = -0.130, -0.400  # centre facet top, wing top at the wall (3 cm under the side glass)
BOT_C, BOT_W = -0.640, CONSOLE_Y   # the reference panel's foot; the footwell under it
FRONT_Z = 0.82                 # the coaming runs forward down the nose deck
FRONT_Y_C, FRONT_Y_W = -0.170, -0.310
FRONT_X = 0.270                # the coaming's front edge: inside the narrow windscreen's sides

HUD_TOP = [1.0]                # how far above the eye the view under the bow reaches at the glass (main sets it)
R_C = np.array([-1.0, 0, 0])                                   # pilot's right
U_C = np.array([0, math.cos(LEAN), math.sin(LEAN)])
N_C = np.array([0, math.sin(LEAN), -math.cos(LEAN)])           # toward the pilot


def facets(E):
    o = E + np.array([0, (TOP_C + BOT_C) / 2, PANEL_Z])
    centre = H.Facet(o, R_C, U_C, N_C)
    wings = {}
    for side in (1, -1):                                       # +1: the pilot's right wing
        R = R_C * side * math.cos(WING) + N_C * math.sin(WING)
        N = N_C * math.cos(WING) - R_C * side * math.sin(WING)
        hinge = o + R_C * side * HINGE
        f = H.Facet(hinge, R, U_C, N)                          # a: metres outward from the hinge
        wings[side] = f
    return centre, wings[1], wings[-1]


def top_of(side, a):
    return TOP_C if side == 0 else TOP_C + (TOP_W - TOP_C) * a / WING_LEN


# Instrument layout after the DCS Su-27 manual, figure 20. facet: 0 centre (a: metres to the pilot's right of the
# centre line), +1 right wing, -1 left wing (a: metres outward from the hinge). y: from the eye.
LAYOUT = [   # positions measured off the reference model's panel (a: right of the centre line on the centre facet)
    # centre facet: the upper-left switch block, airspeed / altimeter / clock column, AoA / G, the KPP over the PNP,
    # VVI / Mach / radio altimeter, the IPV display high on the right in its raised housing, SPO-15 and fuel under it
    ('weapons',  0, -0.238, -0.228, ('paint_rect', 0.124, 0.060), 'weapons control panel (the raised upper-left block)'),
    ('caution',  0, -0.100, -0.272, ('paint_disc', 0.022), 'master caution lamp'),
    ('ias',      0, -0.245, -0.330, ('game', 'AIRSPEED', 0.064), 'KUS-3 airspeed'),
    ('alt',      0, -0.245, -0.430, ('game', 'ALTIMETER', 0.066), 'VM-15 altimeter'),
    ('clock',    0, -0.245, -0.530, ('game', 'CLOCK', 0.046), 'AChS-1 clock'),
    ('aoa',      0, -0.149, -0.315, ('game', 'AOA', 0.054), 'UAP-14 AoA'),
    ('g',        0, -0.145, -0.405, ('game', 'G_METER', 0.050), 'UAP-14 accelerometer'),
    ('adi',      0, -0.026, -0.335, ('game', 'ATTITUDE', 0.095), 'KPP attitude director'),
    ('hsi',      0, -0.030, -0.455, ('game', 'HEADING', 0.095), 'PNP horizontal situation'),
    ('vvi',      0,   0.080, -0.330, ('game', 'VVI', 0.060), 'VAR-300 vertical speed'),
    ('mach',     0,   0.080, -0.420, ('game', 'MACH', 0.056), 'Machmeter'),
    ('radalt',   0,   0.080, -0.500, ('game', 'RADAR_ALT', 0.050), 'radio altimeter'),
    ('hdd',      0,   0.218, -0.290, ('display', 'RADAR', 0.130), 'IPV head-down display, in its raised housing'),
    ('spo15',    0,   0.175, -0.430, ('game', 'RWR', 0.050), 'SPO-15 Beryoza'),
    ('fuel',     0,   0.262, -0.430, ('game', 'FUEL_QTY', 0.050), 'fuel quantity'),
    ('ekran',    0, -0.060, -0.575, ('annunciator', 0.150, 0.055), 'Ekran warning panel'),
    ('afcs',     0,   0.200, -0.540, ('paint_rect', 0.140, 0.040), 'autopilot push-lights'),
    # the short wings
    ('gear',    -1,   0.070, -0.450, ('paint_rect', 0.050, 0.100), 'gear control panel'),
    ('rpm',      1,   0.065, -0.380, ('game', 'ENGINE_RPM', 0.044), 'twin tachometer'),
    ('egt',      1,   0.065, -0.470, ('game', 'EGT', 0.036), 'exhaust gas temperature'),
    ('hyd',      1,   0.065, -0.550, ('game', 'FILLER_HYDRAULIC', 0.032), 'hydraulic pressures'),
    ('oxy',      1,   0.065, -0.315, ('game', 'FILLER_OXYGEN', 0.030), 'oxygen'),
]
HDD_PROUD = 0.058              # the IPV's face stands this far proud of the panel, on its housing (the reference)


# ---------------------------------------------------------------- painted faces

def panel_paint(side, W_, H_, a0, a1, yb, yt):
    def fn(d, im):
        SS = H.A.Atlas.SS
        H.noisy(d, im, TURQ, 5, seed=3 + side)

        def to_px(a, y):
            s = (a - a0) / (a1 - a0)
            if side == -1:
                s = 1 - s
            return s * W_ * SS, (1 - (y - yb) / (yt - yb)) * H_ * SS
        for y in ((-0.30, -0.40, -0.50) if side == 0 else (-0.27, -0.38, -0.48)):
            x0, yy = to_px(a0, y); x1, _ = to_px(a1, y)
            d.line([x0, yy, x1, yy], fill=TURQ_D + (255,), width=SS * 2)
        for a, y in ((a0 + 0.012, yb + 0.012), (a1 - 0.012, yb + 0.012), (a0 + 0.012, yt - 0.02), (a1 - 0.012, yt - 0.02)):
            x, yy = to_px(a, y); r = SS * 2.2
            d.ellipse([x - r, yy - r, x + r, yy + r], fill=MGREY + (255,), outline=(30, 30, 30, 255))
        for name, f, a, y, spec, _ in LAYOUT:
            if f != side:
                continue
            x, yy = to_px(a, y)
            px_m = W_ * SS / (a1 - a0)
            if spec[0] in ('game', 'paint_disc'):
                dia = spec[-1]
                r = (dia / 2 * (1 + H.RIM) + 0.004) * px_m
                d.ellipse([x - r, yy - r, x + r, yy + r], fill=(20, 26, 26, 255))
                if name in PLACARD:                                 # the white-on-black placard under the dial
                    lab = PLACARD[name]
                    ph = 0.011 * px_m; pw = max(dia * 0.6, 0.011 * 0.62 * len(lab)) * px_m
                    py = yy + r + ph * 0.75
                    d.rectangle([x - pw / 2, py - ph / 2, x + pw / 2, py + ph / 2], fill=(16, 18, 18, 255))
                    H.text(d, (x, py), lab, ph * 0.78)
            else:
                w, h = (spec[2], spec[2]) if spec[0] == 'display' else (spec[1], spec[2])
                sx, sy = (w / 2 + 0.006) * px_m, (h / 2 + 0.006) * px_m
                d.rectangle([x - sx, yy - sy, x + sx, yy + sy], fill=(20, 26, 26, 255))
        px_m = W_ * SS / (a1 - a0)
        for a, y, n, lab in FILLERS.get(side, ()):                # rows of toggles and push-lights in the open bands
            x, yy = to_px(a, y)
            pitch = 0.024 * px_m
            x0 = x - pitch * (n - 1) / 2
            d.rectangle([x0 - pitch * .6, yy - 0.016 * px_m, x0 + pitch * (n - .4), yy + 0.022 * px_m], fill=(18, 20, 20, 255))
            for k in range(n):
                cx = x0 + k * pitch
                if lab.startswith('*'):                               # caption push-lights
                    H.lamp(d, [cx - pitch * .4, yy - 0.011 * px_m, cx + pitch * .4, yy + 0.007 * px_m],
                           (200, 196, 170) if k % 3 else (90, 170, 90), None, lit=False)
                else:                                                 # toggles: a bat on a hex nut
                    d.ellipse([cx - 5, yy - 5, cx + 5, yy + 5], fill=(120, 124, 120, 255))
                    d.rectangle([cx - 2, yy - 0.012 * px_m, cx + 2, yy], fill=(200, 202, 198, 255))
            H.text(d, (x0 + pitch * (n - 1) / 2, yy + 0.015 * px_m), lab.lstrip('*'), 0.008 * px_m)
        if side == 0:
            for k, lab in enumerate(['BRAKE', 'EMER JETT']):
                x, yy = to_px(-0.16 + k * 0.32, -0.700)
                d.rectangle([x - 70, yy - 14, x + 70, yy + 14], fill=(18, 18, 18, 255))
                H.text(d, (x, yy), lab, 18)
    return fn


PLACARD = {'ias': 'IAS', 'alt': 'ALT', 'clock': 'CLOCK', 'aoa': 'α', 'g': 'n', 'adi': 'ADI', 'hsi': 'HSI',
           'vvi': 'VVI', 'mach': 'M', 'radalt': 'RADALT', 'spo15': 'RWR', 'fuel': 'FUEL', 'rpm': 'RPM',
           'egt': 'TEMP', 'hyd': 'HYD', 'oxy': 'OXYGEN'}
FILLERS = {   # (a, y, count, label; '*' = push-lights) on the facets' open bands, after the reference panel
    0: [(-0.215, -0.612, 5, 'NAV MODE'), (0.105, -0.603, 4, '*WARN')],
    -1: [(0.065, -0.300, 3, 'SALVO'), (0.065, -0.560, 3, 'RELEASE')],
    1: [(0.065, -0.615, 3, '*FIRE')],
}


def p_weapons(d, im):
    S, Hh = im.width, im.height
    d.rectangle([0, 0, S, Hh], fill=(20, 22, 22, 255))
    labs = ['GUN', 'MISSILE', 'BOMB', 'JETT']                  # the master arm block: red guards over the last two
    for k, lab in enumerate(labs):
        x = S * (0.13 + k * 0.245)
        H.lamp(d, [x - 14, Hh * .1, x + 14, Hh * .3], (210, 210, 200), None, lit=(k == 0))
        d.rectangle([x - 4, Hh * .4, x + 4, Hh * .66], fill=(190, 190, 190, 255))
        if k >= 2:
            d.rectangle([x - 13, Hh * .36, x + 13, Hh * .7], outline=(180, 34, 28, 255), width=5)
        H.text(d, (x, Hh * .86), lab, Hh * .13)


def p_gear(d, im):
    S, Hh = im.width, im.height
    d.rectangle([0, 0, S, Hh], fill=(20, 22, 22, 255))
    for k in range(3):
        y = Hh * (0.12 + 0.11 * k)
        H.lamp(d, [S * .2, y, S * .8, y + Hh * .08], (60, 150, 70), None, lit=True)
    H.text(d, (S / 2, Hh * .55), 'GEAR', S * .16)
    d.rectangle([S * .4, Hh * .62, S * .6, Hh * .95], fill=(140, 140, 140, 255))


def p_afcs(d, im):
    S, Hh = im.width, im.height
    d.rectangle([0, 0, S, Hh], fill=(20, 22, 22, 255))
    labs = ['DAMPER', 'ROLL', 'ALT', 'LEVEL', 'OFF']
    for k, lab in enumerate(labs):
        w = S / len(labs)
        H.lamp(d, [k * w + 6, Hh * .14, (k + 1) * w - 6, Hh * .86], (210, 210, 200) if k < 4 else (200, 60, 50),
               lab, lit=(k == 0), px=Hh * .22)


def p_buttons(labels):
    def fn(d, im):
        S, Hh = im.width, im.height
        d.rectangle([0, 0, S, Hh], fill=(20, 22, 22, 255))
        w = S / len(labels)
        for k, lab in enumerate(labels):
            H.lamp(d, [k * w + 5, Hh * .12, (k + 1) * w - 5, Hh * .70], (200, 200, 190), None, lit=False)
            H.text(d, ((k + .5) * w, Hh * .86), lab, Hh * .2)
    return fn


def p_disc_red(d, im):
    S = im.width
    d.ellipse([0, 0, S - 1, S - 1], fill=(120, 18, 14, 255))
    d.ellipse([S * .18, S * .14, S * .55, S * .4], fill=(220, 90, 80, 255))


def p_hud_ctrl(d, im):
    """the ILS-31 body's aft strip: its vent slots and the filter lever's scale"""
    S, Hh = im.width, im.height
    d.rectangle([0, 0, S, Hh], fill=(26, 27, 28, 255))
    for k in range(14):
        x = S * (0.06 + k * 0.064)
        d.rectangle([x, Hh * .25, x + S * .035, Hh * .75], fill=(10, 10, 11, 255))


def p_ils31_face(d, im):
    """the ILS-31 control unit's face toward the pilot (the reference views): the big brightness knob left of centre
    with its scale, the day / night and filter switches, the test button and the green ready lamp right, the
    recessed centre panel with screw heads"""
    S, Hh = im.width, im.height
    d.rectangle([0, 0, S, Hh], fill=(22, 23, 24, 255))
    d.rectangle([S * .03, Hh * .06, S * .97, Hh * .94], fill=(30, 32, 33, 255), outline=(52, 54, 56, 255), width=3)
    for x, y in ((.06, .14), (.94, .14), (.06, .86), (.94, .86)):
        d.ellipse([S * x - 5, Hh * y - 5, S * x + 5, Hh * y + 5], fill=(70, 72, 74, 255))
    kx, ky, kr = S * .30, Hh * .46, Hh * .24                               # the brightness knob and its scale
    for k in range(9):
        a = math.radians(-135 + k * 33.75)
        d.line([kx + kr * 1.25 * math.sin(a), ky - kr * 1.25 * math.cos(a), kx + kr * 1.5 * math.sin(a),
                ky - kr * 1.5 * math.cos(a)], fill=(220, 220, 210, 255), width=3)
    d.ellipse([kx - kr, ky - kr, kx + kr, ky + kr], fill=(14, 14, 15, 255), outline=(96, 98, 100, 255), width=4)
    d.line([kx, ky, kx + kr * .7, ky - kr * .7], fill=(230, 230, 220, 255), width=5)
    H.text(d, (kx, Hh * .86), 'BRIGHT', Hh * .11)
    for k, lab in enumerate(['DAY', 'FILTER']):                          # two bat toggles
        x = S * (0.50 + k * 0.15)
        d.ellipse([x - 9, Hh * .42 - 9, x + 9, Hh * .42 + 9], fill=(110, 112, 110, 255))
        d.rectangle([x - 4, Hh * .20, x + 4, Hh * .42], fill=(205, 205, 200, 255))
        H.text(d, (x, Hh * .62), lab, Hh * .08)
    H.lamp(d, [S * .78, Hh * .18, S * .9, Hh * .46], (60, 170, 70), None, lit=True)
    H.text(d, (S * .84, Hh * .56), 'READY', Hh * .09)
    d.ellipse([S * .80, Hh * .64, S * .88, Hh * .86], fill=(150, 30, 26, 255), outline=(20, 20, 20, 255), width=3)
    H.text(d, (S * .5, Hh * .84), 'HUD', Hh * .12, bold=True)


def p_ils31_ear(d, im):
    """one of the ILS-31's two side blocks: a caption push-button over a small rotary"""
    S, Hh = im.width, im.height
    d.rectangle([0, 0, S, Hh], fill=(24, 25, 26, 255))
    H.lamp(d, [S * .14, Hh * .1, S * .86, Hh * .38], (200, 196, 170), None, lit=False)
    d.ellipse([S * .26, Hh * .5, S * .74, Hh * .5 + S * .48], fill=(12, 12, 13, 255), outline=(100, 100, 100, 255), width=3)
    d.line([S * .5, Hh * .5 + S * .24, S * .5, Hh * .5 + S * .05], fill=(220, 220, 210, 255), width=3)


def p_hud_lens(d, im):
    S, Hh = im.width, im.height
    d.rectangle([0, 0, S, Hh], fill=(16, 22, 26, 255))
    d.ellipse([S * .1, Hh * .1, S * .9, Hh * .9], fill=(30, 46, 56, 255))
    d.ellipse([S * .25, Hh * .2, S * .5, Hh * .4], fill=(90, 120, 140, 255))


def p_sight(d, im):
    """the ASP-PFD's aft face: the reticle unit's window, the brightness / base / range knobs"""
    S, Hh = im.width, im.height
    H.noisy(d, im, DGREY, 4, seed=9)
    d.rectangle([S * .22, Hh * .12, S * .78, Hh * .62], fill=(16, 22, 26, 255), outline=(90, 90, 90, 255), width=4)
    d.ellipse([S * .38, Hh * .2, S * .62, Hh * .54], fill=(34, 48, 58, 255))
    for k, lab in enumerate(['BRT', 'BASE', 'RANGE']):
        x = S * (0.2 + k * 0.3)
        d.ellipse([x - 14, Hh * .74 - 14, x + 14, Hh * .74 + 14], fill=(22, 22, 22, 255), outline=(140, 140, 140, 255),
                  width=3)
        H.text(d, (x, Hh * .93), lab, Hh * .09)


# the side consoles, front to back (the Su-27 manual's console figures): (share of the length, title, kind)
CONSOLE_L = [(0.10, 'START', 'guarded'), (0.07, 'VENT', 'rotary'), (0.24, 'THROTTLE', 'throttle'),
             (0.12, 'VHF', 'freq'), (0.09, 'ADF', 'freq'), (0.09, 'LAND LT', 'toggles'), (0.13, 'FUEL', 'toggles'),
             (0.12, 'OXYGEN', 'rotary')]
CONSOLE_R = [(0.11, 'NAVIGATION', 'rotary'), (0.10, 'WEAPONS', 'guarded'), (0.10, 'IFF', 'freq'),
             (0.15, 'POWER', 'toggles'), (0.12, 'CB', 'breakers'), (0.10, 'LIGHTS', 'rotary'),
             (0.10, 'RADAR', 'lamps'), (0.18, 'CANOPY', 'guarded')]


def p_su27_console(rows, mirror):
    """a console top from above (tile top = forward): black sub-panels with Cyrillic titles, each its own kind of
    controls; mirror for the left console (its tile runs inner to outer, the pilot's right to left)"""
    def paint(d, im):
        S, Hh = im.width, im.height
        d.rectangle([0, 0, S, Hh], fill=TURQ + (255,))
        y = Hh * 0.01
        for k, (share, title, kind) in enumerate(rows):
            y1 = y + share * Hh
            if kind == 'throttle':                                  # the throttle quadrant: the slot and its scale
                d.rectangle([S * .08, y + 3, S * .92, y1 - 3], fill=(30, 33, 32, 255))
                d.rectangle([S * .44, y + Hh * .02, S * .58, y1 - Hh * .02], fill=(8, 8, 8, 255))
                for j in range(6):
                    yy = y + Hh * .025 + (y1 - y - Hh * .05) * j / 5
                    d.line([S * .62, yy, S * .78, yy], fill=(220, 220, 210, 255), width=2)
                for j, lab in enumerate(['A/B', 'MAX', 'IDLE', 'STOP']):
                    H.text(d, (S * .26, y + Hh * .03 + (y1 - y - Hh * .06) * j / 3), lab, S * .1)
                y = y1
                continue
            d.rectangle([S * .07, y + 3, S * .93, y1 - 3], fill=(22, 24, 24, 255), outline=(60, 64, 62, 255), width=2)
            for sx in (.12, .88):
                for sy in (y + 9, y1 - 9):
                    d.ellipse([S * sx - 3, sy - 3, S * sx + 3, sy + 3], fill=(84, 86, 86, 255))
            H.text(d, (S * .5, y + Hh * .012), title, min(S * .1, Hh * .011))
            cy, top = (y + y1) / 2 + Hh * .006, y + Hh * .024
            if kind == 'guarded':                                   # red guards over toggles
                for j in range(3):
                    x = S * (0.24 + 0.26 * j)
                    d.rectangle([x - S * .07, cy - Hh * .016, x + S * .07, cy + Hh * .016], fill=(170, 30, 26, 255),
                                outline=(60, 10, 8, 255), width=2)
                    d.line([x - S * .05, cy, x + S * .05, cy], fill=(110, 14, 12, 255), width=3)
            elif kind == 'rotary':                                  # selector knobs with pointer and detents
                for j in range(2):
                    x, r = S * (0.3 + 0.4 * j), S * .11
                    for a in range(-120, 121, 40):
                        t = math.radians(a)
                        d.line([x + r * 1.3 * math.sin(t), cy - r * 1.3 * math.cos(t), x + r * 1.6 * math.sin(t),
                                cy - r * 1.6 * math.cos(t)], fill=(210, 210, 200, 255), width=2)
                    d.ellipse([x - r, cy - r, x + r, cy + r], fill=(10, 10, 11, 255), outline=(110, 110, 110, 255), width=2)
                    d.line([x, cy, x + r * .8 * math.sin(.6 * (j + 1)), cy - r * .8 * math.cos(.6 * (j + 1))],
                           fill=(235, 235, 225, 255), width=3)
            elif kind == 'freq':                                    # the frequency window and its two knobs
                d.rectangle([S * .18, top + Hh * .004, S * .82, top + Hh * .022], fill=(8, 8, 8, 255), outline=(90, 90, 90, 255))
                H.text(d, (S * .5, top + Hh * .013), '124.50', Hh * .014, fill=(230, 200, 120))
                for x in (.3, .7):
                    d.ellipse([S * x - S * .09, cy + Hh * .012 - S * .09, S * x + S * .09, cy + Hh * .012 + S * .09],
                              fill=(14, 14, 15, 255), outline=(120, 120, 120, 255), width=2)
            elif kind == 'toggles':                                 # rows of bat toggles
                nrow = max(1, int((y1 - top) / (Hh * .03)))
                for r_ in range(nrow):
                    yy = top + (y1 - top) * (r_ + .5) / nrow
                    for j in range(4):
                        x = S * (0.2 + 0.2 * j)
                        d.ellipse([x - 5, yy - 5, x + 5, yy + 5], fill=(120, 122, 118, 255))
                        d.rectangle([x - 2, yy - Hh * .011, x + 2, yy], fill=(205, 205, 200, 255))
            elif kind == 'breakers':                                # circuit breakers: rows of small black buttons
                for r_ in range(3):
                    yy = top + (y1 - top) * (r_ + .5) / 3
                    for j in range(5):
                        x = S * (0.17 + 0.165 * j)
                        d.ellipse([x - 6, yy - 6, x + 6, yy + 6], fill=(6, 6, 6, 255), outline=(150, 150, 140, 255), width=2)
            elif kind == 'lamps':                                   # caption lamps
                for r_ in range(2):
                    for j in range(3):
                        x0 = S * (0.14 + 0.25 * j); yy = top + (y1 - top) * (r_ * .45 + .1)
                        H.lamp(d, [x0, yy, x0 + S * .2, yy + (y1 - top) * .33], (210, 190, 120) if j else (190, 60, 50), None)
            y = y1
        if mirror:
            im.paste(im.transpose(Image.FLIP_LEFT_RIGHT))
    return paint


def p_su27_wall(side):
    """a cockpit side wall (tile left = aft on the left wall, forward on the right): the panel seams and rivet rows, the wall panels the Su-27 carries there
    (left: the emergency gear and brake handles' plate; right: the circuit-breaker panel and the oxygen hose port)"""
    def paint(d, im):
        S, Hh = im.width, im.height
        d.rectangle([0, 0, S, Hh], fill=TURQ_D + (255,))
        for x in (S * .3, S * .62):
            d.line([x, 0, x, Hh], fill=(40, 52, 47, 255), width=3)
        for y in (Hh * .1, Hh * .9):
            for x in range(6, S, 14):
                d.ellipse([x - 2, y - 2, x + 2, y + 2], fill=(84, 104, 96, 255))
        if side > 0:
            d.rectangle([S * .36, Hh * .25, S * .58, Hh * .7], fill=(22, 24, 24, 255), outline=(70, 74, 72, 255), width=2)
            d.rectangle([S * .40, Hh * .35, S * .46, Hh * .6], fill=(190, 160, 30, 255))
            d.rectangle([S * .49, Hh * .35, S * .55, Hh * .6], fill=(170, 30, 26, 255))
            H.text(d, (S * .47, Hh * .3), 'EMER GEAR', Hh * .07)
        else:
            d.rectangle([S * .34, Hh * .2, S * .6, Hh * .72], fill=(22, 24, 24, 255), outline=(70, 74, 72, 255), width=2)
            for r_ in range(4):
                for j in range(6):
                    x, y = S * (0.37 + 0.04 * j), Hh * (0.3 + 0.12 * r_)
                    d.ellipse([x - 3, y - 3, x + 3, y + 3], fill=(6, 6, 6, 255), outline=(140, 140, 130, 255))
            H.text(d, (S * .47, Hh * .25), 'CB', Hh * .07)
            d.ellipse([S * .12, Hh * .35, S * .2, Hh * .55], fill=(30, 32, 30, 255), outline=(100, 100, 96, 255), width=3)
        d.rectangle([0, Hh * .8, S, Hh * .84], fill=(40, 52, 47, 255))
    return paint


def p_k36_cushion(is_back):
    """the K-36DM's olive-brown cushion: quilted in long stitched panels; the back with the parachute harness"""
    def fn(d, im):
        S, Hh = im.width, im.height
        d.rectangle([0, 0, S, Hh], fill=CUSHION + (255,))
        dark = tuple(c - 20 for c in CUSHION) + (255,)
        for k in range(1, 4):                                      # stitched panels
            x = S * k / 4
            d.line([x, 0, x, Hh], fill=dark, width=3)
        for k in range(1, 6 if is_back else 4):
            y = Hh * k / (6 if is_back else 4)
            d.line([0, y, S, y], fill=tuple(c - 10 for c in CUSHION) + (255,), width=2)
        d.rectangle([2, 2, S - 3, Hh - 3], outline=dark, width=3)
        if is_back:
            H.text(d, (S * .5, Hh * .9), 'K-36DM', Hh * .07, fill=(30, 30, 26), bold=True)
    return fn


def p_k36_headpad(d, im):
    S, Hh = im.width, im.height
    d.rectangle([0, 0, S, Hh], fill=(30, 31, 30, 255))
    d.rounded_rectangle([S * .08, Hh * .1, S * .92, Hh * .9], radius=S * .1, fill=(46, 47, 45, 255),
                        outline=(18, 18, 18, 255), width=3)
    d.line([S * .5, Hh * .14, S * .5, Hh * .86], fill=(24, 24, 24, 255), width=3)


def p_k36_stripes(d, im):
    S, Hh = im.width, im.height
    d.rectangle([0, 0, S, Hh], fill=(220, 180, 28, 255))
    for k in range(-Hh, S, 16):
        d.polygon([(k, Hh), (k + 8, Hh), (k + 8 + Hh, 0), (k + Hh, 0)], fill=(18, 18, 18, 255))


def p_k36_side(d, im):
    """the seat's side plate: the frame's lightening holes and the red 'do not touch' stencil"""
    S, Hh = im.width, im.height
    d.rectangle([0, 0, S, Hh], fill=FRAME + (255,))
    for k in range(3):
        x = S * (0.2 + 0.3 * k)
        d.ellipse([x - S * .08, Hh * .3, x + S * .08, Hh * .3 + S * .16], fill=(34, 38, 36, 255))
    d.rectangle([S * .1, Hh * .72, S * .9, Hh * .9], fill=(170, 30, 26, 255))
    H.text(d, (S * .5, Hh * .81), 'DO NOT TOUCH', Hh * .13)


def p_k36_naz(d, im):
    S, Hh = im.width, im.height
    d.rectangle([0, 0, S, Hh], fill=FRAME + (255,))
    d.rectangle([S * .3, Hh * .2, S * .7, Hh * .8], fill=(200, 120, 30, 255))
    H.text(d, (S * .5, Hh * .5), 'SURV KIT', Hh * .45, fill=(20, 20, 20), bold=True)


def p_headbox(d, im):
    S, Hh = im.width, im.height
    H.noisy(d, im, HEADPAD, 5, seed=60)
    d.rectangle([S * .1, Hh * .1, S * .9, Hh * .9], outline=(60, 62, 58, 255), width=6)
    H.text(d, (S * .5, Hh * .5), 'K-36DM', Hh * .2, fill=(40, 40, 40), bold=True)


# ---------------------------------------------------------------- build

TILE = 0.6                     # this model's texture has little free room: every painted tile but the panel smaller


def build(atlas, E):
    m = H.Mesh(atlas)
    _paint = atlas.paint
    atlas.paint = lambda w, h, fn, **kw: (_paint(max(8, int(w * TILE)), max(8, int(h * TILE)), fn, **kw)   # noqa: E731
                                          if w > 16 and h > 16 else _paint(w, h, fn, **kw))
    sw = {k: atlas.swatch(c) for k, c in dict(turq=TURQ, turq_d=TURQ_D, black=BLACK, dgrey=DGREY, mgrey=MGREY,
                                                frame=FRAME, cushion=CUSHION, yellow=YELLOW, red=RED, metal=METAL,
                                                floor=FLOOR, bezel=(30, 31, 32), harness=(84, 88, 70)).items()}
    X, Y, Z = np.array([1.0, 0, 0]), np.array([0, 1.0, 0]), np.array([0, 0, 1.0])
    P = lambda x, y, z: E + np.array([x, y, z], float)          # noqa: E731  eye-relative point
    gauges, displays = [], []
    centre, right, left = facets(E)
    fac = {0: centre, 1: right, -1: left}
    ext = {0: (-HINGE, HINGE), 1: (0.0, WING_LEN), -1: (0.0, WING_LEN)}
    bot = {0: BOT_C, 1: BOT_W, -1: BOT_W}

    def at(side, a, y):
        return fac[side].at(a, E[1] + y)

    # -- the main panel: three facets, each a 2 cm slab with its painted face
    ppm = 360
    for side in (0, -1, 1):
        a0, a1 = ext[side]
        yt = max(top_of(side, a0), top_of(side, a1)); yb = bot[side]
        W_ = int(round((a1 - a0) * ppm)); H_ = int(round((yt - yb) / math.cos(LEAN) * ppm))
        rect = _paint(W_, H_, panel_paint(side, W_, H_, a0, a1, yb, yt))
        n = 6
        for k in range(n):
            s0, s1 = k / n, (k + 1) / n
            aa0, aa1 = a0 + (a1 - a0) * s0, a0 + (a1 - a0) * s1
            t0, t1 = (top_of(side, aa0) - yb) / (yt - yb), (top_of(side, aa1) - yb) / (yt - yb)
            pts = [at(side, aa0, yb), at(side, aa1, yb), at(side, aa1, top_of(side, aa1)), at(side, aa0, top_of(side, aa0))]
            su = (lambda s: 1 - s) if side == -1 else (lambda s: s)
            m.face(pts, [m.uv(rect, su(s0), 0), m.uv(rect, su(s1), 0), m.uv(rect, su(s1), t1), m.uv(rect, su(s0), t0)],
                   normal=fac[side].N)
        back = -fac[side].N * 0.02
        corners = [at(side, a0, yb), at(side, a1, yb), at(side, a1, top_of(side, a1)), at(side, a0, top_of(side, a0))]
        m.flat([c + back for c in corners], sw['turq_d'], normal=-fac[side].N)
        mid0 = np.mean(corners, 0)
        for i in range(4):
            p, q = corners[i], corners[(i + 1) % 4]
            mid = (p + q) / 2
            m.flat([p, q, q + back, p + back], sw['turq_d'], toward=mid + (mid - mid0))

    # -- instruments
    cols, lamps = L.lamps_of('cw', 'prc')        # (English legends: the game's fonts have no Chinese)
    cache = {}
    for name, side, a, y, spec, _ in LAYOUT:
        f = fac[side]
        c = at(side, a, y)
        kind = spec[0]
        if kind == 'game':
            gauges.append({'Seat': 0, 'Kind': spec[1], 'Center': H.r5(c + f.N * H.GAUGE_LIFT), 'Normal': H.r5(f.N),
                           'Up': H.r5(f.U), 'Diameter': spec[2], 'Depth': 0.03})
        elif kind == 'annunciator':
            w, h = spec[1], spec[2]
            gauges.append({'Seat': 0, 'Kind': 'ANNUNCIATOR', 'Center': H.r5(c + f.N * 0.004), 'Normal': H.r5(f.N),
                           'Up': H.r5(f.U), 'Diameter': w, 'Width': w, 'Height': h, 'Depth': 0.02, 'Columns': cols,
                           'Lamps': lamps})
            m.frame_rect(c, f.N, f.U, w, h, 0.005, 0.008, sw['bezel'])
        elif kind == 'display':
            w = spec[2]
            if name == 'hdd':
                c = c + f.N * HDD_PROUD                                # on the face of its raised housing
            displays.append({'Seat': 0, 'Center': H.r5(c + f.N * H.DISPLAY_LIFT), 'Normal': H.r5(f.N), 'Up': H.r5(f.U),
                             'Width': w, 'Depth': 0.035, 'Kind': spec[1], 'Style': 'CRT'})
            m.frame_rect(c, f.N, f.U, w, w, 0.014, 0.008, sw['black'])
            _, n_, u_, r_ = H.frame(c, f.N, f.U)                     # the scope's rubber hood: deep on top
            hw = w / 2 + 0.014
            for s in (-1, 1):
                p0 = c + r_ * s * hw - u_ * hw; p1 = c + r_ * s * hw + u_ * hw
                m.flat([p0, p1, p1 + n_ * 0.07, p0 + n_ * 0.02], sw['black'], normal=r_ * s)
                m.flat([p0, p1, p1 + n_ * 0.07, p0 + n_ * 0.02], sw['black'], normal=-r_ * s)
            m.box(c + u_ * (hw + 0.003) + n_ * 0.035, (r_, u_, n_), (hw + 0.004, 0.003, 0.035), sw['black'])
        elif kind == 'paint_disc':
            rect = atlas.paint(32, 32, p_disc_red)
            m.disc(c + f.N * 0.008, f.N, f.U, spec[1] / 2, rect, seg=12)
            m.ring(c, f.N, f.U, spec[1] / 2, spec[1] / 2 + 0.004, 0.008, sw['bezel'], seg=12)
        elif kind == 'paint_rect':
            w, h = spec[1], spec[2]
            painter = {'weapons': p_weapons, 'gear': p_gear, 'afcs': p_afcs}[name]
            pw = 160 if w >= h else 56
            ph = max(16, int(round(pw * h / w)))
            rect = cache.get(name) or atlas.paint(pw, ph, painter)
            m.rect_tex(c + f.N * 0.004, f.N, f.U, w, h, rect)
            m.frame_rect(c, f.N, f.U, w, h, 0.004, 0.006, sw['bezel'])
            if name == 'gear':                                     # the gear lever stands out of its panel
                m.box(c + f.N * 0.04 - f.U * 0.02, (f.R, f.U, f.N), (0.006, 0.006, 0.035), sw['metal'])
                m.box(c + f.N * 0.075 - f.U * 0.02, (f.R, f.U, f.N), (0.012, 0.012, 0.010), sw['red'])

    # -- coaming: lofted from the panel lip forward to the windscreen base
    lip = []
    for side, aa in ((-1, np.linspace(WING_LEN, 0, 4)), (0, np.linspace(HINGE, -HINGE, 7)), (1, np.linspace(0, WING_LEN, 4))):
        for a in aa:
            p = at(side, a, top_of(side, a))
            if lip and np.linalg.norm(lip[-1][0] - p) < 1e-6:
                continue
            lip.append((p, fac[side]))
    lip.sort(key=lambda q: -q[0][0])
    lip_edge = [p + f.N * 0.045 + np.array([0, 0.025, 0]) for p, f in lip]
    panel_top = [p for p, f in lip]
    rows = []
    for t in (0.0, 0.35, 0.7, 1.0):
        row = []
        for le in lip_edge:
            s = (le[0] - E[0]) / max(abs(lip_edge[0][0] - E[0]), 1e-6)
            xf = E[0] + s * FRONT_X
            yf = E[1] + FRONT_Y_C + (FRONT_Y_W - FRONT_Y_C) * s * s
            z = le[2] + (E[2] + FRONT_Z - le[2]) * t
            x = le[0] + (xf - le[0]) * t
            y = le[1] + (yf - le[1]) * t + 0.03 * math.sin(math.pi * t) * (1 - s * s)
            row.append(np.array([x, y, z]))
        rows.append(row)
    for i in range(len(rows) - 1):
        for j in range(len(rows[i]) - 1):
            m.flat([rows[i][j], rows[i][j + 1], rows[i + 1][j + 1], rows[i + 1][j]], sw['black'], normal=[0, 1, 0])
    for j in range(len(lip_edge) - 1):
        a, b = lip_edge[j], lip_edge[j + 1]
        da, db = a - [0, 0.012, 0], b - [0, 0.012, 0]
        m.flat([a, b, db, da], sw['black'], toward=E)
        m.flat([da, db, panel_top[j + 1], panel_top[j]], sw['black'], normal=[0, -1, 0])
    for j in (0, len(lip_edge) - 1):                                # the coaming's side ends down to the sill
        col = [rows[i][j] for i in range(len(rows))]
        for i in range(len(col) - 1):
            p, q = col[i], col[i + 1]
            yb_ = E[1] + SILL_Y - 0.06
            m.flat([p, q, [q[0], yb_, q[2]], [p[0], yb_, p[2]]], sw['black'], toward=[E[0], yb_, (p[2] + q[2]) / 2])

    # -- ILS-31 HUD: the body on the coaming, its aft control strip, the lens, the combiner posts and frame (no
    # glass); the picture the game projects (HeadUpDisplay) fills the frame, centred on the pilot's line of sight
    hud_z0, hud_z1 = E[2] + PANEL_Z + 0.02, E[2] + PANEL_Z + 0.30
    hud_y0 = E[1] + TOP_C + 0.025
    hud_y1 = hud_y0 + 0.032                      # a low body: its control strip stays under the sight line
    ctrl = atlas.paint(160, 40, p_hud_ctrl)
    lens = atlas.paint(64, 64, p_hud_lens)
    m.box([E[0], (hud_y0 + hud_y1) / 2, (hud_z0 + hud_z1) / 2], (X, Y, Z), (0.085, (hud_y1 - hud_y0) / 2, (hud_z1 - hud_z0) / 2),
          sw['dgrey'], front=(2, -1, ctrl, -X, Y))
    m.rect_tex([E[0], hud_y1 + 0.001, (hud_z0 + hud_z1) / 2 + 0.03], [0, 1, 0], [0, 0, 1], 0.10, 0.10, lens)
    # the ILS-31's housing under it, down into the coaming (from outside it is one block, not a slab over the nose)
    hb = E[1] - 0.26
    m.box([E[0], (hb + hud_y0 - 0.002) / 2, (hud_z0 + 0.03 + hud_z1) / 2], (X, Y, Z),
          (0.083, (hud_y0 - 0.002 - hb) / 2, (hud_z1 - hud_z0 - 0.03) / 2), sw['black'])
    g_lean = math.radians(22)
    g_up = np.array([0, math.cos(g_lean), -math.sin(g_lean)])
    g_n = H.unit(np.cross(X, g_up))
    g_h, g_hw = 0.200, 0.095                   # a little larger than the real glass, for the game
    g_h = min(g_h, 2 * HUD_TOP[0] - 0.012)        # its top under the windscreen bow
    g_bot = np.array([E[0], E[1] - g_up[1] * g_h / 2, hud_z0 + 0.02])
    g_top = g_bot + g_up * g_h
    stem = g_bot[1] - 0.006 - hud_y1
    if stem > 0.004:                               # two thin posts: the view under the glass stays open
        for s in (-1, 1):
            m.box([E[0] + s * (g_hw + 0.004), hud_y1 + stem / 2, g_bot[2]], (X, Y, Z), (0.004, stem / 2, 0.005), sw['dgrey'])
    # the ILS-31's one tall glass: its top corners cut, held along its foot and up both sides, the top edge bare (the glass into the canopy glass, the symbology over it)
    H.combiner(m, g_bot, g_up, -g_n, 2 * g_hw, g_h, 'chamfer', 0.035, ('bottom', 'side'), 0.010, sw['dgrey'])
    hud = dict(centre=(g_bot + g_top) / 2, normal=-g_n, up=g_up, width=2 * g_hw, height=g_h)
    # the reference views: the HUD's control unit standing on the centre facet's top under the HUD, black, its knobs
    # toward the pilot; the IPV display on the right wing under a hood
    face = atlas.paint(240, 92, p_ils31_face)
    ear = atlas.paint(36, 60, p_ils31_ear)
    H.facet_box(m, centre, 0.0, E[1] - 0.188, 0.235, 0.09, 0.045, sw['black'], front=face)
    for s in (-1, 1):                                               # its two side blocks, standing proud of it
        H.facet_box(m, centre, s * 0.138, E[1] - 0.178, 0.034, 0.056, 0.062, sw['black'], front=ear)
    H.facet_box(m, centre, 0.218, E[1] - 0.290, 0.130 + 0.036, 0.130 + 0.036, HDD_PROUD - 0.003, sw['black'])   # the IPV's raised housing

    # -- side walls, consoles, the closing faces under the panel
    for side in (1, -1):                                            # +1 = left (x > 0)
        xw = E[0] + WALL_X * side
        wall = atlas.paint(200, 110, p_su27_wall(side))
        z0, z1 = E[2] + BACK_Z, E[2] + PANEL_Z - 0.10
        q = [[xw, E[1] + CONSOLE_Y - 0.02, z0], [xw, E[1] + CONSOLE_Y - 0.02, z1], [xw, E[1] + SILL_Y - 0.01, z1],
             [xw, E[1] + SILL_Y - 0.01, z0]]
        m.quad_tex(q if side > 0 else [q[1], q[0], q[3], q[2]], wall, normal=[-side, 0, 0])
        wing = left if side > 0 else right

        def wing_bottom_z(x):
            a = (abs(x) - HINGE) / math.cos(WING)
            return wing.at(a, E[1] + BOT_W)[2]
        xi, xo = E[0] + CONSOLE_IN * side, E[0] + WALL_X * side
        zf_i, zf_o = wing_bottom_z(CONSOLE_IN), wing_bottom_z(WALL_X)
        cy, fy = E[1] + CONSOLE_Y, E[1] + FLOOR_Y + 0.02
        zb = E[2] + BACK_Z + 0.06
        con = atlas.paint(72, 400, p_su27_console(CONSOLE_L if side > 0 else CONSOLE_R, side > 0))
        top = [[xi, cy, zb], [xo, cy, zb], [xo, cy, zf_o], [xi, cy, zf_i]]
        m.face(top, [m.uv(con, 0, 0), m.uv(con, 1, 0), m.uv(con, 1, 1), m.uv(con, 0, 1)], normal=[0, 1, 0])
        m.flat([[xi, fy, zb], [xi, fy, zf_i], [xi, cy, zf_i], [xi, cy, zb]], sw['turq_d'], normal=[-side, 0, 0])
        m.flat([[xi, fy, zb], [xo, fy, zb], [xo, cy, zb], [xi, cy, zb]], sw['turq_d'], normal=[0, 0, -1])
        m.box([xw - side * 0.015, E[1] + SILL_Y - 0.005, E[2] + (BACK_Z + PANEL_Z - 0.1) / 2], (X, Y, Z),
              (0.03, 0.01, (PANEL_Z - 0.1 - BACK_Z) / 2), sw['dgrey'])                     # canopy rail ledge
    # under the centre facet: its lower closing face back to the footwell
    bl, br = centre.at(HINGE, E[1] + BOT_C), centre.at(-HINGE, E[1] + BOT_C)
    m.flat([bl, br, br + np.array([0, 0, 0.12]), bl + np.array([0, 0, 0.12])], sw['turq_d'], normal=[0, -1, 0])
    for s in ((1, -1) if CONSOLE_IN - HINGE > 0.02 else ()):   # the footwell sides under the wings (none
        #                                                         where the console meets the hinge: it would lie on the facet)
        e = centre.at(-HINGE * s, E[1] + BOT_C)
        m.flat([e, centre.at(-HINGE * s, E[1] + BOT_W), [E[0] + s * CONSOLE_IN, E[1] + BOT_W, e[2]],
                [E[0] + s * CONSOLE_IN, E[1] + BOT_C, e[2]]], sw['turq_d'], normal=[0, 0, -1])
    # the floor: a dark plate over the model's own (white) floor, from behind the seat to the pedals
    fw = WALL_X - 0.005                                             # wall to wall (under the consoles, ahead of them)
    m.flat([P(-fw, FLOOR_Y + 0.012, BACK_Z), P(fw, FLOOR_Y + 0.012, BACK_Z),     # (1.2 cm up: the old slab under it)
            P(fw, FLOOR_Y + 0.012, PANEL_Z + 0.40), P(-fw, FLOOR_Y + 0.012, PANEL_Z + 0.40)], sw['floor'],
           normal=[0, 1, 0])
    # rudder pedals under the panel
    for s in (1, -1):
        m.box(P(s * 0.12, -0.97, 0.46), (X, H.unit([0, 1, -0.35]), H.unit([0, 0.35, 1])), (0.045, 0.07, 0.01), sw['mgrey'])
        m.box(P(s * 0.12, -1.035, 0.50), (X, Y, Z), (0.012, 0.03, 0.06), sw['dgrey'])

    # -- throttle (RUD) on the left console
    tz = 0.20
    m.box(P(0.355, CONSOLE_Y + 0.055, tz), (X, H.unit([0, 1, 0.25]), H.unit([0, -0.25, 1])), (0.009, 0.06, 0.009), sw['metal'])
    grip_ax = (X, H.unit([0, 1, 0.3]), H.unit([0, -0.3, 1]))
    m.box(P(0.355, CONSOLE_Y + 0.135, tz + 0.02), grip_ax, (0.03, 0.045, 0.04), sw['black'])
    m.box(P(0.355, CONSOLE_Y + 0.185, tz + 0.03), grip_ax, (0.026, 0.01, 0.032), sw['dgrey'])
    m.box(P(0.332, CONSOLE_Y + 0.15, tz + 0.045), grip_ax, (0.004, 0.008, 0.008), sw['red'])

    # -- K-36DM ejection seat (the reference views): the frame with its side plates and the leg guards flaring at the
    # front of the pan, the quilted pan and back cushions with the harness, the headbox with its black pad and the
    # striped top, the two stabiliser booms on its back, the red double ejection loop between the knees
    pan = atlas.paint(72, 72, p_k36_cushion(False))
    back = atlas.paint(72, 108, p_k36_cushion(True))
    headpad = atlas.paint(64, 56, p_k36_headpad)
    stripes = atlas.paint(64, 12, p_k36_stripes)
    side_pl = atlas.paint(72, 48, p_k36_side)
    naz = atlas.paint(72, 20, p_k36_naz)
    ax_b = (X, H.unit([0, 1, -0.14]), H.unit([0, 0.14, 1]))
    m.box(P(0, -0.930, -0.004), (X, Y, Z), (0.196, 0.04, 0.22), sw['frame'],
          front=(2, 1, naz, X, Y))                                                       # the base (the survival kit's front)
    m.box(P(0, -0.887, 0.026), (X, Y, Z), (0.19, 0.035, 0.21), sw['cushion'], front=(1, 1, pan, -X, Z))
    m.box(P(0, (FLOOR_Y + 0.012 - 0.970) / 2, -0.03), (X, Y, Z), (0.17, (-0.970 - FLOOR_Y - 0.012) / 2 - 0.001, 0.17),
          sw['frame'])                                                                   # its base on this model's floor
    for s in (1, -1):
        m.box(P(s * 0.212, -0.882, -0.014), (X, Y, Z), (0.012, 0.15, 0.24), sw['frame'],
              front=(0, s, side_pl, -Z * s, Y))                                         # the side plates, stencilled
        lg = (X, H.unit([0, 1, 0.6]), H.unit([0, -0.6, 1]))                              # the leg guards at the pan's front
        m.box(P(s * 0.214, -0.80, 0.20), (H.unit([1, 0, -0.25 * s]), lg[1], H.unit(np.cross([1, 0, -0.25 * s], lg[1]))),
              (0.008, 0.07, 0.035), sw['frame'])
        m.box(P(s * 0.17, -0.44, -0.404), ax_b, (0.02, 0.54, 0.025), sw['frame'])   # guide beams, their tops under the glass
        m.box(P(s * 0.198, -0.60, -0.300), ax_b, (0.008, 0.20, 0.05), sw['frame'])   # the back's side cheeks
    m.box(P(0, -0.502, -0.234), ax_b, (0.19, 0.33, 0.03), sw['cushion'], front=(2, 1, back, X, ax_b[1]))
    m.box(P(0, -0.502, -0.294), ax_b, (0.205, 0.36, 0.035), sw['frame'])
    for s in (1, -1):                                               # the harness: shoulder straps over the back cushion
        m.box(P(s * 0.06, -0.40, -0.198), ax_b, (0.022, 0.15, 0.004), sw['harness'])
    m.box(P(0, -0.835, 0.10), (X, Y, Z), (0.17, 0.006, 0.02), sw['harness'])              # the lap belt across the pan
    m.box(P(0, -0.828, 0.10), (X, Y, Z), (0.03, 0.008, 0.026), sw['metal'])               # its buckle
    m.box(P(0, -0.03, -0.314), (X, Y, Z), (0.16, 0.15, 0.10), sw['frame'])   # headbox, under the glass
    m.box(P(0, -0.02, -0.199), (X, Y, Z), (0.11, 0.09, 0.015), sw['black'], front=(2, 1, headpad, X, Y))
    m.box(P(0, 0.122, -0.300), (X, Y, Z), (0.15, 0.004, 0.075), sw['black'], front=(1, 1, stripes, X, -Z))
    for s in (1, -1):                                               # the stabiliser booms up the headbox's back
        bm = (X, H.unit([0, 1, -0.5]), H.unit([0, 0.5, 1]))
        m.box(P(s * 0.10, 0.10, -0.43), bm, (0.012, 0.09, 0.012), sw['metal'])
        m.box(P(s * 0.10, 0.18, -0.47), bm, (0.016, 0.012, 0.016), sw['dgrey'])
    red = atlas.swatch((178, 34, 30))                                 # the ejection handle: a red double loop on a striped root
    for s in (1, -1):
        for xl in (0.02, 0.09):
            m.box(P(s * xl, -0.818, 0.256), (X, Y, Z), (0.007, 0.034, 0.007), red)
        m.box(P(s * 0.055, -0.780, 0.256), (X, Y, Z), (0.042, 0.007, 0.007), red)
    m.box(P(0, -0.852, 0.256), (X, Y, Z), (0.032, 0.01, 0.01), sw['black'], front=(2, 1, stripes, X, Y))

    # -- control stick (RUS): the boot stays with the cockpit, the rest goes into the stick bone
    sz = 0.40
    m.box(P(0, FLOOR_Y + 0.057, sz), (X, Y, Z), (0.045, 0.045, 0.045), sw['dgrey'])
    stick = H.Mesh(atlas)
    col_ax = (X, H.unit([0, 1, -0.07]), H.unit([0, 0.07, 1]))
    stick.box(P(0, -0.765, sz - 0.002), col_ax, (0.011, 0.28, 0.011), sw['metal'])
    g_ax = (X, H.unit([0, 1, 0.21]), H.unit([0, -0.21, 1]))
    gc = P(0, -0.437, sz - 0.008)
    stick.box(gc, g_ax, (0.021, 0.062, 0.027), sw['black'])
    stick.box(gc + g_ax[1] * 0.066, g_ax, (0.017, 0.006, 0.022), sw['dgrey'])
    stick.box(gc + g_ax[1] * 0.07 + g_ax[2] * 0.010, g_ax, (0.006, 0.004, 0.006), sw['red'])
    stick.box(gc + g_ax[2] * 0.031 + g_ax[1] * 0.015, g_ax, (0.008, 0.012, 0.005), sw['metal'])
    stick.box(gc - g_ax[2] * 0.034 + g_ax[1] * 0.01, g_ax, (0.012, 0.035, 0.004), sw['metal'])
    foot = P(0, FLOOR_Y + 0.07, sz)
    return m, (stick, foot), gauges, displays, hud


def old_interior(E):
    """boxes (vehicle-local lo, hi) of the old cockpit: the tub inside the walls above the floor from behind the
    seat to the old panel, the old coaming and its instrument block under the windscreen, the old HUD, the old
    pedals and footwell"""
    e = np.array(E)
    return [(e + [-0.44, -1.09, BACK_Z], e + [0.44, SILL_Y - 0.005, 0.52]),
            (e + [-0.40, -0.40, 0.40], e + [0.40, -0.06, 0.86]),
            (e + [-0.12, -0.10, 0.40], e + [0.12, 0.14, 0.70]),
            (e + [-0.30, -1.09, 0.30], e + [0.30, -0.40, 0.86])]


SILL_Z = [-1.60, -1.20, -0.80, -0.40]       # the canopy's sill behind the seat (canopy_j_11a.SECTIONS)
SILL_X = [0.197, 0.339, 0.343, 0.430]
SILL_YS = [0.041, 0.019, -0.114, -0.170]


def repaint_tub(ac, atlas, E):
    """the model's old turquoise tub left round the new cockpit (the rear deck under the canopy behind the seat, the
    floor and sill strips, seen through the glass and from outside): those faces repainted in the dark grey-green
    of the cockpit's deck instead of removed (they close the fuselage). Only turquoise texels (the airframe's light
    blue camouflage is not), only within the cockpit and canopy from the canopy's tail to the panel"""
    tex = Image.open(L.texture_path(VID)).convert('RGB')
    TW, TH = tex.size
    deck, floor = atlas.swatch(DECK), atlas.swatch(FLOOR)
    lo, hi = np.array([-0.75, -1.30, -1.60]), np.array([0.75, 0.45, 1.00])
    n = 0
    for b in ac.bones:
        pm = b.get('poly_mesh')
        if not pm or not pm.get('polys') or b['name'].endswith('__cockpit') or 'stick' in b['name']:
            continue
        P = np.array(pm['positions'], float) * np.array([1, 1, -1]) / 16.0 - E
        for poly in pm['polys']:
            if not poly:
                continue
            c = P[[v[0] for v in poly]].mean(0)
            if not (np.all(c >= lo) and np.all(c <= hi)):
                continue
            u, v = np.mean([pm['uvs'][q[2]] for q in poly], axis=0)
            r, g, bl = tex.getpixel((min(int(u * TW), TW - 1), min(int((1 - v) * TH), TH - 1)))
            # behind the seat under the canopy (inside the sill line, from the canopy's tail to the seat's bulkhead)
            # the spine's own skin lies inside the cockpit: it reads as interior there, so it takes the deck colour too
            sx, sy = np.interp(c[2], SILL_Z, SILL_X), np.interp(c[2], SILL_Z, SILL_YS)
            behind = -1.62 < c[2] < BACK_Z + 0.15 and abs(c[0]) < sx - 0.01 and c[1] < sy + 0.02 and r + g + bl > 200
            if (r < 70 and g > 110 and bl > 120) or behind:
                pm['uvs'].append(atlas.flat(floor if c[1] < FLOOR_Y + 0.05 else deck))
                k = len(pm['uvs']) - 1
                for q in poly:
                    q[2] = k
                n += 1
    return n


def main(argv):
    dry = '--dry-run' in argv
    ac = L.Aircraft(VID)
    E = ac.eye(0)
    removed = H.remove_faces(ac, old_interior(E))
    removed['old sight in the view'] = H.remove_sight_clutter(ac, E)
    HUD_TOP[0] = H.view_ceiling(ac, ac.eye(0), PANEL_Z + 0.06)
    atlas = H.A.Atlas(VID, min_area=600)     # (this texture's free room is in small pieces)
    removed['turquoise faces repainted'] = repaint_tub(ac, atlas, E)
    mesh, stick, gauges, displays, hud = build(atlas, E)
    pm = H.install(ac, mesh, stick, SOURCE_BONE, STICK_BONE, gauges, displays, hud, E)
    print(f'{VID}: removed {removed}; new cockpit {len(pm["polys"])} faces, {len(gauges)} gauges')
    if not dry:
        H.write(ac, atlas)


if __name__ == '__main__':
    main(sys.argv[1:])
