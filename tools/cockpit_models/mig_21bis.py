#!/usr/bin/env python3
"""MiG-21bis cockpit, built by hand for this model (as mig_29.py and su_25.py), from the real panel layout.

Measured off the model with sections.py and eyeview.py (all heights and distances from the pilot's eye at
(0, 2.5342, 2.7604); 1 block = 1 m on this model):
  * the canopy glass comes down to y -0.30 at the sides (the sill, x +-0.43), the cockpit widens under the sill; the
    side consoles stand at y -0.62 from x 0.27 outward, the floor at y -1.08, the seat's bulkhead at z -0.45;
  * the old panel face stands 0.42 m ahead, the bulkhead in the nose 0.82 m ahead, the deck under the windscreen at
    y -0.26 from 0.73 m forward; the side windscreen panes come down to y -0.30.
Built:
  * a three-facet main panel 0.55 m ahead, 10 degrees back, its wings turned 20 degrees toward the pilot to the
    sill; after the DCS MiG-21bis manual, figure 6-3: the upper row beside the sight (gyro, accelerometer, the
    weapons panel), the KPP attitude director over the NPP HSI left of centre with AoA / VVI / clock beside them,
    airspeed / altimeter / radio altimeter on the left, the RP-22 radar scope right of centre, tachometer / EGT /
    fuel on the right, the SPO-10 and armament button rows;
  * the black coaming forward to the windscreen, the ASP-PFD gunsight on it;
  * side consoles with switch panels, side walls, rudder pedals, the throttle on the left console, a KM-1M seat and
    the control stick (into the stick bone).
Instruments the game draws (CockpitGauges, FlightDisplays) are live. No glass.

Run on the committed originals (git checkout be357476 -- the mig_21bis geo, asset and both textures first).
Usage: python3 tools/cockpit_models/mig_21bis.py [--dry-run]
"""
import math, os, sys
import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import cockpit_lib as L   # noqa: E402
import handbuilt as H     # noqa: E402

VID = 'mig_21bis'
SOURCE_BONE = 'wreck_fuselage_1__hull'
STICK_BONE = 'wreck_fuselage_1__control_stick_pitch'

TURQ = (74, 150, 154)          # Soviet cockpit turquoise
TURQ_D = (52, 112, 116)
BLACK = (24, 25, 26)
DGREY = (44, 46, 48)
MGREY = (88, 92, 94)
FRAME = (78, 86, 78)           # the KM-1M's grey-green structure
CUSHION = (92, 70, 50)         # its brown leather cushions
DECK = (40, 70, 72)            # the model's old bright turquoise tub, repainted in the panels' dark turquoise
RESTORE_REPAINT = DECK         # (work/handbuilt restore_exterior.py: the tub faces the canopy step takes and the restore puts back)
HEADPAD = (92, 96, 88)
YELLOW = (226, 186, 30)
RED = (196, 36, 30)
METAL = (128, 132, 134)
FLOOR = (46, 50, 50)

# ---------------------------------------------------------------- the measured cockpit (metres from the eye)
WALL_X = 0.420                 # panel wings and consoles end here (the sill rails at 0.40-0.43)
SILL_Y = -0.30
FLOOR_Y = -1.08
BACK_Z = -0.45                 # behind the seat
CONSOLE_Y = -0.62
CONSOLE_IN = 0.27
PANEL_Z = 0.55                 # centre facet reference plane
LEAN = math.radians(10)
HINGE = 0.24                   # centre facet half width
WING = math.radians(20)
WING_LEN = (WALL_X - HINGE) / math.cos(WING)
TOP_C, TOP_W = -0.140, -0.360  # centre facet top, wing top at the wall (4 cm under the side glass)
BOT_C, BOT_W = -0.700, -0.600
FRONT_Z = 0.80                 # the coaming runs forward to the deck under the windscreen
FRONT_Y_C, FRONT_Y_W = -0.240, -0.360

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


