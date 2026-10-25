#!/usr/bin/env python3
"""Ka-50 cockpit, built by hand for this model (as su_25.py), from the real panel layout.

Measured off the model with sections.py and work/handbuilt cuts (metres from the pilot's eye at (0, 3.042, 2.771);
the model is about 1.2 times life size round the cockpit):
  * the side glass comes down to y -0.85 (the sill), the walls below it at x +-0.76, the floor at y -1.70 (the
    glazed footwell under the panel reaches down to y -1.5 at z +0.3); the roof at y +0.35;
  * the flat armoured windscreen stands 1.42-1.48 ahead from y -0.10 down to its base at y -0.55; the old panel was a
    plate 1.27 ahead with a box for a glareshield at the windscreen's foot; the old seat's head box at z -0.33..-0.55.
Built (the Ka-50's cockpit as photographed and as the DCS Black Shark manual shows it):
  * a three-facet panel 0.90 ahead, 15 degrees back: the IT-23 TV monitor (Shkval) top centre with the weapons
    control panel (PUI-800) under it; the left wing the flight instruments (KPP attitude director over the PNP
    situation indicator, the airspeed, barometric and radio altimeters outboard), the right wing the vertical speed,
    rotor and engine dials, the accelerometer, fuel and the warning lights;
  * the ILS-31 HUD high on its mount over the coaming, its glass centred on the pilot's straight-ahead line;
  * side consoles under the sills, the K-37-800 seat (its tow-rocket pack behind the back), the cyclic in the middle,
    the collective on the left, rudder pedals on their stands over the model's floor.
Instruments the game draws (CockpitGauges, FlightDisplays) are live; the warning lights are an ANNUNCIATOR panel.
No canopy is made: the model keeps its own windscreen, side panes and roof frames (the flat armoured panes); they get
glass (model_glass.glaze: the model's original panes, and flat panes in the openings they leave).

Run on the committed originals (work/handbuilt/run_heli.ps1 restores them first).
Usage: python3 tools/cockpit_models/ka50.py [--dry-run]
"""
import math, os, sys
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import cockpit_lib as L   # noqa: E402
import handbuilt as H     # noqa: E402
import model_glass as MG  # noqa: E402
import free_atlas as FA   # noqa: E402

VID = 'ka50'
SOURCE_BONE = 'hull'
L.INTERIOR_BONES.add('hull')   # a helicopter: the one hull bone holds the cockpit
FA.also_used(VID, 'ad56207c', 'suspended_')   # the pylons' texels stay unpainted (main's geo drops those bones)

TURQ = (78, 140, 146)          # the Ka-50's blue-green panels
TURQ_D = (54, 100, 106)
BLACK = (24, 25, 26)
DGREY = (44, 46, 48)
MGREY = (88, 92, 94)
FRAME = (62, 66, 62)           # K-37-800 seat structure
CUSHION = (82, 84, 70)
YELLOW = (226, 186, 30)
RED = (196, 36, 30)
METAL = (128, 132, 134)
FLOOR = (46, 50, 50)

# ---------------------------------------------------------------- the measured cockpit (metres from the eye)
WALL_X = 0.56                  # panel wings end here (the side glass beside the panel at 0.60-0.65)
SILL_Y = -0.85
FLOOR_Y = -1.70                # the model's own floor (kept)
BACK_Z = -0.55
CONSOLE_Y = -0.90
CONSOLE_IN, CONSOLE_OUT = 0.40, 0.70
CONSOLE_FRONT, CONSOLE_FOOT = 0.62, -1.22   # the consoles end over the glazed footwell (its glass bounds the cabin)
PANEL_Z = 0.90                 # centre facet reference plane (at its mid height)
LEAN = math.radians(15)
HINGE = 0.24                   # centre facet half width
WING = math.radians(30)
WING_LEN = (WALL_X - HINGE) / math.cos(WING)
TOP_C, TOP_W = -0.420, -0.480
BOT_C, BOT_W = -0.950, -0.900
FRONT_Z = 1.38                 # the coaming runs forward to the windscreen base
FRONT_Y_C, FRONT_Y_W = -0.555, -0.600
COAMING_X = 0.40               # (the windscreen's side panes close in at its base)

HUD_TOP = [1.0]
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
        wings[side] = H.Facet(o + R_C * side * HINGE, R, U_C, N)
    return centre, wings[1], wings[-1]


def top_of(side, a):
    return TOP_C if side == 0 else TOP_C + (TOP_W - TOP_C) * a / WING_LEN


def bot_of(side, a):
    return BOT_C if side == 0 else BOT_C + (BOT_W - BOT_C) * a / WING_LEN


