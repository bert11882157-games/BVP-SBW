#!/usr/bin/env python3
"""Nanchang Q-5 cockpit, built by hand for this model (as mig_19s.py: the Q-5 grew out of the J-6, the licence
MiG-19, and kept its cockpit), from the real panel layout.

Measured off the model with sections.py and glass_sections.py (all heights and distances from the pilot's eye at
(0, 2.3438, 3.5); 1 block = 1 m on this model):
  * the canopy glass meets the fuselage at y -0.25, x +-0.37 (the canopy rails at x +-0.33); under it the tub walls
    and the sides sloping out, the side consoles at y -0.72; the floor at y -1.13;
  * the old panel block's face stands 0.63 m ahead with its top at y -0.20, reaching 0.95 m forward; the windscreen
    runs down to the nose 1.25 m ahead.
Built: the J-6's black panel (horizon at the top centre between airspeed and climb, altimeter / compass / Machmeter,
clock / g-meter / fuel, oxygen and the gear panel on the left wing, the engine gauges and the warning lights on the
right), the coaming with the reflector sight, the consoles, linings, pedals, throttle, seat and stick (into the
stick bone); English placards (the game's fonts have no Chinese). Instruments the game draws are live. No glass.

Run on the committed originals (git checkout be357476 -- the q_5 geo, asset and both textures first).
Usage: python3 tools/cockpit_models/q_5.py [--dry-run]
"""
import math, os, sys
import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import cockpit_lib as L   # noqa: E402
import handbuilt as H     # noqa: E402

VID = 'q_5'
SOURCE_BONE = 'wreck_fuselage_0__hull'
STICK_BONE = 'wreck_fuselage_1__control_stick_pitch'

TURQ = (34, 35, 37)            # the early jets' black instrument panel
TURQ_D = (24, 25, 26)
WALLC = (104, 112, 104)        # grey-green cockpit sides and consoles
WALLC_D = (84, 92, 86)
BLACK = (24, 25, 26)
DGREY = (44, 46, 48)
MGREY = (88, 92, 94)
FRAME = (62, 68, 64)           # K-36 seat structure
CUSHION = (62, 72, 58)         # the seat's dark green canvas
DECK = (70, 78, 72)            # the model's old tub and light inner skin, repainted in the linings' dark grey-green
HEADPAD = (92, 96, 88)
YELLOW = (226, 186, 30)
RED = (196, 36, 30)
METAL = (128, 132, 134)
FLOOR = (46, 50, 50)

# ---------------------------------------------------------------- the measured cockpit (metres from the eye)
WALL_X = 0.330                 # the panel's wings end here, just outside the canopy rails
CONSOLE_OUT = 0.550            # the consoles run out over the sloping sides
SILL_Y = -0.26
FLOOR_Y = -1.13
BACK_Z = -0.45                 # behind the seat
CONSOLE_Y = -0.72
CONSOLE_IN = 0.27
PANEL_Z = 0.60                 # centre facet reference plane
LEAN = math.radians(10)
HINGE = 0.180                  # centre facet half width
WING = math.radians(18)
WING_LEN = (WALL_X - HINGE) / math.cos(WING)
TOP_C, TOP_W = -0.200, -0.330  # centre facet top, wing top at its end
BOT_C, BOT_W = -0.660, -0.660
FRONT_Z = 1.05                 # the coaming runs forward to the windscreen base
FRONT_Y_C, FRONT_Y_W = -0.250, -0.300
FRONT_X = 0.250                # the coaming's front edge: inside the narrowing windscreen

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