# Instrument layout after the DCS MiG-21bis manual, figure 6-3. facet: 0 centre (a: metres to the pilot's right of
# the centre line), +1 right wing, -1 left wing (a: metres outward from the hinge). y: from the eye.
LAYOUT = [
    # upper row, beside the gunsight
    ('top_l1',   0, -0.182, -0.190, ('game', 'FILLER_TEMP', 0.050), 'cabin pressure / temperature'),
    ('top_l2',   0, -0.176, -0.262, ('game', 'TURN_SLIP', 0.054), 'turn and slip'),
    ('gyro',     0,   0.150, -0.192, ('game', 'FILLER_OXYGEN', 0.052), 'oxygen'),
    ('accel',    0,   0.180, -0.262, ('game', 'G_METER', 0.056), 'accelerometer'),
    ('weapons', -1,   0.085, -0.356, ('paint_rect', 0.100, 0.090), 'weapons panel'),
    ('top_r',    1,   0.060, -0.281, ('game', 'FILLER_HYDRAULIC', 0.052), 'hydraulic pressures'),
    # button rows under them
    ('armament', 0, -0.103, -0.356, ('paint_rect', 0.180, 0.045), 'armament selector buttons'),
    ('spo10',    0,   0.118, -0.356, ('paint_rect', 0.140, 0.045), 'SPO-10 / radar buttons'),
    # the flight group left of centre
    ('adi',      0, -0.100, -0.438, ('game', 'ATTITUDE', 0.090), 'KPP attitude director'),
    ('hsi',      0, -0.100, -0.544, ('game', 'HEADING', 0.088), 'NPP horizontal situation'),
    ('aoa',      0,   0.002, -0.417, ('game', 'AOA', 0.054), 'UUA AoA'),
    ('vvi',      0,   0.002, -0.485, ('game', 'VVI', 0.058), 'VAR-300 vertical speed'),
    ('clock',    0,   0.002, -0.560, ('game', 'CLOCK', 0.052), 'AChS-1 clock'),
    ('ias',     -1,   0.075, -0.447, ('game', 'AIRSPEED', 0.068), 'KUS-2500 airspeed'),
    ('alt',     -1,   0.075, -0.526, ('game', 'ALTIMETER', 0.064), 'VD-20 altimeter'),
    ('radalt',   0, -0.201, -0.600, ('game', 'RADAR_ALT', 0.056), 'RV-UM radio altimeter'),
    # RP-22 radar scope at the top centre under the sight, the engine group on the right
    ('radar',    0,   0.000, -0.237, ('display', 'RADAR', 0.144), 'RP-22 radar scope'),
    ('rpm',      1,   0.060, -0.398, ('game', 'ENGINE_RPM', 0.068), 'ITE-2 tachometer'),
    ('egt',      1,   0.060, -0.474, ('game', 'EGT', 0.058), 'exhaust gas temperature'),
    ('fuel',     0,   0.199, -0.560, ('game', 'FUEL_QTY', 0.058), 'fuel quantity'),
    ('mach',     0,   0.105, -0.420, ('game', 'MACH', 0.058), 'Machmeter'),
    ('volts',    0,   0.105, -0.485, ('game', 'FILLER_VOLTS', 0.050), 'voltmeter'),
    ('caution',  0, -0.217, -0.335, ('paint_disc', 0.026), 'master caution lamp'),
    ('warning',  1,   0.100, -0.552, ('annunciator', 0.090, 0.075), 'warning lights'),
]


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
                if dia > 0.04:
                    pw, ph = dia * 0.55 * px_m, 0.007 * px_m
                    py = yy + r + ph * 0.9
                    d.rectangle([x - pw / 2, py - ph / 2, x + pw / 2, py + ph / 2], fill=(18, 18, 18, 255))
            else:
                w, h = (spec[2], spec[2]) if spec[0] == 'display' else (spec[1], spec[2])
                sx, sy = (w / 2 + 0.006) * px_m, (h / 2 + 0.006) * px_m
                d.rectangle([x - sx, yy - sy, x + sx, yy + sy], fill=(20, 26, 26, 255))
        if side == 0:
            for k, lab in enumerate(['ТОРМОЗ', 'АВАР. СБРОС']):
                x, yy = to_px(-0.10 + k * 0.20, -0.668)
                d.rectangle([x - 70, yy - 14, x + 70, yy + 14], fill=(18, 18, 18, 255))
                H.text(d, (x, yy), lab, 18)
    return fn


def p_weapons(d, im):
    S, Hh = im.width, im.height
    d.rectangle([0, 0, S, Hh], fill=(20, 22, 22, 255))
    labs = ['ПУШКА', 'НАР', 'УР', 'БОМБ', 'ПОДВ', 'ЗАЛП']
    for k, lab in enumerate(labs):
        x = S * (0.09 + k * 0.165)
        H.lamp(d, [x - 16, Hh * .14, x + 16, Hh * .38], (210, 210, 200), None, lit=(k == 0))
        d.rectangle([x - 4, Hh * .48, x + 4, Hh * .70], fill=(190, 190, 190, 255))
        H.text(d, (x, Hh * .86), lab, Hh * .085)