# Instrument layout after the DCS Black Shark manual's front panel. facet: 0 centre (a: metres to the pilot's right of
# the centre line), +1 right wing, -1 left wing (a: metres outward from the hinge). y: from the eye. Sizes life x 1.15.
LAYOUT = [
    # centre facet
    ('it23',     0,  0.000, -0.565, ('display', 'STORES', 0.200), 'IT-23MV TV monitor (Shkval)'),
    ('pui800',   0,  0.000, -0.800, ('paint_rect', 0.300, 0.085), 'PUI-800 weapons control panel'),
    ('caution',  0,  0.190, -0.470, ('paint_disc', 0.030), 'master warning lamp'),
    ('clock',    0, -0.185, -0.900, ('game', 'CLOCK', 0.045), 'AChS-1 clock'),
    ('gear',     0,  0.185, -0.895, ('paint_rect', 0.060, 0.080), 'gear lever and lamps'),
    # left wing
    ('adi',     -1,  0.105, -0.560, ('game', 'ATTITUDE', 0.115), 'KPP attitude director'),
    ('hsi',     -1,  0.105, -0.755, ('game', 'HEADING', 0.115), 'PNP situation indicator'),
    ('ias',     -1,  0.270, -0.540, ('game', 'AIRSPEED', 0.080), 'US-450 airspeed'),
    ('alt',     -1,  0.270, -0.690, ('game', 'ALTIMETER', 0.080), 'VD-10VK barometric altimeter'),
    ('radalt',  -1,  0.270, -0.835, ('game', 'RADAR_ALT', 0.075), 'A-036 radio altimeter'),
    # right wing
    ('vvi',      1,  0.090, -0.545, ('game', 'VVI', 0.080), 'VR-30 vertical speed'),
    ('rotor',    1,  0.215, -0.545, ('game', 'ENGINE_RPM', 0.075), 'ITE-1T rotor speed'),
    ('g',        1,  0.320, -0.560, ('game', 'G_METER', 0.055), 'accelerometer'),
    ('egt_l',    1,  0.075, -0.690, ('game', 'EGT', 0.058), 'gas temperature, left engine'),
    ('egt_r',    1,  0.165, -0.690, ('game', 'EGT', 0.058), 'gas temperature, right engine'),
    ('fuel',     1,  0.270, -0.690, ('game', 'FUEL_QTY', 0.065), 'fuel quantity'),
    ('warning',  1,  0.170, -0.830, ('annunciator', 0.200, 0.085), 'warning lights'),
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
        for y in (-0.48, -0.65, -0.86):
            x0, yy = to_px(a0, y); x1, _ = to_px(a1, y)
            d.line([x0, yy, x1, yy], fill=TURQ_D + (255,), width=SS * 2)
        for a, y in ((a0 + 0.012, yb + 0.012), (a1 - 0.012, yb + 0.012), (a0 + 0.012, yt - 0.02), (a1 - 0.012, yt - 0.02)):
            x, yy = to_px(a, y); r = SS * 2.2
            d.ellipse([x - r, yy - r, x + r, yy + r], fill=MGREY + (255,), outline=(30, 30, 30, 255))
        px_m = W_ * SS / (a1 - a0)
        for name, f, a, y, spec, _ in LAYOUT:
            if f != side:
                continue
            x, yy = to_px(a, y)
            if spec[0] in ('game', 'paint_disc'):
                dia = spec[-1]
                r = (dia / 2 * (1 + H.RIM) + 0.004) * px_m
                d.ellipse([x - r, yy - r, x + r, yy + r], fill=(20, 26, 26, 255))
                if dia > 0.05:                                     # the instrument's name plate under it
                    pw, ph = dia * 0.55 * px_m, 0.008 * px_m
                    py = yy + r + ph * 0.9
                    d.rectangle([x - pw / 2, py - ph / 2, x + pw / 2, py + ph / 2], fill=(18, 18, 18, 255))
            else:
                w = spec[-1] if spec[0] == 'display' else spec[1]
                h = spec[-1] if spec[0] == 'display' else spec[2]
                sx, sy = (w / 2 + 0.008) * px_m, (h / 2 + 0.008) * px_m
                d.rectangle([x - sx, yy - sy, x + sx, yy + sy], fill=(20, 26, 26, 255))
        if side == 0:
            for k, lab in enumerate(['Ð¨ÐšÐ’ÐÐ›', 'Ð¯Ð ÐšÐžÐ¡Ð¢Ð¬']):
                x, yy = to_px(-0.15 + k * 0.30, -0.69)
                d.rectangle([x - 46, yy - 12, x + 46, yy + 12], fill=(18, 18, 18, 255))
                H.text(d, (x, yy), lab, 16)
    return fn


def p_pui800(d, im):
    """the PUI-800 weapons control panel: the station lamps for the four pylons, the weapon type and remaining-rounds
    windows, the master arm and the burst and rate switches"""
    S, Hh = im.width, im.height
    d.rectangle([0, 0, S, Hh], fill=(20, 22, 22, 255))
    for k in range(4):
        x = S * (0.12 + k * 0.12)
        H.lamp(d, [x - 14, Hh * .12, x + 14, Hh * .36], (210, 210, 200), None, lit=(k == 1))
        H.text(d, (x, Hh * .46), str(k + 1), Hh * .12)
    for k, lab in enumerate(['ÐÐ ', 'ÐŸÐ¢Ð£Ð ', 'ÐŸÐ£Ð¨ÐšÐ']):
        x = S * (0.64 + k * 0.12)
        d.rectangle([x - 22, Hh * .12, x + 22, Hh * .36], fill=(8, 8, 8, 255), outline=(90, 90, 90, 255))
        H.text(d, (x, Hh * .24), '20' if k == 0 else ('12' if k == 1 else '460'), Hh * .14, fill=(230, 160, 60))
        H.text(d, (x, Hh * .46), lab, Hh * .1)
    for k, lab in enumerate(['Ð“Ð›ÐÐ’Ð', 'ÐžÐ§Ð•Ð Ð•Ð”Ð¬', 'Ð¢Ð•ÐœÐŸ']):
        x = S * (0.2 + k * 0.3)
        d.rectangle([x - 4, Hh * .62, x + 4, Hh * .82], fill=(200, 200, 196, 255))
        d.ellipse([x - 8, Hh * .78, x + 8, Hh * .9], fill=(120, 122, 118, 255))
        H.text(d, (x + 50, Hh * .74), lab, Hh * .1)


def p_gear(d, im):
    S, Hh = im.width, im.height
    d.rectangle([0, 0, S, Hh], fill=(20, 22, 22, 255))
    for k in range(3):
        y = Hh * (0.08 + 0.12 * k)
        H.lamp(d, [S * .2, y, S * .8, y + Hh * .09], (60, 150, 70), None, lit=True)
    H.text(d, (S / 2, Hh * .52), 'Ð¨ÐÐ¡Ð¡Ð˜', S * .16)
    d.rectangle([S * .4, Hh * .6, S * .6, Hh * .95], fill=(140, 140, 140, 255))


def p_disc_red(d, im):
    S = im.width
    d.ellipse([0, 0, S - 1, S - 1], fill=(120, 18, 14, 255))
    d.ellipse([S * .18, S * .14, S * .55, S * .4], fill=(220, 90, 80, 255))


def p_hud(d, im):
    """the ILS-31's control strip on the aft face of its body: the mode buttons and the brightness rotary"""
    S, Hh = im.width, im.height
    d.rectangle([0, 0, S, Hh], fill=(30, 32, 34, 255))
    for k, lab in enumerate(['Ð¤Ð˜Ð›Ð¬Ð¢Ð ', 'Ð¯Ð Ðš', 'ÐšÐžÐÐ¢Ð ', 'Ð¡Ð•Ð¢ÐšÐ']):
        x = S * (0.14 + k * 0.24)
        d.rectangle([x - S * .09, Hh * .25, x + S * .09, Hh * .6], fill=(14, 14, 15, 255), outline=(100, 100, 100, 255),
                    width=2)
        H.text(d, (x, Hh * .8), lab, Hh * .14)


# the Ka-50's side consoles, front to back (the DCS Black Shark manual's console figures): (share, title, kind)
CONSOLE_L = [(0.14, 'Ð—ÐÐŸÐ£Ð¡Ðš', 'guarded'), (0.12, 'Ð¢ÐžÐŸÐ›Ð˜Ð’Ðž', 'toggles'), (0.12, 'Ð -800', 'freq'),
             (0.12, 'ÐÐ’Ð¢ÐžÐŸÐ˜Ð›ÐžÐ¢', 'lamps'), (0.14, 'ÐžÐ¡Ð’Ð•Ð©', 'rotary'), (0.18, 'Ð“Ð˜Ð”Ð Ðž', 'toggles'), (0.18, 'ÐšÐ˜Ð¡Ð›ÐžÐ ÐžÐ”', 'rotary')]
CONSOLE_R = [(0.18, 'ÐŸÐ’Ð˜-800', 'keys'), (0.12, 'Ð -828', 'freq'), (0.12, 'ÐÐ Ðš-22', 'freq'),
             (0.16, 'Ð­Ð›Ð•ÐšÐ¢Ð ÐžÐŸÐ˜Ð¢ÐÐÐ˜Ð•', 'toggles'), (0.16, 'ÐÐ—Ð¡', 'breakers'), (0.12, 'Ð¡Ð˜Ð“ÐÐÐ›', 'lamps'), (0.14, 'Ð”ÐžÐšÐ£ÐœÐ•ÐÐ¢', 'rotary')]


def p_console(rows, mirror):
    """a console top from above (tile top = forward): black sub-panels with Cyrillic titles, each its own kind of
    controls; mirror for the left console"""
    def paint(d, im):
        S, Hh = im.width, im.height
        d.rectangle([0, 0, S, Hh], fill=TURQ + (255,))
        y = Hh * 0.01
        for share, title, kind in rows:
            y1 = y + share * Hh
            d.rectangle([S * .07, y + 3, S * .93, y1 - 3], fill=(22, 24, 24, 255), outline=(54, 96, 98, 255), width=2)
            H.text(d, (S * .5, y + Hh * .014), title, min(S * .08, Hh * .012))
            cy, top = (y + y1) / 2 + Hh * .006, y + Hh * .026
            if kind == 'guarded':
                for j in range(3):
                    x = S * (0.24 + 0.26 * j)
                    d.rectangle([x - S * .07, cy - Hh * .016, x + S * .07, cy + Hh * .016], fill=(170, 30, 26, 255),
                                outline=(60, 10, 8, 255), width=2)
            elif kind == 'rotary':
                for j in range(2):
                    x, r = S * (0.3 + 0.4 * j), S * .1
                    d.ellipse([x - r, cy - r, x + r, cy + r], fill=(10, 10, 11, 255), outline=(110, 110, 110, 255), width=2)
                    d.line([x, cy, x - r * .8 * math.sin(.5 * (j + 1)), cy - r * .8 * math.cos(.5 * (j + 1))],
                           fill=(235, 235, 225, 255), width=3)
            elif kind == 'freq':
                d.rectangle([S * .18, top, S * .82, top + Hh * .02], fill=(8, 8, 8, 255), outline=(90, 90, 90, 255))
                H.text(d, (S * .5, top + Hh * .01), '124.00', Hh * .013, fill=(230, 200, 120))
                for x in (.3, .7):
                    d.ellipse([S * x - S * .08, cy + Hh * .014 - S * .08, S * x + S * .08, cy + Hh * .014 + S * .08],
                              fill=(14, 14, 15, 255), outline=(120, 120, 120, 255), width=2)
            elif kind == 'keys':                                  # the PVI-800 navigation keypad and its window
                d.rectangle([S * .14, top, S * .86, top + Hh * .022], fill=(8, 8, 8, 255), outline=(90, 90, 90, 255))
                H.text(d, (S * .5, top + Hh * .011), '41Â°12.5  44Â°31.0', Hh * .011, fill=(230, 160, 60))
                for r_ in range(3):
                    for j in range(4):
                        x0, yy = S * (0.16 + 0.18 * j), top + Hh * (0.034 + 0.03 * r_)
                        d.rectangle([x0, yy, x0 + S * .13, yy + Hh * .022], fill=(60, 62, 60, 255), outline=(14, 14, 14, 255))
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
            from PIL import Image
            im.paste(im.transpose(Image.FLIP_LEFT_RIGHT))
    return paint


def p_cushion(is_back):
    def fn(d, im):
        S, Hh = im.width, im.height
        d.rectangle([0, 0, S, Hh], fill=CUSHION + (255,))
        dark = tuple(c - 18 for c in CUSHION) + (255,)
        for k in range(1, 3):
            d.line([S * k / 3, 0, S * k / 3, Hh], fill=dark, width=3)
        for k in range(1, 5 if is_back else 3):
            y = Hh * k / (5 if is_back else 3)
            d.line([0, y, S, y], fill=tuple(c - 9 for c in CUSHION) + (255,), width=2)
        d.rectangle([2, 2, S - 3, Hh - 3], outline=dark, width=3)
        if is_back:
            for x in (S * .3, S * .7):
                d.rectangle([x - S * .05, 0, x + S * .05, Hh * .7], fill=(92, 96, 80, 255))
            d.rectangle([S * .42, Hh * .6, S * .58, Hh * .72], fill=(150, 150, 146, 255))
    return fn


def p_headbox(d, im):
    S, Hh = im.width, im.height
    H.noisy(d, im, (92, 96, 88), 5, seed=60)
    d.rectangle([S * .1, Hh * .1, S * .9, Hh * .9], outline=(60, 62, 58, 255), width=6)
    H.text(d, (S * .5, Hh * .5), 'Ðš-37-800', Hh * .17, fill=(40, 40, 40), bold=True)


def p_stripes(d, im):
    S, Hh = im.width, im.height
    d.rectangle([0, 0, S, Hh], fill=(220, 180, 28, 255))
    for k in range(-Hh, S, 18):
        d.polygon([(k, Hh), (k + 9, Hh), (k + 9 + Hh, 0), (k + Hh, 0)], fill=(18, 18, 18, 255))


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

    def at(side, a, y):
        return fac[side].at(a, E[1] + y)

    # -- the main panel: three facets, each a 2 cm slab with its painted face
    ppm = 420
    for side in (0, -1, 1):
        a0, a1 = ext[side]
        yt = max(top_of(side, a0), top_of(side, a1)); yb = min(bot_of(side, a0), bot_of(side, a1))
        W_ = int(round((a1 - a0) * ppm)); H_ = int(round((yt - yb) / math.cos(LEAN) * ppm))
        rect = atlas.paint(W_, H_, panel_paint(side, W_, H_, a0, a1, yb, yt))
        n = 6
        su = (lambda s: 1 - s) if side == -1 else (lambda s: s)
        for k in range(n):
            s0, s1 = k / n, (k + 1) / n
            aa0, aa1 = a0 + (a1 - a0) * s0, a0 + (a1 - a0) * s1
            b0, b1 = (bot_of(side, aa0) - yb) / (yt - yb), (bot_of(side, aa1) - yb) / (yt - yb)
            t0, t1 = (top_of(side, aa0) - yb) / (yt - yb), (top_of(side, aa1) - yb) / (yt - yb)
            pts = [at(side, aa0, bot_of(side, aa0)), at(side, aa1, bot_of(side, aa1)), at(side, aa1, top_of(side, aa1)),
                   at(side, aa0, top_of(side, aa0))]
            m.face(pts, [m.uv(rect, su(s0), b0), m.uv(rect, su(s1), b1), m.uv(rect, su(s1), t1), m.uv(rect, su(s0), t0)],
                   normal=fac[side].N)
        back = -fac[side].N * 0.02
        corners = [at(side, a0, bot_of(side, a0)), at(side, a1, bot_of(side, a1)), at(side, a1, top_of(side, a1)),
                   at(side, a0, top_of(side, a0))]
        m.flat([c + back for c in corners], sw['turq_d'], normal=-fac[side].N)
        mid0 = np.mean(corners, 0)
        for i in range(4):
            p, q = corners[i], corners[(i + 1) % 4]
            mid = (p + q) / 2
            m.flat([p, q, q + back, p + back], sw['turq_d'], toward=mid + (mid - mid0))

    # -- instruments
    cols, lamps = L.lamps_of('cw', 'soviet')
    for name, side, a, y, spec, _ in LAYOUT:
        f = fac[side]
        c = at(side, a, y)
        kind = spec[0]
        if kind == 'game':
            gauges.append({'Seat': 0, 'Kind': spec[1], 'Center': H.r5(c + f.N * H.GAUGE_LIFT), 'Normal': H.r5(f.N),
                           'Up': H.r5(f.U), 'Diameter': spec[2], 'Depth': 0.03})
        elif kind == 'display':
            w = spec[2]
            displays.append({'Seat': 0, 'Center': H.r5(c + f.N * H.DISPLAY_LIFT), 'Normal': H.r5(f.N), 'Up': H.r5(f.U),
                             'Width': w, 'Depth': 0.035, 'Kind': spec[1], 'Style': 'CRT'})
            m.frame_rect(c, f.N, f.U, w, w, 0.018, 0.010, sw['black'])
            H.hood(m, f, a, E[1] + y, w + 0.036, w + 0.02, 0.07, sw['black'])
        elif kind == 'annunciator':
            w, h = spec[1], spec[2]
            gauges.append({'Seat': 0, 'Kind': 'ANNUNCIATOR', 'Center': H.r5(c + f.N * 0.004), 'Normal': H.r5(f.N),
                           'Up': H.r5(f.U), 'Diameter': w, 'Width': w, 'Height': h, 'Depth': 0.02, 'Columns': cols,
                           'Lamps': lamps})
            m.frame_rect(c, f.N, f.U, w, h, 0.005, 0.008, sw['bezel'])
        elif kind == 'paint_disc':
            rect = atlas.paint(32, 32, p_disc_red)
            m.disc(c + f.N * 0.008, f.N, f.U, spec[1] / 2, rect, seg=12)
            m.ring(c, f.N, f.U, spec[1] / 2, spec[1] / 2 + 0.004, 0.008, sw['bezel'], seg=12)
        elif kind == 'paint_rect':
            w, h = spec[1], spec[2]
            painter = {'pui800': p_pui800, 'gear': p_gear}[name]
            pw = 200 if w >= h else 60
            ph = max(16, int(round(pw * h / w)))
            rect = atlas.paint(pw, ph, painter)
            m.rect_tex(c + f.N * 0.004, f.N, f.U, w, h, rect)
            m.frame_rect(c, f.N, f.U, w, h, 0.004, 0.006, sw['bezel'])
            if name == 'gear':                                     # the gear lever stands out of its panel
                m.box(c + f.N * 0.04 - f.U * 0.025, (f.R, f.U, f.N), (0.006, 0.006, 0.035), sw['metal'])
                m.box(c + f.N * 0.075 - f.U * 0.025, (f.R, f.U, f.N), (0.012, 0.012, 0.010), sw['red'])

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
    for le in lip_edge:
        le[0] = E[0] + max(-COAMING_X, min(COAMING_X, le[0] - E[0]))
    panel_top = [p for p, f in lip]
    rows = []
    for t in (0.0, 0.35, 0.7, 1.0):
        row = []
        for le in lip_edge:
            s = (le[0] - E[0]) / max(abs(lip_edge[0][0] - E[0]), 1e-6)
            xf = E[0] + s * (COAMING_X - 0.03)
            yf = E[1] + FRONT_Y_C + (FRONT_Y_W - FRONT_Y_C) * s * s
            z = le[2] + (E[2] + FRONT_Z - le[2]) * t
            x = le[0] + (xf - le[0]) * t
            y = le[1] + (yf - le[1]) * t + 0.02 * math.sin(math.pi * t) * (1 - s * s)
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
    for j in (0, len(lip_edge) - 1):                                # the coaming's side ends, down 3 cm
        col = [rows[i][j] for i in range(len(rows))]
        for i in range(len(col) - 1):
            p, q = col[i], col[i + 1]
            m.flat([p, q, q - [0, 0.03, 0], p - [0, 0.03, 0]], sw['black'], toward=[E[0], p[1], (p[2] + q[2]) / 2])

    # -- the ILS-31 HUD: a body standing high on its mount over the coaming, the combiner leaning back over it; the
    # glass centred on the pilot's straight-ahead line (the picture's boresight)
    g_h, g_hw = 0.170, 0.095
    g_h = min(g_h, 2 * HUD_TOP[0] - 0.012)
    lean = math.radians(20)
    g_up = np.array([0, math.cos(lean), -math.sin(lean)])
    g_n = H.unit(np.cross(X, g_up))
    g_c = np.array([E[0], E[1], E[2] + 1.10])
    g_bot = g_c - g_up * g_h / 2
    base_y = E[1] + TOP_C + 0.06                                   # the coaming's top at the mount
    body_top = E[1] - 0.235                                        # the projector unit, low on the coaming
    body_c = np.array([E[0], (base_y + body_top) / 2, E[2] + 1.12])
    strip = atlas.paint(128, 48, p_hud)
    m.box(body_c, (X, Y, Z), (0.09, (body_top - base_y) / 2, 0.085), sw['dgrey'], front=(2, -1, strip, -X, Y))
    m.box([E[0], body_top + 0.006, E[2] + 1.12], (X, Y, Z), (0.075, 0.006, 0.07), sw['black'])   # its exit window's frame
    for s in (-1, 1):                                              # the two posts up to the glass's foot clamp
        m.box([E[0] + s * (g_hw + 0.012), (body_top + g_bot[1]) / 2, g_bot[2]], (X, Y, Z),
              (0.007, (g_bot[1] - body_top) / 2, 0.012), sw['dgrey'])
    H.combiner(m, g_bot, g_up, -g_n, 2 * g_hw, g_h, 'rect', 0.0, ('bottom', 'side'), 0.008, sw['black'])
    hud = dict(centre=g_c, normal=-g_n, up=g_up, width=2 * g_hw, height=g_h)

    # -- side consoles under the sills, their inner faces down to the floor
    for side in (1, -1):                                            # +1 = left (x > 0)
        wing = left if side > 0 else right

        def wing_bottom_z(x):
            a = (abs(x) - HINGE) / math.cos(WING)
            return wing.at(min(a, WING_LEN), E[1] + BOT_W)[2]
        xi, xo = E[0] + CONSOLE_IN * side, E[0] + CONSOLE_OUT * side
        zf = min(wing_bottom_z(CONSOLE_IN) - 0.02, E[2] + CONSOLE_FRONT)
        cy, fy = E[1] + CONSOLE_Y, E[1] + CONSOLE_FOOT
        zb = E[2] + BACK_Z + 0.25                                   # (clear of the bulkhead behind the seat)
        tile = atlas.paint(60, 300, p_console(CONSOLE_L if side > 0 else CONSOLE_R, side > 0))
        top = [[xi, cy, zb], [xo, cy, zb], [xo, cy, zf], [xi, cy, zf]]
        m.face(top, [m.uv(tile, 0, 0), m.uv(tile, 1, 0), m.uv(tile, 1, 1), m.uv(tile, 0, 1)], normal=[0, 1, 0])
        m.flat([[xi, fy, zb], [xi, fy, zf], [xi, cy, zf], [xi, cy, zb]], sw['turq_d'], normal=[-side, 0, 0])
        m.flat([[xi, fy, zb], [xo, fy, zb], [xo, cy, zb], [xi, cy, zb]], sw['turq_d'], normal=[0, 0, -1])
        m.flat([[xi, fy, zf], [xo, fy, zf], [xo, cy, zf], [xi, cy, zf]], sw['turq_d'], normal=[0, 0, 1])
    # under the centre facet: its lower closing face, and the footwell's sides under the wings
    bl, br = centre.at(HINGE, E[1] + BOT_C), centre.at(-HINGE, E[1] + BOT_C)
    m.flat([bl, br, br + np.array([0, 0, 0.12]), bl + np.array([0, 0, 0.12])], sw['turq_d'], normal=[0, -1, 0])
    for side in (1, -1):
        wing = left if side > 0 else right
        a, b = wing.at(0, E[1] + BOT_W), wing.at(WING_LEN, E[1] + BOT_W)
        m.flat([a, b, b + np.array([0, 0, 0.10]), a + np.array([0, 0, 0.10])], sw['turq_d'], normal=[0, -1, 0])

    # -- rudder pedals over the glazed footwell, hung from their beam under the panel
    for s in (1, -1):
        m.box(P(s * 0.13, -1.25, 0.66), (X, H.unit([0, 1, -0.35]), H.unit([0, 0.35, 1])), (0.05, 0.08, 0.012), sw['mgrey'])
        m.box(P(s * 0.13, -1.06, 0.72), (X, H.unit([0, 1, 0.45]), H.unit([0, -0.45, 1])), (0.012, 0.13, 0.014), sw['dgrey'])
    m.box(P(0, -0.96, 0.78), (X, Y, Z), (0.20, 0.02, 0.025), sw['dgrey'])

    # -- the K-37-800 seat: its bucket on the rails, olive cushions, the tall back with the head box, and behind the
    # back the tow-rocket pack that pulls the pilot out (yellow and black striped on its top)
    pan = atlas.paint(64, 64, p_cushion(False))
    back = atlas.paint(64, 96, p_cushion(True))
    headbox = atlas.paint(64, 48, p_headbox)
    stripes = atlas.paint(64, 12, p_stripes)
    ax_b = (X, H.unit([0, 1, -0.18]), H.unit([0, 0.18, 1]))
    m.box(P(0, -1.36, -0.02), (X, Y, Z), (0.21, 0.32, 0.24), sw['frame'])                   # the bucket and its base
    m.box(P(0, -1.005, 0.0), (X, Y, Z), (0.20, 0.035, 0.23), sw['cushion'], front=(1, 1, pan, -X, Z))
    for s in (1, -1):
        m.box(P(s * 0.222, -0.97, -0.03), (X, Y, Z), (0.012, 0.11, 0.24), sw['frame'])
    m.box(P(0, -0.60, -0.31), ax_b, (0.20, 0.38, 0.03), sw['cushion'], front=(2, 1, back, X, ax_b[1]))
    m.box(P(0, -0.60, -0.37), ax_b, (0.215, 0.41, 0.035), sw['frame'])
    m.box(P(0, -0.07, -0.43), (X, Y, Z), (0.15, 0.12, 0.08), sw['frame'], front=(2, 1, headbox, X, Y))   # head box
    m.box(P(0, -0.51, -0.465), ax_b, (0.12, 0.20, 0.05), sw['dgrey'])                        # the tow-rocket pack
    m.box(P(0, -0.31, -0.53), ax_b, (0.12, 0.006, 0.06), sw['black'], front=(1, 1, stripes, X, -ax_b[2]))
    m.box(P(0, -0.95, 0.10), (X, Y, Z), (0.18, 0.006, 0.02), sw['mgrey'])                   # the lap belt and buckle
    m.box(P(0, -0.943, 0.10), (X, Y, Z), (0.03, 0.008, 0.026), sw['metal'])
    for s in (1, -1):                                               # the shoulder straps
        m.box(P(s * 0.09, -0.62, -0.27), ax_b, (0.025, 0.30, 0.006), sw['mgrey'])
    red = atlas.swatch((178, 34, 30))
    for s in (1, -1):                                               # the ejection handles on the seat's sides
        m.box(P(s * 0.236, -0.93, 0.14), (X, Y, Z), (0.006, 0.03, 0.045), red)

    # -- the cyclic: its boot on the floor, the column, the grip with its trigger and trim hat
    sz = 0.36
    m.box(P(0, -1.36, sz), (X, Y, Z), (0.08, 0.06, 0.09), sw['dgrey'])                     # its mount over the floor
    m.box(P(0, -1.28, sz), (X, Y, Z), (0.05, 0.03, 0.05), sw['black'])                     # the boot
    col_ax = (X, H.unit([0, 1, -0.10]), H.unit([0, 0.10, 1]))
    m.box(P(0, -1.02, sz + 0.02), col_ax, (0.014, 0.25, 0.014), sw['metal'])
    g_ax = (X, H.unit([0, 1, 0.2]), H.unit([0, -0.2, 1]))
    gc = P(0, -0.71, sz - 0.01)
    m.box(gc, g_ax, (0.024, 0.07, 0.03), sw['black'])
    m.box(gc + g_ax[1] * 0.074, g_ax, (0.02, 0.006, 0.025), sw['dgrey'])
    m.box(gc + g_ax[1] * 0.078 + g_ax[2] * 0.012, g_ax, (0.006, 0.004, 0.006), sw['red'])
    m.box(gc + g_ax[2] * 0.034 + g_ax[1] * 0.015, g_ax, (0.008, 0.014, 0.005), sw['metal'])

    # -- the collective on the left: from its pivot beside the seat forward and up to the grip (the twist throttle,
    # the switch box on its head)
    p0, p1 = P(0.30, -1.10, -0.10), P(0.30, -0.86, 0.32)
    d_ = H.unit(p1 - p0); up_ = H.unit(np.cross(d_, X))
    m.box(P(0.30, -1.12, -0.12), (X, Y, Z), (0.03, 0.04, 0.05), sw['dgrey'])
    m.box((p0 + p1) / 2, (X, up_, d_), (0.014, 0.014, np.linalg.norm(p1 - p0) / 2), sw['metal'])
    m.box(p1 + d_ * 0.04, (X, up_, d_), (0.022, 0.022, 0.05), sw['black'])
    m.box(p1 + d_ * 0.10 + up_ * 0.01, (X, up_, d_), (0.03, 0.03, 0.025), sw['dgrey'])
    m.box(p1 + d_ * 0.10 + up_ * 0.043, (X, up_, d_), (0.006, 0.004, 0.006), sw['red'])


    return m, None, gauges, displays, hud


def old_interior(ac, E):
    """the old cockpit: interior faces inside the cabin from behind the seat to the windscreen base, below the roof and
    inside the walls, that do not lie along the glass (the window frames stay)"""
    e = np.array(E, float)
    lo, hi = e + [-0.70, -1.66, -0.62], e + [0.70, 0.28, 1.45]
    n = 0
    for b in ac.bones:
        pm = b.get('poly_mesh')
        if not pm or not pm.get('polys') or not L.is_interior_bone(b['name']) or b['name'].endswith('__cockpit'):
            continue
        P = np.array(pm['positions'], float) * np.array([1, 1, -1]) / 16.0
        out = []
        for poly in pm['polys']:
            if poly:
                V = P[[v[0] for v in poly]]
                c = V.mean(0)
                inner = abs(c[0] - e[0]) < 0.48 or c[1] < e[1] + SILL_Y - 0.07   # (the window frames out at the
                if inner and np.all(c >= lo) and np.all(c <= hi) and not ac.hugs_glass(V, 0.04):  # sides stay)
                    n += 1
                    continue
            out.append(poly)
        pm['polys'] = out
    return n


def repaint_floor(ac, atlas, E):
    """the model's own cabin floor round the new cockpit (bright red under the seat and the cyclic, seen from the seat
    and through the glazed footwell): its faces given the cockpit's floor colour instead of removed (they close the
    fuselage)"""
    deck = atlas.swatch(FLOOR)
    e = np.array(E, float)
    n = 0
    for b in ac.bones:
        pm = b.get('poly_mesh')
        if not pm or not pm.get('polys') or not L.is_interior_bone(b['name']) or b['name'].endswith('__cockpit'):
            continue
        P = np.array(pm['positions'], float) * np.array([1, 1, -1]) / 16.0 - e
        N = np.array(pm['normals'], float)
        for poly in pm['polys']:
            if not poly:
                continue
            c = P[[v[0] for v in poly]].mean(0)
            level = abs(N[poly[0][1]][1]) > 0.7                    # (the floor's level faces only, never the skin)
            if level and abs(c[0]) < 0.74 and FLOOR_Y - 0.08 < c[1] < FLOOR_Y + 0.25 and -0.62 < c[2] < 1.30:
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
    removed = {'old interior': old_interior(ac, E)}
    HUD_TOP[0] = H.view_ceiling(ac, E, 1.10)
    atlas = H.A.Atlas(VID)
    removed['floor faces repainted'] = repaint_floor(ac, atlas, E)
    mesh, stick, gauges, displays, hud = build(atlas, E)
    pm = H.install(ac, mesh, stick, SOURCE_BONE, None, gauges, displays, hud, E)
    line, _ = MG.glaze(ac)
    comb = ac.asset.pop('CombinerGlass', None) or []               # the HUD's glass (no canopy step to take it)
    if comb:
        L.add_glass(ac.asset, comb)
    print(f'{VID}: removed {removed}; new cockpit {len(pm["polys"])} faces, {len(gauges)} gauges, '
          f'{len(displays)} displays; {line}')
    if not dry:
        H.write(ac, atlas)


if __name__ == '__main__':
    main(sys.argv[1:])