# Instrument layout of the MiG-19S panel. facet: 0 centre (a: metres to the pilot's right of the centre line), +1
# right wing, -1 left wing (a: metres outward from the hinge). y: from the eye.
LAYOUT = [
    ('caution',  0, -0.150, -0.200, ('paint_disc', 0.026), 'master caution lamp'),
    ('kus',      0, -0.100, -0.270, ('game', 'AIRSPEED', 0.066), 'KUS-1500 airspeed'),
    ('agi',      0,    0.000, -0.270, ('game', 'ATTITUDE', 0.080), 'AGI-1 horizon'),
    ('vvi',      0,    0.100, -0.270, ('game', 'VVI', 0.066), 'VAR-150 climb'),
    ('alt',      0, -0.100, -0.370, ('game', 'ALTIMETER', 0.066), 'VD-17 altimeter'),
    ('ksi',      0,    0.000, -0.375, ('game', 'HEADING', 0.070), 'KSI compass'),
    ('mach',     0,    0.100, -0.370, ('game', 'MACH', 0.060), 'Machmeter'),
    ('clock',    0, -0.100, -0.470, ('game', 'CLOCK', 0.054), 'clock'),
    ('g',        0,    0.000, -0.470, ('game', 'G_METER', 0.050), 'accelerometer'),
    ('fuel',     0,    0.100, -0.470, ('game', 'FUEL_QTY', 0.058), 'fuel quantity'),
    ('oxygen',  -1,    0.070, -0.330, ('game', 'FILLER_OXYGEN', 0.050), 'oxygen'),
    ('gear',    -1,    0.070, -0.450, ('paint_rect', 0.050, 0.090), 'gear control panel'),
    ('rpm',      1,    0.070, -0.326, ('game', 'ENGINE_RPM', 0.056), 'twin tachometer'),
    ('egt',      1,    0.070, -0.400, ('game', 'EGT', 0.050), 'twin EGT'),
    ('hyd',      1,    0.070, -0.490, ('game', 'FILLER_HYDRAULIC', 0.044), 'hydraulic pressure'),
    ('warning',  1,    0.070, -0.580, ('annunciator', 0.070, 0.060), 'warning lights'),
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
            for k, lab in enumerate(['BRAKE', 'EMER JETT']):
                x, yy = to_px(-0.10 + k * 0.20, -0.610)
                d.rectangle([x - 70, yy - 14, x + 70, yy + 14], fill=(18, 18, 18, 255))
                H.text(d, (x, yy), lab, 18)
    return fn


def p_weapons(d, im):
    S, Hh = im.width, im.height
    d.rectangle([0, 0, S, Hh], fill=(20, 22, 22, 255))
    labs = ['GUN', 'ROCKETS', 'MISSILE', 'BOMB', 'STORES', 'SALVO']
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


def p_sight(d, im):
    """the ASP-5N's aft face (MiG-19 photos): the sight head's rounded grey body with the collimator window, the
    target-base and range wheels either side, the reticle brightness knob and the gyro caging lever below"""
    S, Hh = im.width, im.height
    d.rectangle([0, 0, S, Hh], fill=(84, 88, 90, 255))
    d.rounded_rectangle([S * .2, Hh * .08, S * .8, Hh * .66], radius=Hh * .14, fill=(20, 21, 22, 255),
                        outline=(52, 54, 56, 255), width=4)
    cx, cy, r = S * .5, Hh * .37, Hh * .22
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=(26, 40, 50, 255), outline=(100, 108, 112, 255), width=3)
    d.ellipse([cx - r * .5, cy - r * .7, cx - r * .1, cy - r * .3], fill=(90, 120, 140, 255))
    for x, lab in ((.1, 'BASE'), (.9, 'RANGE')):                  # the knurled wheels
        d.rectangle([S * x - S * .05, Hh * .16, S * x + S * .05, Hh * .56], fill=(30, 30, 31, 255), outline=(130, 130, 130, 255), width=2)
        for k in range(6):
            y = Hh * (0.2 + k * 0.06)
            d.line([S * x - S * .04, y, S * x + S * .04, y], fill=(110, 110, 110, 255), width=2)
        H.text(d, (S * x, Hh * .66), lab, Hh * .08)
    d.ellipse([S * .3, Hh * .74, S * .38, Hh * .88], fill=(14, 14, 15, 255), outline=(130, 130, 130, 255), width=2)
    d.rectangle([S * .6, Hh * .74, S * .64, Hh * .9], fill=(200, 200, 196, 255))
    H.text(d, (S * .5, Hh * .95), 'SIGHT', Hh * .07, bold=True)