def p_gear(d, im):
    S, Hh = im.width, im.height
    d.rectangle([0, 0, S, Hh], fill=(20, 22, 22, 255))
    for k in range(3):
        y = Hh * (0.12 + 0.11 * k)
        H.lamp(d, [S * .2, y, S * .8, y + Hh * .08], (60, 150, 70), None, lit=True)
    H.text(d, (S / 2, Hh * .55), 'ШАССИ', S * .16)
    d.rectangle([S * .4, Hh * .62, S * .6, Hh * .95], fill=(140, 140, 140, 255))


def p_afcs(d, im):
    S, Hh = im.width, im.height
    d.rectangle([0, 0, S, Hh], fill=(20, 22, 22, 255))
    labs = ['ДЕМПФ', 'КРЕН', 'ВЫСОТА', 'ПРИВЕД', 'ОТКЛ']
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


def p_sight(d, im):
    """the ASP-PFD-21's aft face (MiG-21bis photos): the grey sight head with its large square eyepiece frame and
    round collimator window, the target-base and range rotaries either side, the gyro/fixed reticle switch and the
    caging lamp, the SRD-5 ranging lamp strip under the window"""
    S, Hh = im.width, im.height
    d.rectangle([0, 0, S, Hh], fill=(70, 74, 76, 255))
    d.rectangle([S * .24, Hh * .06, S * .76, Hh * .7], fill=(16, 16, 17, 255), outline=(40, 42, 44, 255), width=4)
    cx, cy, r = S * .5, Hh * .38, Hh * .26
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=(24, 38, 48, 255), outline=(96, 104, 108, 255), width=3)
    d.ellipse([cx - r * .5, cy - r * .7, cx - r * .1, cy - r * .3], fill=(90, 120, 140, 255))
    for x, lab in ((.12, 'БАЗА'), (.88, 'ДАЛЬН')):
        kx, ky, kr = S * x, Hh * .34, Hh * .12
        d.ellipse([kx - kr, ky - kr, kx + kr, ky + kr], fill=(14, 14, 15, 255), outline=(150, 150, 150, 255), width=3)
        d.line([kx, ky, kx + kr * .7, ky - kr * .6], fill=(230, 230, 220, 255), width=3)
        H.text(d, (kx, Hh * .56), lab, Hh * .08)
    for k in range(4):                                            # the SRD-5 lamp strip
        x0 = S * (0.3 + k * 0.1)
        H.lamp(d, [x0, Hh * .76, x0 + S * .08, Hh * .86], (60, 170, 70) if k == 0 else (200, 190, 150), None, lit=(k == 0))
    d.rectangle([S * .08, Hh * .74, S * .14, Hh * .9], fill=(200, 200, 196, 255))
    H.text(d, (S * .5, Hh * .94), 'АСП-ПФД-21', Hh * .07, bold=True)


def p_km1_cushion(is_back):
    """the KM-1M's brown leather cushions: buttoned in a grid; the back with the harness and stencil"""
    def fn(d, im):
        S, Hh = im.width, im.height
        d.rectangle([0, 0, S, Hh], fill=CUSHION + (255,))
        dark = tuple(c - 22 for c in CUSHION) + (255,)
        n = 4 if is_back else 3
        for i in range(1, 3):
            for k in range(1, n + 1):
                x, y = S * i / 3, Hh * k / (n + 1)
                d.ellipse([x - 3, y - 3, x + 3, y + 3], fill=dark)
        d.rectangle([2, 2, S - 3, Hh - 3], outline=dark, width=3)
        if is_back:
            for x in (S * .28, S * .72):
                d.rectangle([x - S * .05, 0, x + S * .05, Hh * .7], fill=(96, 100, 84, 255))
            d.rectangle([S * .42, Hh * .6, S * .58, Hh * .72], fill=(150, 150, 146, 255))
            H.text(d, (S * .5, Hh * .9), 'КМ-1М', Hh * .07, fill=(30, 26, 22), bold=True)
    return fn