def p_m19_cushion(is_back):
    """the MiG-19 seat's dark green canvas cushions, stitched in squares; the back with the harness"""
    def fn(d, im):
        S, Hh = im.width, im.height
        d.rectangle([0, 0, S, Hh], fill=CUSHION + (255,))
        dark = tuple(c - 16 for c in CUSHION) + (255,)
        for k in range(1, 3):
            d.line([S * k / 3, 0, S * k / 3, Hh], fill=dark, width=3)
        for k in range(1, 4 if is_back else 3):
            d.line([0, Hh * k / (4 if is_back else 3), S, Hh * k / (4 if is_back else 3)], fill=dark, width=3)
        d.rectangle([2, 2, S - 3, Hh - 3], outline=dark, width=3)
        if is_back:
            for x in (S * .3, S * .7):
                d.rectangle([x - S * .05, 0, x + S * .05, Hh * .7], fill=(104, 104, 86, 255))
            d.rectangle([S * .42, Hh * .6, S * .58, Hh * .72], fill=(150, 150, 146, 255))
    return fn


def p_m19_headpad(d, im):
    S, Hh = im.width, im.height
    d.rectangle([0, 0, S, Hh], fill=(40, 46, 40, 255))
    d.rounded_rectangle([S * .12, Hh * .14, S * .88, Hh * .86], radius=S * .1, fill=(58, 66, 56, 255),
                        outline=(24, 28, 24, 255), width=3)


def p_m19_stripes(d, im):
    S, Hh = im.width, im.height
    d.rectangle([0, 0, S, Hh], fill=(226, 186, 30, 255))
    for k in range(-Hh, S, 12):
        d.polygon([(k, Hh), (k + 6, Hh), (k + 6 + Hh, 0), (k + Hh, 0)], fill=(18, 18, 18, 255))


def p_mig19_console(rows, mirror, finish):
    """a console top from above (tile top = forward): black sub-panels with Cyrillic titles, each its own kind of
    controls; mirror for the left console (its tile runs inner to outer, the pilot's right to left)"""
    def paint(d, im):
        S, Hh = im.width, im.height
        d.rectangle([0, 0, S, Hh], fill=tuple(finish) + (255,))
        y = Hh * 0.01
        for share, title, kind in rows:
            y1 = y + share * Hh
            if kind == 'throttle':
                d.rectangle([S * .08, y + 3, S * .92, y1 - 3], fill=(30, 33, 32, 255))
                d.rectangle([S * .22, y + Hh * .02, S * .34, y1 - Hh * .02], fill=(8, 8, 8, 255))
                for j in range(6):
                    yy = y + Hh * .025 + (y1 - y - Hh * .05) * j / 5
                    d.line([S * .38, yy, S * .5, yy], fill=(220, 220, 210, 255), width=2)
                for j, lab in enumerate(['A/B', 'MAX', 'IDLE', 'STOP']):
                    H.text(d, (S * .7, y + Hh * .03 + (y1 - y - Hh * .06) * j / 3), lab, S * .08)
                y = y1
                continue
            d.rectangle([S * .07, y + 3, S * .93, y1 - 3], fill=(22, 24, 24, 255), outline=(60, 64, 62, 255), width=2)
            H.text(d, (S * .5, y + Hh * .012), title, min(S * .09, Hh * .011))
            cy, top = (y + y1) / 2 + Hh * .006, y + Hh * .024
            if kind == 'guarded':
                for j in range(3):
                    x = S * (0.24 + 0.26 * j)
                    d.rectangle([x - S * .07, cy - Hh * .016, x + S * .07, cy + Hh * .016], fill=(170, 30, 26, 255),
                                outline=(60, 10, 8, 255), width=2)
            elif kind == 'rotary':
                for j in range(2):
                    x, r = S * (0.3 + 0.4 * j), S * .1
                    for a in range(-120, 121, 40):
                        t_ = math.radians(a)
                        d.line([x + r * 1.3 * math.sin(t_), cy - r * 1.3 * math.cos(t_), x + r * 1.6 * math.sin(t_),
                                cy - r * 1.6 * math.cos(t_)], fill=(210, 210, 200, 255), width=2)
                    d.ellipse([x - r, cy - r, x + r, cy + r], fill=(10, 10, 11, 255), outline=(110, 110, 110, 255), width=2)
                    d.line([x, cy, x + r * .8 * math.sin(.5 * (j + 2)), cy - r * .8 * math.cos(.5 * (j + 2))],
                           fill=(235, 235, 225, 255), width=3)
            elif kind == 'freq':
                d.rectangle([S * .18, top + Hh * .004, S * .82, top + Hh * .022], fill=(8, 8, 8, 255), outline=(90, 90, 90, 255))
                H.text(d, (S * .5, top + Hh * .013), '131.25', Hh * .014, fill=(230, 200, 120))
                for x in (.3, .7):
                    d.ellipse([S * x - S * .08, cy + Hh * .012 - S * .08, S * x + S * .08, cy + Hh * .012 + S * .08],
                              fill=(14, 14, 15, 255), outline=(120, 120, 120, 255), width=2)
            elif kind == 'toggles':
                nrow = max(1, int((y1 - top) / (Hh * .03)))
                for r_ in range(nrow):
                    yy = top + (y1 - top) * (r_ + .5) / nrow
                    for j in range(5):
                        x = S * (0.16 + 0.17 * j)
                        d.ellipse([x - 5, yy - 5, x + 5, yy + 5], fill=(120, 122, 118, 255))
                        d.rectangle([x - 2, yy - Hh * .011, x + 2, yy], fill=(205, 205, 200, 255))
            elif kind == 'breakers':
                for r_ in range(3):
                    yy = top + (y1 - top) * (r_ + .5) / 3
                    for j in range(6):
                        x = S * (0.14 + 0.145 * j)
                        d.ellipse([x - 6, yy - 6, x + 6, yy + 6], fill=(6, 6, 6, 255), outline=(150, 150, 140, 255), width=2)
            elif kind == 'lamps':
                for r_ in range(2):
                    for j in range(3):
                        x0 = S * (0.14 + 0.25 * j); yy = top + (y1 - top) * (r_ * .45 + .1)
                        H.lamp(d, [x0, yy, x0 + S * .2, yy + (y1 - top) * .33], (210, 190, 120) if (j + r_) % 2 else (190, 60, 50), None)
            y = y1
        if mirror:
            im.paste(im.transpose(Image.FLIP_LEFT_RIGHT))
    return paint


def p_mig19_lining(side):
    """the grey-green lining over a sloping fuselage side: seams and rivets; left the emergency gear and canopy
    handles' plate, right a breaker strip and the oxygen hose port"""
    def paint(d, im):
        S, Hh = im.width, im.height
        d.rectangle([0, 0, S, Hh], fill=WALLC_D + (255,))
        for x in (S * .33, S * .66):
            d.line([x, 0, x, Hh], fill=(64, 72, 66, 255), width=3)
        for y in (Hh * .12, Hh * .88):
            for x in range(6, S, 14):
                d.ellipse([x - 2, y - 2, x + 2, y + 2], fill=(116, 124, 116, 255))
        if side > 0:
            d.rectangle([S * .4, Hh * .3, S * .58, Hh * .7], fill=(22, 24, 24, 255))
            d.rectangle([S * .43, Hh * .38, S * .48, Hh * .62], fill=(190, 160, 30, 255))
            d.rectangle([S * .5, Hh * .38, S * .55, Hh * .62], fill=(170, 30, 26, 255))
        else:
            d.rectangle([S * .38, Hh * .28, S * .62, Hh * .72], fill=(22, 24, 24, 255))
            for r_ in range(3):
                for j in range(6):
                    x, y = S * (0.41 + 0.036 * j), Hh * (0.38 + 0.12 * r_)
                    d.ellipse([x - 3, y - 3, x + 3, y + 3], fill=(6, 6, 6, 255), outline=(140, 140, 130, 255))
            d.ellipse([S * .14, Hh * .38, S * .22, Hh * .6], fill=(30, 32, 30, 255), outline=(100, 100, 96, 255), width=3)
    return paint