def p_km1_headpad(d, im):
    S, Hh = im.width, im.height
    d.rectangle([0, 0, S, Hh], fill=(56, 44, 34, 255))
    d.rounded_rectangle([S * .12, Hh * .12, S * .88, Hh * .88], radius=S * .14, fill=(74, 58, 44, 255),
                        outline=(30, 24, 18, 255), width=3)


def p_km1_stripes(d, im):
    S, Hh = im.width, im.height
    d.rectangle([0, 0, S, Hh], fill=(226, 186, 30, 255))
    for k in range(-Hh, S, 12):
        d.polygon([(k, Hh), (k + 6, Hh), (k + 6 + Hh, 0), (k + Hh, 0)], fill=(18, 18, 18, 255))


# the MiG-21bis's side consoles, front to back (the DCS MiG-21bis manual's console figures): (share, title, kind)
CONSOLE_L = [(0.10, 'ЗАПУСК', 'guarded'), (0.10, 'АРК-10', 'freq'), (0.24, 'РУД', 'throttle'),
             (0.12, 'Р-832', 'freq'), (0.10, 'ФОРСАЖ', 'guarded'), (0.10, 'ЗАКРЫЛКИ', 'toggles'), (0.12, 'ОСВЕЩ', 'rotary'),
             (0.12, 'КИСЛОРОД', 'rotary')]
CONSOLE_R = [(0.12, 'РСБН-2', 'rotary'), (0.10, 'САУ', 'lamps'), (0.12, 'СРЗО', 'freq'),
             (0.14, 'ЭЛЕКТРОПИТАНИЕ', 'toggles'), (0.14, 'АЗС', 'breakers'), (0.10, 'КОНДИЦ', 'rotary'),
             (0.10, 'СПО-10', 'lamps'), (0.18, 'ФОНАРЬ', 'guarded')]


def p_mig21_console(rows, mirror):
    """a console top from above (tile top = forward): black sub-panels with Cyrillic titles, each its own kind of
    controls; mirror for the left console (its tile runs inner to outer, the pilot's right to left)"""
    def paint(d, im):
        S, Hh = im.width, im.height
        d.rectangle([0, 0, S, Hh], fill=TURQ + (255,))
        y = Hh * 0.01
        for share, title, kind in rows:
            y1 = y + share * Hh
            if kind == 'throttle':                                  # the twin throttles' quadrant and its scale
                d.rectangle([S * .08, y + 3, S * .92, y1 - 3], fill=(36, 56, 58, 255))
                for x0 in (.45,):
                    d.rectangle([S * x0, y + Hh * .02, S * (x0 + .09), y1 - Hh * .02], fill=(8, 8, 8, 255))
                for j in range(6):
                    yy = y + Hh * .025 + (y1 - y - Hh * .05) * j / 5
                    d.line([S * .66, yy, S * .8, yy], fill=(220, 220, 210, 255), width=2)
                for j, lab in enumerate(['ФОРС', 'МАКС', 'МГ', 'СТОП']):
                    H.text(d, (S * .2, y + Hh * .03 + (y1 - y - Hh * .06) * j / 3), lab, S * .09)
                y = y1
                continue
            d.rectangle([S * .07, y + 3, S * .93, y1 - 3], fill=(22, 24, 24, 255), outline=(54, 96, 98, 255), width=2)
            for sx in (.12, .88):
                for sy in (y + 9, y1 - 9):
                    d.ellipse([S * sx - 3, sy - 3, S * sx + 3, sy + 3], fill=(84, 86, 86, 255))
            H.text(d, (S * .5, y + Hh * .012), title, min(S * .1, Hh * .011))
            cy, top = (y + y1) / 2 + Hh * .006, y + Hh * .024
            if kind == 'guarded':
                for j in range(3):
                    x = S * (0.24 + 0.26 * j)
                    d.rectangle([x - S * .07, cy - Hh * .016, x + S * .07, cy + Hh * .016], fill=(170, 30, 26, 255),
                                outline=(60, 10, 8, 255), width=2)
                    d.line([x - S * .05, cy, x + S * .05, cy], fill=(110, 14, 12, 255), width=3)
            elif kind == 'rotary':
                for j in range(2):
                    x, r = S * (0.3 + 0.4 * j), S * .11
                    for a in range(-120, 121, 40):
                        t = math.radians(a)
                        d.line([x + r * 1.3 * math.sin(t), cy - r * 1.3 * math.cos(t), x + r * 1.6 * math.sin(t),
                                cy - r * 1.6 * math.cos(t)], fill=(210, 210, 200, 255), width=2)
                    d.ellipse([x - r, cy - r, x + r, cy + r], fill=(10, 10, 11, 255), outline=(110, 110, 110, 255), width=2)
                    d.line([x, cy, x - r * .8 * math.sin(.5 * (j + 1)), cy - r * .8 * math.cos(.5 * (j + 1))],
                           fill=(235, 235, 225, 255), width=3)
            elif kind == 'freq':
                d.rectangle([S * .18, top + Hh * .004, S * .82, top + Hh * .022], fill=(8, 8, 8, 255), outline=(90, 90, 90, 255))
                H.text(d, (S * .5, top + Hh * .013), '118.75', Hh * .014, fill=(230, 200, 120))
                for x in (.3, .7):
                    d.ellipse([S * x - S * .09, cy + Hh * .012 - S * .09, S * x + S * .09, cy + Hh * .012 + S * .09],
                              fill=(14, 14, 15, 255), outline=(120, 120, 120, 255), width=2)
            elif kind == 'toggles':
                nrow = max(1, int((y1 - top) / (Hh * .03)))
                for r_ in range(nrow):
                    yy = top + (y1 - top) * (r_ + .5) / nrow
                    for j in range(4):
                        x = S * (0.2 + 0.2 * j)
                        d.ellipse([x - 5, yy - 5, x + 5, yy + 5], fill=(120, 122, 118, 255))
                        d.rectangle([x - 2, yy - Hh * .011, x + 2, yy], fill=(205, 205, 200, 255))
            elif kind == 'breakers':
                for r_ in range(3):
                    yy = top + (y1 - top) * (r_ + .5) / 3
                    for j in range(5):
                        x = S * (0.17 + 0.165 * j)
                        d.ellipse([x - 6, yy - 6, x + 6, yy + 6], fill=(6, 6, 6, 255), outline=(150, 150, 140, 255), width=2)
            elif kind == 'lamps':
                for r_ in range(2):
                    for j in range(3):
                        x0 = S * (0.14 + 0.25 * j); yy = top + (y1 - top) * (r_ * .45 + .1)
                        H.lamp(d, [x0, yy, x0 + S * .2, yy + (y1 - top) * .33], (210, 190, 120) if (j + r_) % 2 else (90, 170, 90), None)
            y = y1
        if mirror:
            im.paste(im.transpose(Image.FLIP_LEFT_RIGHT))
    return paint