# the MiG-19S's side consoles, front to back: (share of the length, title, kind)
CONSOLE_L = [(0.10, 'START', 'guarded'), (0.08, 'FUEL', 'toggles'), (0.26, 'THROTTLE', 'throttle'),
             (0.14, 'VHF', 'freq'), (0.12, 'ADF', 'freq'), (0.12, 'LIGHTS', 'rotary'), (0.12, 'OXYGEN', 'rotary')]
CONSOLE_R = [(0.12, 'WEAPONS', 'guarded'), (0.12, 'GENERATOR', 'toggles'), (0.14, 'CB', 'breakers'),
             (0.12, 'IFF', 'freq'), (0.12, 'WARN', 'lamps'), (0.12, 'AIR COND', 'rotary'), (0.18, 'CANOPY', 'guarded')]


def p_headbox(d, im):
    S, Hh = im.width, im.height
    H.noisy(d, im, HEADPAD, 5, seed=60)
    d.rectangle([S * .1, Hh * .1, S * .9, Hh * .9], outline=(60, 62, 58, 255), width=6)
    pass


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
            painter = {'gear': p_gear}[name]
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

    # -- ASP-5N gunsight on the coaming: the body, its aft face, the reflector frame (no glass)
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
    # the ASP-5N's reflector: a plain upright glass with its top corners cut, held only in its foot clamp (the glass into the canopy glass, the symbology over it)
    H.combiner(m, g_bot, g_up, -g_n, 2 * g_hw, g_h, 'chamfer', 0.025, ('bottom',), 0.010, sw['dgrey'])
    hud = dict(centre=g_c, normal=-g_n, up=g_up, width=2 * g_hw, height=g_h, style='GYRO')

    # -- consoles over the fuselage's sloping sides (those sides are the cockpit walls: no walls of our own)
    sw['wall_d'] = atlas.swatch(WALLC_D)
    for side in (1, -1):                                            # +1 = left (x > 0)
        xi, xo = E[0] + CONSOLE_IN * side, E[0] + CONSOLE_OUT * side
        cy, fy = E[1] + CONSOLE_Y, E[1] + FLOOR_Y + 0.02
        zb, zf = E[2] + BACK_Z + 0.06, E[2] + PANEL_Z - 0.06
        con = atlas.paint(72, 300, p_mig19_console(CONSOLE_L if side > 0 else CONSOLE_R, side > 0, WALLC))
        top = [[xi, cy, zb], [xo, cy, zb], [xo, cy, zf], [xi, cy, zf]]
        m.face(top, [m.uv(con, 0, 0), m.uv(con, 1, 0), m.uv(con, 1, 1), m.uv(con, 0, 1)], normal=[0, 1, 0])
        m.flat([[xi, fy, zb], [xi, fy, zf], [xi, cy, zf], [xi, cy, zb]], sw['wall_d'], normal=[-side, 0, 0])
        m.flat([[xi, fy, zb], [xo, fy, zb], [xo, cy, zb], [xi, cy, zb]], sw['wall_d'], normal=[0, 0, -1])
        m.flat([[xi, fy, zf], [xo, fy, zf], [xo, cy, zf], [xi, cy, zf]], sw['wall_d'], normal=[0, 0, 1])
        # a grey-green lining over the sloping side, from the glass line down to the console
        xs, xc = E[0] + 0.30 * side, E[0] + (CONSOLE_OUT - 0.01) * side
        ys, yc = E[1] + SILL_Y - 0.01, E[1] + CONSOLE_Y + 0.005
        lining = atlas.paint(150, 60, p_mig19_lining(side))
        q = [[xc, yc, zb - 0.06], [xc, yc, zf], [xs, ys, zf], [xs, ys, zb - 0.06]]
        q = [[v[0] - side * 0.004, v[1] + 0.003, v[2]] for v in q]   # inside the fuselage's own side
        m.quad_tex(q if side > 0 else [q[1], q[0], q[3], q[2]], lining, toward=[E[0], E[1] - 0.3, (zb + zf) / 2])
        # the bulkhead at the consoles' front between the panel wing's end and the lining (the nose's intake duct showed
        # through the gap there)
        yl0, yl1 = SILL_Y - 0.03, CONSOLE_Y + 0.005
        xl = lambda y: 0.30 + (CONSOLE_OUT - 0.01 - 0.30) * (y - (SILL_Y - 0.01)) / (CONSOLE_Y + 0.005 - (SILL_Y - 0.01))   # noqa: E731
        m.flat([[E[0] + side * (WALL_X + 0.005), E[1] + yl0, zf], [E[0] + side * (WALL_X + 0.005), E[1] + yl1, zf],
                [E[0] + side * (xl(yl1) - 0.006), E[1] + yl1, zf], [E[0] + side * (max(xl(yl0), WALL_X + 0.006) - 0.003), E[1] + yl0, zf]],
               sw['wall_d'], normal=[0, 0, -1])
    # under the centre facet: its lower closing face back to the footwell
    bl, br = centre.at(HINGE, E[1] + BOT_C), centre.at(-HINGE, E[1] + BOT_C)
    m.flat([bl, br, br + np.array([0, 0, 0.12]), bl + np.array([0, 0, 0.12])], sw['turq_d'], normal=[0, -1, 0])
    for s in ((1, -1) if CONSOLE_IN - HINGE > 0.02 else ()):   # the footwell sides under the wings (none
        #                                                         where the console meets the hinge: it would lie on the facet)
        e = centre.at(-HINGE * s, E[1] + BOT_C)
        m.flat([e, centre.at(-HINGE * s, E[1] + BOT_W), [E[0] + s * CONSOLE_IN, E[1] + BOT_W, e[2]],
                [E[0] + s * CONSOLE_IN, E[1] + BOT_C, e[2]]], sw['turq_d'], normal=[0, 0, -1])
    # the floor: a dark plate over the model's own (white) floor, from behind the seat to the pedals
    fw = CONSOLE_IN + 0.10                                          # under the consoles' inner faces too
    m.flat([P(-fw, FLOOR_Y + 0.02, BACK_Z), P(fw, FLOOR_Y + 0.02, BACK_Z),
            P(fw, FLOOR_Y + 0.02, PANEL_Z + 0.30), P(-fw, FLOOR_Y + 0.02, PANEL_Z + 0.30)], sw['floor'],
           normal=[0, 1, 0])
    # rudder pedals under the panel
    for s in (1, -1):
        m.box(P(s * 0.12, -1.02, 0.63), (X, H.unit([0, 1, -0.35]), H.unit([0, 0.35, 1])), (0.045, 0.07, 0.01), sw['mgrey'])
        m.box(P(s * 0.12, -1.08, 0.67), (X, Y, Z), (0.012, 0.03, 0.06), sw['dgrey'])

    # -- throttle (RUD) on the left console
    tz = 0.20
    m.box(P(0.335, CONSOLE_Y + 0.055, tz), (X, H.unit([0, 1, 0.25]), H.unit([0, -0.25, 1])), (0.009, 0.06, 0.009), sw['metal'])
    grip_ax = (X, H.unit([0, 1, 0.3]), H.unit([0, -0.3, 1]))
    m.box(P(0.335, CONSOLE_Y + 0.135, tz + 0.02), grip_ax, (0.03, 0.045, 0.04), sw['black'])
    m.box(P(0.335, CONSOLE_Y + 0.185, tz + 0.03), grip_ax, (0.026, 0.01, 0.032), sw['dgrey'])
    m.box(P(0.312, CONSOLE_Y + 0.15, tz + 0.045), grip_ax, (0.004, 0.008, 0.008), sw['red'])

    # -- the MiG-19's ejection seat (MiG-19 photos): grey-green frame, dark green canvas cushions with the harness,
    # the headrest pad, the leg guards, the yellow-and-black striped ejection handles at the pan's front
    pan = atlas.paint(64, 64, p_m19_cushion(False))
    back = atlas.paint(64, 96, p_m19_cushion(True))
    headpad = atlas.paint(56, 56, p_m19_headpad)
    stripes = atlas.paint(64, 12, p_m19_stripes)
    ax_b = (X, H.unit([0, 1, -0.14]), H.unit([0, 0.14, 1]))
    m.box(P(0, -1.027, -0.004), (X, Y, Z), (0.196, 0.105, 0.22), sw['frame'])          # (on this model's lower floor)
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
    for s in (1, -1):
        m.box(P(s * 0.05, -0.817, 0.256), (X, Y, Z), (0.008, 0.035, 0.0075), sw['yellow'], front=(2, 1, stripes, X, Y))
    m.box(P(0, -0.782, 0.256), (X, Y, Z), (0.058, 0.008, 0.008), sw['yellow'], front=(2, 1, stripes, X, Y))

    # -- control stick (RUS): the boot stays with the cockpit, the rest goes into the stick bone
    sz = 0.40
    m.box(P(0, -1.03, sz), (X, Y, Z), (0.045, 0.10, 0.045), sw['dgrey'])
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
    """boxes (vehicle-local lo, hi) of the old cockpit: the tub inside the rails above the floor from behind the seat
    to the old panel block, the old consoles over the sloping sides (whose skin stays), the old panel block and sight,
    the old pedals"""
    e = np.array(E)
    return [(e + [-0.32, -1.11, BACK_Z], e + [0.32, SILL_Y - 0.005, 0.62]),
            (e + [-0.55, -1.11, BACK_Z], e + [0.55, -0.705, 0.62]),
            (e + [-0.30, -0.80, 0.55], e + [0.30, -0.10, 1.00]),
            (e + [-0.30, -1.15, 0.30], e + [0.30, -0.60, 1.00])]


def repaint_tub(ac, atlas, E):
    """the model's old bright turquoise tub round the new cockpit, and the fuselage's light inner skin inside the
    canopy's sill line (the white and cyan slabs along both sides seen from the seat): repainted in the linings'
    dark grey-green instead of removed (they close the fuselage)"""
    import canopy_q_5 as CS
    secs = getattr(CS, 'SECTIONS', None) or sorted(CS.CANOPY + CS.WINDSCREEN, key=lambda s: s[0])
    SZ = [z for z, s in secs]; SX = [s[0][0] for z, s in secs]; SY = [s[0][1] for z, s in secs]
    tex = Image.open(L.texture_path(VID)).convert('RGB')
    TW, TH = tex.size
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
            if not (abs(c[0]) < 0.8 and -1.4 < c[1] < 0.5 and SZ[0] < c[2] < SZ[-1]):
                continue
            u, v = np.mean([pm['uvs'][q[2]] for q in poly], axis=0)
            r, g, bl = tex.getpixel((min(int(u * TW), TW - 1), min(int((1 - v) * TH), TH - 1)))
            inside = abs(c[0]) < np.interp(c[2], SZ, SX) - 0.01 and c[1] < np.interp(c[2], SZ, SY) + 0.02
            # the old cockpit's light inner walls sloping out under the sills: faces turned in toward the centre
            # line, within the cockpit's length (the fuselage's outer skin there faces out, and stays)
            V = P[[v[0] for v in poly]]
            nrm = sum(np.cross(V[k] - c, V[(k + 1) % len(V)] - c) for k in range(len(V)))   # (the whole polygon's)
            wall = (BACK_Z - 0.05 < c[2] < PANEL_Z + 0.10 and 0.2 < abs(c[0]) < 0.45 and -0.80 < c[1] < SILL_Y + 0.02
                    and (nrm[0] * c[0] < -0.3 * (np.linalg.norm(nrm) * abs(c[0]) + 1e-12)
                         or abs(nrm[2]) > 0.8 * np.linalg.norm(nrm)))      # (or the old bulkhead across beside the panel)
            if (r < 70 and g > 140 and bl > 150) or ((inside or wall) and r + g + bl > 200):
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