def p_mig21_wall(side):
    """a cockpit side wall: seams and rivets; left the emergency gear and canopy handles, right the breaker panel"""
    def paint(d, im):
        S, Hh = im.width, im.height
        d.rectangle([0, 0, S, Hh], fill=TURQ_D + (255,))
        for x in (S * .3, S * .62):
            d.line([x, 0, x, Hh], fill=(40, 88, 90, 255), width=3)
        for y in (Hh * .1, Hh * .9):
            for x in range(6, S, 14):
                d.ellipse([x - 2, y - 2, x + 2, y + 2], fill=(86, 140, 142, 255))
        d.rectangle([S * .38, Hh * .3, S * .54, Hh * .66], fill=(22, 24, 24, 255), outline=(70, 110, 112, 255), width=2)
        if side > 0:
            d.rectangle([S * .405, Hh * .4, S * .445, Hh * .6], fill=(190, 160, 30, 255))
            d.rectangle([S * .475, Hh * .4, S * .515, Hh * .6], fill=(170, 30, 26, 255))
            H.text(d, (S * .46, Hh * .35), 'АВАР. ВЫПУСК', Hh * .045)
        else:
            for r_ in range(3):
                for j in range(5):
                    x, y = S * (0.405 + 0.026 * j), Hh * (0.4 + 0.09 * r_)
                    d.ellipse([x - 3, y - 3, x + 3, y + 3], fill=(6, 6, 6, 255), outline=(140, 140, 130, 255))
            H.text(d, (S * .46, Hh * .35), 'АЗС', Hh * .05)
    return paint


def p_headbox(d, im):
    S, Hh = im.width, im.height
    H.noisy(d, im, HEADPAD, 5, seed=60)
    d.rectangle([S * .1, Hh * .1, S * .9, Hh * .9], outline=(60, 62, 58, 255), width=6)
    H.text(d, (S * .5, Hh * .5), 'КМ-1М', Hh * .2, fill=(40, 40, 40), bold=True)


# ---------------------------------------------------------------- build

def build(atlas, E):
    m = H.Mesh(atlas)
    sw = {k: atlas.swatch(c) for k, c in dict(turq=TURQ, turq_d=TURQ_D, black=BLACK, dgrey=DGREY, mgrey=MGREY,
                                                frame=FRAME, cushion=CUSHION, yellow=YELLOW, red=RED, metal=METAL,
                                                floor=FLOOR, bezel=(30, 31, 32)).items()}
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
    ppm = 600
    for side in (0, -1, 1):
        a0, a1 = ext[side]
        yt = max(top_of(side, a0), top_of(side, a1)); yb = bot[side]
        W_ = int(round((a1 - a0) * ppm)); H_ = int(round((yt - yb) / math.cos(LEAN) * ppm))
        rect = atlas.paint(W_, H_, panel_paint(side, W_, H_, a0, a1, yb, yt))
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
    cols, lamps = L.lamps_of('cw', 'soviet')
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
            painter = {'weapons': p_weapons, 'armament': p_buttons(['1', '2', '3', '4', 'ПУСК', 'СБРОС']),
                       'spo10': p_buttons(['СПО', 'РЛС', 'ОТВ', 'ИЗЛ', 'СЕЛ'])}[name]
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
            xf = E[0] + s * (WALL_X - 0.04)
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

    # -- ASP-PFD gunsight on the coaming: the body, its aft face, the reflector frame (no glass)
    sight = atlas.paint(128, 96, p_sight)
    # the reflector glass (its frame only, no glass) is centred on the pilot's straight-ahead line, so from the seat
    # the projected reticle sits at the boresight; the body stands under it on the coaming
    g_h, g_hw = 0.120, 0.065
    g_h = min(g_h, 2 * HUD_TOP[0] - 0.012)        # its top under the windscreen bow
    lean = math.radians(35)
    g_up = np.array([0, math.cos(lean), -math.sin(lean)])
    g_n = H.unit(np.cross(X, g_up))
    base_y = E[1] + TOP_C + 0.045                                  # the coaming's top
    g_c = np.array([E[0], E[1], E[2] + PANEL_Z + 0.14])
    g_c, g_h = H.fit_sight_under(VID, E, g_c, g_up, g_h, g_hw)   # (the reflector under the windscreen glass)
    g_bot, g_top = g_c - g_up * g_h / 2, g_c + g_up * g_h / 2
    body_top = base_y + 0.07                                       # the sight head's own height
    body_c = np.array([E[0], (base_y + body_top) / 2, E[2] + PANEL_Z + 0.11])
    m.box(body_c, (X, Y, Z), (0.065, (body_top - base_y) / 2, 0.10), sw['dgrey'], front=(2, -1, sight, -X, Y))
    if g_bot[1] - 0.006 > body_top + 0.003:                        # two thin posts up to the glass's lower clamp
        for s in (-1, 1):
            m.box([E[0] + s * (g_hw + 0.004), (body_top + g_bot[1] - 0.006) / 2, g_bot[2]], (X, Y, Z),
                  (0.004, (g_bot[1] - 0.006 - body_top) / 2, 0.005), sw['dgrey'])
    # the ASP-PFD-21's reflector: its top corners rounded, in a thin frame all round (the glass into the canopy glass, the symbology over it)
    H.combiner(m, g_bot, g_up, -g_n, 2 * g_hw, g_h, 'rounded', 0.035, ('bottom', 'side', 'top'), 0.005, sw['dgrey'])
    hud = dict(centre=g_c, normal=-g_n, up=g_up, width=2 * g_hw, height=g_h, style='GYRO')

    # -- side walls, consoles, the closing faces under the panel
    for side in (1, -1):                                            # +1 = left (x > 0)
        xw = E[0] + WALL_X * side
        wall = atlas.paint(180, 100, p_mig21_wall(side))
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
        con = atlas.paint(56, 300, p_mig21_console(CONSOLE_L if side > 0 else CONSOLE_R, side > 0))
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
    m.flat([P(-fw, FLOOR_Y + 0.016, BACK_Z), P(fw, FLOOR_Y + 0.016, BACK_Z),     # (clear of the old floor)
            P(fw, FLOOR_Y + 0.016, PANEL_Z + 0.25), P(-fw, FLOOR_Y + 0.016, PANEL_Z + 0.25)], sw['floor'],
           normal=[0, 1, 0])
    # rudder pedals under the panel
    for s in (1, -1):
        m.box(P(s * 0.12, -0.97, 0.56), (X, H.unit([0, 1, -0.35]), H.unit([0, 0.35, 1])), (0.045, 0.07, 0.01), sw['mgrey'])
        m.box(P(s * 0.12, -1.03, 0.60), (X, Y, Z), (0.012, 0.03, 0.06), sw['dgrey'])

    # -- throttle (RUD) on the left console
    tz = 0.20
    m.box(P(0.355, CONSOLE_Y + 0.055, tz), (X, H.unit([0, 1, 0.25]), H.unit([0, -0.25, 1])), (0.009, 0.06, 0.009), sw['metal'])
    grip_ax = (X, H.unit([0, 1, 0.3]), H.unit([0, -0.3, 1]))
    m.box(P(0.355, CONSOLE_Y + 0.135, tz + 0.02), grip_ax, (0.03, 0.045, 0.04), sw['black'])
    m.box(P(0.355, CONSOLE_Y + 0.185, tz + 0.03), grip_ax, (0.026, 0.01, 0.032), sw['dgrey'])
    m.box(P(0.332, CONSOLE_Y + 0.15, tz + 0.045), grip_ax, (0.004, 0.008, 0.008), sw['red'])

    # -- KM-1M ejection seat (MiG-21bis photos): the grey-green frame, buttoned brown leather cushions with the harness,
    # the tall headrest, the yellow-and-black striped ejection handles on the pan's front, the leg guards
    pan = atlas.paint(64, 64, p_km1_cushion(False))
    back = atlas.paint(64, 96, p_km1_cushion(True))
    headpad = atlas.paint(56, 56, p_km1_headpad)
    stripes = atlas.paint(64, 12, p_km1_stripes)
    ax_b = (X, H.unit([0, 1, -0.14]), H.unit([0, 0.14, 1]))
    m.box(P(0, -1.002, -0.004), (X, Y, Z), (0.196, 0.08, 0.22), sw['frame'])
    m.box(P(0, -0.887, 0.026), (X, Y, Z), (0.19, 0.035, 0.21), sw['cushion'], front=(1, 1, pan, -X, Z))
    for s in (1, -1):
        m.box(P(s * 0.212, -0.882, -0.014), (X, Y, Z), (0.012, 0.15, 0.24), sw['frame'])
        m.box(P(s * 0.17, -0.44, -0.404), ax_b, (0.02, 0.54, 0.025), sw['frame'])   # guide beams, their tops under the glass
        m.box(P(s * 0.214, -0.80, 0.19), (X, Y, Z), (0.008, 0.06, 0.04), sw['frame'])     # the leg guards
    m.box(P(0, -0.502, -0.234), ax_b, (0.19, 0.33, 0.03), sw['cushion'], front=(2, 1, back, X, ax_b[1]))
    m.box(P(0, -0.502, -0.294), ax_b, (0.205, 0.36, 0.035), sw['frame'])
    m.box(P(0, -0.03, -0.314), (X, Y, Z), (0.16, 0.15, 0.10), sw['frame'])   # headbox, under the glass
    m.box(P(0, -0.02, -0.199), (X, Y, Z), (0.11, 0.09, 0.015), sw['cushion'], front=(2, 1, headpad, X, Y))
    m.box(P(0, -0.835, 0.10), (X, Y, Z), (0.17, 0.006, 0.02), sw['mgrey'])                # the lap belt and buckle
    m.box(P(0, -0.828, 0.10), (X, Y, Z), (0.03, 0.008, 0.026), sw['metal'])
    for s in (1, -1):                                               # the striped ejection handles either side
        m.box(P(s * 0.05, -0.817, 0.256), (X, Y, Z), (0.008, 0.035, 0.0075), sw['yellow'], front=(2, 1, stripes, X, Y))
    m.box(P(0, -0.782, 0.256), (X, Y, Z), (0.058, 0.008, 0.008), sw['yellow'], front=(2, 1, stripes, X, Y))

    # -- control stick (RUS): the boot stays with the cockpit, the rest goes into the stick bone
    sz = 0.40
    m.box(P(0, -0.99, sz), (X, Y, Z), (0.045, 0.10, 0.045), sw['dgrey'])
    stick = H.Mesh(atlas)
    col_ax = (X, H.unit([0, 1, -0.07]), H.unit([0, 0.07, 1]))
    stick.box(P(0, -0.70, sz - 0.002), col_ax, (0.011, 0.215, 0.011), sw['metal'])
    g_ax = (X, H.unit([0, 1, 0.21]), H.unit([0, -0.21, 1]))
    gc = P(0, -0.437, sz - 0.008)
    stick.box(gc, g_ax, (0.021, 0.062, 0.027), sw['black'])
    stick.box(gc + g_ax[1] * 0.066, g_ax, (0.017, 0.006, 0.022), sw['dgrey'])
    stick.box(gc + g_ax[1] * 0.07 + g_ax[2] * 0.010, g_ax, (0.006, 0.004, 0.006), sw['red'])
    stick.box(gc + g_ax[2] * 0.031 + g_ax[1] * 0.015, g_ax, (0.008, 0.012, 0.005), sw['metal'])
    stick.box(gc - g_ax[2] * 0.034 + g_ax[1] * 0.01, g_ax, (0.012, 0.035, 0.004), sw['metal'])
    foot = P(0, -0.92, sz)
    return m, (stick, foot), gauges, displays, hud


def old_interior(E):
    """boxes (vehicle-local lo, hi) of the old cockpit: the tub inside the walls above the floor from behind the
    seat to the bulkhead in the nose, the old glareshield blocks, gunsight and pedals (the windscreen posts at
    x +-0.38 and the floor stay)"""
    e = np.array(E)
    return [(e + [-0.42, -1.04, BACK_Z], e + [0.42, SILL_Y - 0.005, 0.80]),
            (e + [-0.34, -0.36, 0.30], e + [0.34, -0.03, 0.85]),        # the old glareshield blocks and sight
            (e + [-0.25, -1.075, 0.30], e + [0.25, -0.60, 0.80]),       # the old pedals
            (e + [-0.12, -0.05, 0.50], e + [0.12, 0.08, 0.75])]


def repaint_tub(ac, atlas, E):
    """the model's old bright turquoise tub left round the new cockpit (the sills' edges, the console fronts, the
    bulkhead behind the seat, seen from the seat and through the glass), and the fuselage's light inner skin inside
    the canopy's sill line (the footwell's sides): repainted in the panels' dark
    turquoise instead of removed (they close the fuselage). Only that bright texel, only round the cockpit"""
    tex = Image.open(L.texture_path(VID)).convert('RGB')
    TW, TH = tex.size
    import canopy_mig_21bis as CS                       # the canopy's own sections give its sill line
    secs = getattr(CS, 'SECTIONS', None) or sorted(CS.CANOPY + CS.WINDSCREEN, key=lambda s: s[0])
    SZ = [z for z, s in secs]; SX = [s[0][0] for z, s in secs]; SY = [s[0][1] for z, s in secs]
    deck = atlas.swatch(DECK)
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
            if not (abs(c[0]) < 0.75 and -1.4 < c[1] < 0.5 and -1.5 < c[2] < 1.7):      # (to the windscreen's foot)
                continue
            u, v = np.mean([pm['uvs'][q[2]] for q in poly], axis=0)
            r, g, bl = tex.getpixel((min(int(u * TW), TW - 1), min(int((1 - v) * TH), TH - 1)))
            sx_, sy_ = np.interp(c[2], SZ, SX), np.interp(c[2], SZ, SY)
            inside = abs(c[0]) < sx_ - 0.01 and c[1] < sy_ + 0.02 and r + g + bl > 200   # (the fuselage's light inner skin)
            if (r < 70 and g > 140 and bl > 150) or inside:
                pm['uvs'].append(atlas.flat(deck))
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
    atlas = H.A.Atlas(VID)
    removed['faces repainted'] = repaint_tub(ac, atlas, E)
    mesh, stick, gauges, displays, hud = build(atlas, E)
    pm = H.install(ac, mesh, stick, SOURCE_BONE, STICK_BONE, gauges, displays, hud, E)
    print(f'{VID}: removed {removed}; new cockpit {len(pm["polys"])} faces, {len(gauges)} gauges')
    if not dry:
        H.write(ac, atlas)


if __name__ == '__main__':
    main(sys.argv[1:])
