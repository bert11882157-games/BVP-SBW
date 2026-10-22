#!/usr/bin/env python3
"""Su-35S cockpit, built by hand for this model (as mig_29.py and su_27.py), after the real cockpit.

Measured off the model with sections.py and eyeview.py (all heights and distances from the pilot's eye at
(0, 3.5172, 5.1405); 1 block = 1 m on this model):
  * the cockpit walls' inner faces at x +-0.45 under the sill; the canopy glass comes down to y -0.25 at the sides
    (-0.29 0.6 m ahead, -0.34 1.0 m ahead); the side consoles at y -0.62 from x 0.27 outward; the floor at y -0.91;
  * the old panel face stands 0.68 m ahead with its top at y -0.13; the nose deck slopes from there to y -0.40 at
    the windscreen base, 1.5 m ahead.
Built:
  * a three-facet main panel 0.66 m ahead, 12 degrees back, a wide centre facet and short wings turned 25 degrees
    to the walls: the two MFI-35 displays side by side, the PUI control panel under them, the standby row (attitude,
    airspeed, altimeter, VVI, clock) and the Ekran warning panel below; the weapons and gear panels on the left wing;
    fuel, engine and hydraulics on the right wing;
  * the black coaming forward down the nose deck, the IKSh-1M wide-angle HUD on it;
  * side consoles with switch panels, side walls, rudder pedals, the throttle on the left console, a K-36D-3.5 seat
    and the control stick (into the stick bone).
Instruments the game draws (CockpitGauges, FlightDisplays, HeadUpDisplay) are live. No glass.

Run on the committed originals (git checkout be357476 -- the su_35 geo, asset and both textures first).
Usage: python3 tools/cockpit_models/su_35.py [--dry-run]
"""
import math, os, sys
import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import cockpit_lib as L   # noqa: E402
import handbuilt as H     # noqa: E402

VID = 'su_35'
SOURCE_BONE = 'wreck_fuselage_0__hull'
STICK_BONE = 'wreck_fuselage_0__control_stick_pitch'

TURQ = (92, 102, 110)          # the Su-35S cockpit's blue-grey (it is not turquoise like the earlier types)
TURQ_D = (66, 74, 80)
DECK = (52, 58, 64)            # the model's old turquoise tub and the rim skin inside the canopy, repainted
BLACK = (24, 25, 26)
DGREY = (44, 46, 48)
MGREY = (88, 92, 94)
FRAME = (70, 76, 82)           # the K-36D-3.5's blue-grey structure
CUSHION = (56, 62, 70)         # its dark blue-grey cushions
HEADPAD = (36, 38, 42)
YELLOW = (226, 186, 30)
RED = (196, 36, 30)
METAL = (128, 132, 134)
FLOOR = (46, 50, 50)

# ---------------------------------------------------------------- the measured cockpit (metres from the eye)
WALL_X = 0.440                 # panel wings and consoles end here (the walls' inner faces at 0.45)
SILL_Y = -0.26
FLOOR_Y = -0.91
BACK_Z = -0.55                 # behind the seat
CONSOLE_Y = -0.62
CONSOLE_IN = 0.27
PANEL_Z = 0.74                 # centre facet reference plane (the Su-27 reference views: the panel 0.7-0.8 m ahead)
LEAN = math.radians(12)
HINGE = 0.275                  # centre facet half width: the two displays side by side
WING = math.radians(25)
WING_LEN = (WALL_X - HINGE) / math.cos(WING)
TOP_C, TOP_W = -0.120, -0.320  # centre facet top, wing top at the wall (3 cm under the side glass)
BOT_C, BOT_W = -0.700, CONSOLE_Y
FRONT_Z = 1.10                 # the coaming runs forward down the nose deck
FRONT_Y_C, FRONT_Y_W = -0.270, -0.380
SEAT_DY = 0.03                 # the floor sits 6 cm higher under the eye than the Su-27's: the seat stands higher

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


# Instrument layout of the Su-35S front panel. facet: 0 centre (a: metres to the pilot's right of the centre line),
# +1 right wing, -1 left wing (a: metres outward from the hinge). y: from the eye.
LAYOUT = [
    # centre facet: the two MFI-35s, the PUI under them, the standby row, the Ekran
    ('mfi_l',    0, -0.122, -0.300, ('display', 'RADAR', 0.212), 'MFI-35 left (tactical)'),
    ('mfi_r',    0,  0.122, -0.300, ('display', 'STORES', 0.212), 'MFI-35 right (systems / stores)'),
    ('pui',      0,     0.000, -0.468, ('paint_rect', 0.135, 0.034), 'PUI control panel'),
    ('ksei',     0, -0.200, -0.585, ('display', 'PFD', 0.085), 'KSEI standby display (it replaces the standby horizon)'),
    ('engines',  0,   0.225, -0.590, ('paint_rect', 0.070, 0.080), 'engine strip: N2 / EGT bars for both engines'),
    ('sb_ias',   0, -0.080, -0.565, ('game', 'AIRSPEED', 0.029), 'standby airspeed'),
    ('sb_alt',   0,     0.000, -0.565, ('game', 'ALTIMETER', 0.029), 'standby altimeter'),
    ('sb_vvi',   0,     0.080, -0.565, ('game', 'VVI', 0.029), 'standby VVI'),
    ('clock',    0,     0.160, -0.565, ('game', 'CLOCK', 0.026), 'clock'),
    ('ekran',    0,     0.000, -0.645, ('annunciator', 0.124, 0.029), 'Ekran warning panel'),
    ('caution',  0, -0.225, -0.158, ('paint_disc', 0.015), 'master caution lamp'),
    # left wing
    ('lights',  -1,     0.100, -0.300, ('paint_rect', 0.100, 0.046), 'emergency jettison / lights block'),
    ('weapons', -1,     0.106, -0.395, ('paint_rect', 0.130, 0.080), 'weapons control panel'),
    ('gear',    -1,     0.110, -0.525, ('paint_rect', 0.050, 0.100), 'gear control panel'),
    # right wing
    ('fuel',     1,     0.105, -0.330, ('game', 'FUEL_QTY', 0.052), 'fuel quantity'),
    ('rpm',      1,     0.105, -0.425, ('game', 'ENGINE_RPM', 0.052), 'engine tachometer'),
    ('hyd',      1,     0.105, -0.515, ('game', 'FILLER_HYDRAULIC', 0.044), 'hydraulic pressures'),
    ('oxy',      1,     0.105, -0.588, ('game', 'FILLER_OXYGEN', 0.028), 'oxygen'),
]
PLACARD = {'fuel': 'ТОПЛИВО', 'rpm': 'ОБОРОТЫ', 'hyd': 'ГИДРО', 'mfi_l': 'МФИ-35', 'mfi_r': 'МФИ-35', 'ksei': 'КСЭИ'}


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
                    ph = 0.010 * px_m; pw = max(dia * 0.6, 0.010 * 0.62 * len(lab)) * px_m
                    py = yy + r + ph * 0.7
                    d.rectangle([x - pw / 2, py - ph / 2, x + pw / 2, py + ph / 2], fill=(16, 18, 20, 255))
                    H.text(d, (x, py), lab, ph * 0.78)
            else:
                w, h = (spec[2], spec[2]) if spec[0] == 'display' else (spec[1], spec[2])
                sx, sy = (w / 2 + 0.006) * px_m, (h / 2 + 0.006) * px_m
                d.rectangle([x - sx, yy - sy, x + sx, yy + sy], fill=(20, 26, 26, 255))
        if side == 0:
            for k, lab in enumerate(['ТОРМОЗ', 'АВАР. СБРОС']):
                x, yy = to_px(-0.16 + k * 0.32, -0.700)
                d.rectangle([x - 70, yy - 14, x + 70, yy + 14], fill=(18, 18, 18, 255))
                H.text(d, (x, yy), lab, 18)
    return fn


def p_weapons(d, im):
    S, Hh = im.width, im.height
    d.rectangle([0, 0, S, Hh], fill=(20, 22, 22, 255))
    labs = ['ГЛАВН', 'ПУШКА', 'УР', 'СБРОС']                  # the Su-35S master arm block: master, gun, missiles, jettison
    for k, lab in enumerate(labs):
        x = S * (0.13 + k * 0.245)
        H.lamp(d, [x - 14, Hh * .1, x + 14, Hh * .3], (210, 210, 200), None, lit=(k == 0))
        d.rectangle([x - 4, Hh * .4, x + 4, Hh * .66], fill=(190, 190, 190, 255))
        if k in (0, 3):
            d.rectangle([x - 13, Hh * .36, x + 13, Hh * .7], outline=(180, 34, 28, 255), width=5)
        H.text(d, (x, Hh * .86), lab, Hh * .13)


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


def p_engines(d, im):
    """the engine strip beside the standby row: two pairs of vertical bars (N2 and EGT, left and right engine)"""
    S, Hh = im.width, im.height
    d.rectangle([0, 0, S, Hh], fill=(10, 12, 14, 255))
    for k, (lev, col) in enumerate(((.78, (110, 220, 120)), (.80, (110, 220, 120)), (.62, (230, 190, 90)), (.60, (230, 190, 90)))):
        x0 = S * (0.1 + k * 0.215)
        d.rectangle([x0, Hh * .12, x0 + S * .14, Hh * .82], outline=(80, 90, 90, 255), width=2)
        d.rectangle([x0 + 3, Hh * (.82 - .7 * lev), x0 + S * .14 - 3, Hh * .82 - 3], fill=col + (255,))
    H.text(d, (S * .26, Hh * .92), 'N2', Hh * .08, fill=(170, 200, 170))
    H.text(d, (S * .72, Hh * .92), 'T', Hh * .08, fill=(220, 200, 150))


def p_lights(d, im):
    """the emergency jettison and lights block: a red guarded jettison button, two toggles"""
    S, Hh = im.width, im.height
    d.rectangle([0, 0, S, Hh], fill=(20, 22, 24, 255))
    d.rectangle([S * .06, Hh * .15, S * .36, Hh * .7], fill=(170, 30, 26, 255), outline=(60, 10, 8, 255), width=3)
    H.text(d, (S * .21, Hh * .86), 'АВАР.СБР', Hh * .16)
    for k, lab in enumerate(['ФАРА', 'АНО']):
        x = S * (0.56 + k * 0.26)
        d.ellipse([x - 7, Hh * .45 - 7, x + 7, Hh * .45 + 7], fill=(120, 122, 118, 255))
        d.rectangle([x - 3, Hh * .14, x + 3, Hh * .45], fill=(205, 205, 200, 255))
        H.text(d, (x, Hh * .86), lab, Hh * .16)


def p_disc_red(d, im):
    S = im.width
    d.ellipse([0, 0, S - 1, S - 1], fill=(120, 18, 14, 255))
    d.ellipse([S * .18, S * .14, S * .55, S * .4], fill=(220, 90, 80, 255))


def p_pui(d, im):
    """the PUI: two rows of push buttons either side of a small text window"""
    S, Hh = im.width, im.height
    d.rectangle([0, 0, S, Hh], fill=(22, 24, 26, 255))
    d.rectangle([S * .38, Hh * .18, S * .62, Hh * .62], fill=(10, 30, 16, 255), outline=(80, 80, 80, 255), width=3)
    H.text(d, (S * .5, Hh * .40), 'НАВ  ВОЗД', Hh * .16, fill=(110, 220, 120))
    for row in range(2):
        for k in range(5):
            for side in (0, 1):
                x = S * ((0.04 + k * 0.066) if side == 0 else (0.66 + k * 0.066))
                y = Hh * (0.14 + row * 0.42)
                d.rectangle([x, y, x + S * .05, y + Hh * .30], fill=(60, 62, 64, 255), outline=(10, 10, 10, 255), width=2)


def p_hud_ctrl(d, im):
    """the IKSh-1M body's aft edge: a plain dark strip with its two fixing screws"""
    S, Hh = im.width, im.height
    d.rectangle([0, 0, S, Hh], fill=(30, 32, 34, 255))
    d.line([0, Hh * .5, S, Hh * .5], fill=(18, 18, 20, 255), width=3)
    for x in (.08, .92):
        d.ellipse([S * x - 5, Hh * .5 - 5, S * x + 5, Hh * .5 + 5], fill=(80, 82, 84, 255))


def p_iksh_face(d, im):
    """the IKSh-1M's control strip toward the pilot (Su-35S photos): a row of square mode keys with white legends
    either side of the brightness and contrast rotaries, the mode window in the middle"""
    S, Hh = im.width, im.height
    d.rectangle([0, 0, S, Hh], fill=(26, 28, 30, 255))
    d.rectangle([S * .02, Hh * .08, S * .98, Hh * .92], outline=(58, 60, 64, 255), width=3)
    keys = ['ДЛЬН', 'БЛЖ', 'ОПТ', 'ЗЕМ', 'ПРИБ', 'ВОЗВ']
    for k, lab in enumerate(keys):
        x = S * (0.06 + k * 0.058) if k < 3 else S * (0.66 + (k - 3) * 0.1)
        d.rectangle([x, Hh * .2, x + S * .048, Hh * .62], fill=(58, 60, 62, 255), outline=(12, 12, 12, 255), width=2)
        H.text(d, (x + S * .024, Hh * .41), lab, Hh * .13)
    for x, lab in ((.30, 'ЯРК'), (.56, 'КОНТР')):                    # the two rotaries
        cx, cy, r = S * x, Hh * .42, Hh * .22
        d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=(12, 12, 13, 255), outline=(110, 110, 110, 255), width=3)
        d.line([cx, cy, cx + r * .7, cy - r * .5], fill=(230, 230, 220, 255), width=3)
        H.text(d, (cx, Hh * .8), lab, Hh * .13)
    d.rectangle([S * .38, Hh * .24, S * .48, Hh * .58], fill=(10, 30, 16, 255), outline=(80, 80, 80, 255), width=2)
    H.text(d, (S * .43, Hh * .41), 'НАВ', Hh * .14, fill=(110, 220, 120))
    H.text(d, (S * .43, Hh * .8), 'ИКШ-1М', Hh * .13, bold=True)


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
    for k, lab in enumerate(['ЯРК', 'БАЗА', 'ДАЛЬН']):
        x = S * (0.2 + k * 0.3)
        d.ellipse([x - 14, Hh * .74 - 14, x + 14, Hh * .74 + 14], fill=(22, 22, 22, 255), outline=(140, 140, 140, 255),
                  width=3)
        H.text(d, (x, Hh * .93), lab, Hh * .09)


# the Su-35S side consoles, front to back: (share of the length, title, kind)
CONSOLE_L = [(0.09, 'ЗАПУСК', 'guarded'), (0.08, 'ТОПЛИВО', 'toggles'), (0.24, 'РУД', 'throttle'),
             (0.12, 'Р-862', 'freq'), (0.10, 'ПУ САУ', 'lamps'), (0.10, 'ОСВЕЩ', 'rotary'), (0.12, 'ШАССИ ТОРМ', 'toggles'),
             (0.13, 'КИСЛОРОД', 'rotary')]
CONSOLE_R = [(0.12, 'ПУИ ОЛС', 'keys'), (0.10, 'ВООРУЖ', 'guarded'), (0.10, 'ГОСУДАРСТВ', 'freq'),
             (0.14, 'ЭЛЕКТРОПИТАНИЕ', 'toggles'), (0.13, 'АЗС', 'breakers'), (0.10, 'КОНДИЦ', 'rotary'),
             (0.10, 'БРЭО', 'lamps'), (0.19, 'ФОНАРЬ', 'guarded')]


def p_su35_console(rows, mirror):
    """a console top from above (tile top = forward): black sub-panels with Cyrillic titles, each its own kind of
    controls; mirror for the left console (its tile runs inner to outer, the pilot's right to left)"""
    def paint(d, im):
        S, Hh = im.width, im.height
        d.rectangle([0, 0, S, Hh], fill=TURQ + (255,))
        y = Hh * 0.01
        for share, title, kind in rows:
            y1 = y + share * Hh
            if kind == 'throttle':                                  # the throttle quadrant: twin slots and the scale
                d.rectangle([S * .08, y + 3, S * .92, y1 - 3], fill=(34, 38, 42, 255))
                for x0 in (.36, .52):
                    d.rectangle([S * x0, y + Hh * .02, S * (x0 + .1), y1 - Hh * .02], fill=(8, 8, 8, 255))
                for j in range(6):
                    yy = y + Hh * .025 + (y1 - y - Hh * .05) * j / 5
                    d.line([S * .66, yy, S * .8, yy], fill=(220, 220, 210, 255), width=2)
                for j, lab in enumerate(['ФОРС', 'МАКС', 'МГ', 'СТОП']):
                    H.text(d, (S * .2, y + Hh * .03 + (y1 - y - Hh * .06) * j / 3), lab, S * .09)
                y = y1
                continue
            d.rectangle([S * .07, y + 3, S * .93, y1 - 3], fill=(22, 24, 27, 255), outline=(64, 70, 76, 255), width=2)
            for sx in (.12, .88):
                for sy in (y + 9, y1 - 9):
                    d.ellipse([S * sx - 3, sy - 3, S * sx + 3, sy + 3], fill=(84, 88, 92, 255))
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
                    d.line([x, cy, x + r * .8 * math.sin(.7 * (j + 1)), cy - r * .8 * math.cos(.7 * (j + 1))],
                           fill=(235, 235, 225, 255), width=3)
            elif kind == 'freq':                                    # the LCD channel window and its two knobs
                d.rectangle([S * .18, top + Hh * .004, S * .82, top + Hh * .022], fill=(10, 30, 16, 255), outline=(90, 90, 90, 255))
                H.text(d, (S * .5, top + Hh * .013), 'К 12', Hh * .014, fill=(110, 220, 120))
                for x in (.3, .7):
                    d.ellipse([S * x - S * .09, cy + Hh * .012 - S * .09, S * x + S * .09, cy + Hh * .012 + S * .09],
                              fill=(14, 14, 15, 255), outline=(120, 120, 120, 255), width=2)
            elif kind == 'keys':                                    # square push keys with legends
                for r_ in range(2):
                    for j in range(3):
                        x0 = S * (0.16 + 0.24 * j); yy = top + (y1 - top) * (0.08 + 0.46 * r_)
                        d.rectangle([x0, yy, x0 + S * .2, yy + (y1 - top) * .36], fill=(58, 60, 62, 255),
                                    outline=(12, 12, 12, 255), width=2)
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
                        H.lamp(d, [x0, yy, x0 + S * .2, yy + (y1 - top) * .33], (210, 190, 120) if j else (190, 60, 50), None)
            y = y1
        if mirror:
            im.paste(im.transpose(Image.FLIP_LEFT_RIGHT))
    return paint


def p_su35_wall(side):
    """a cockpit side wall: the panel seams and rivet rows; left the emergency gear and brake handles, right the
    breaker panel and the oxygen hose port"""
    def paint(d, im):
        S, Hh = im.width, im.height
        d.rectangle([0, 0, S, Hh], fill=TURQ_D + (255,))
        for x in (S * .3, S * .62):
            d.line([x, 0, x, Hh], fill=(50, 56, 62, 255), width=3)
        for y in (Hh * .1, Hh * .9):
            for x in range(6, S, 14):
                d.ellipse([x - 2, y - 2, x + 2, y + 2], fill=(96, 104, 112, 255))
        d.rectangle([S * .38, Hh * .3, S * .54, Hh * .66], fill=(22, 24, 27, 255), outline=(76, 80, 86, 255), width=2)
        if side > 0:
            d.rectangle([S * .405, Hh * .4, S * .445, Hh * .6], fill=(190, 160, 30, 255))
            d.rectangle([S * .475, Hh * .4, S * .515, Hh * .6], fill=(170, 30, 26, 255))
            H.text(d, (S * .46, Hh * .35), 'АВАР. ВЫПУСК', Hh * .045)
        else:
            for r_ in range(4):
                for j in range(6):
                    x, y = S * (0.38 + 0.04 * j), Hh * (0.32 + 0.11 * r_)
                    d.ellipse([x - 3, y - 3, x + 3, y + 3], fill=(6, 6, 6, 255), outline=(140, 140, 130, 255))
            H.text(d, (S * .47, Hh * .27), 'АЗС', Hh * .07)
            d.ellipse([S * .12, Hh * .35, S * .2, Hh * .55], fill=(30, 32, 36, 255), outline=(100, 104, 110, 255), width=3)
        d.rectangle([0, Hh * .8, S, Hh * .84], fill=(50, 56, 62, 255))
    return paint


def p_k35_cushion(is_back):
    """the K-36D-3.5's blue-grey cushion: stitched in vertical bolsters; the back with the harness's grey straps"""
    def fn(d, im):
        S, Hh = im.width, im.height
        d.rectangle([0, 0, S, Hh], fill=CUSHION + (255,))
        dark = tuple(c - 18 for c in CUSHION) + (255,)
        for x in (S * .22, S * .78):
            d.line([x, 0, x, Hh], fill=dark, width=4)
        for k in range(1, 5 if is_back else 3):
            y = Hh * k / (5 if is_back else 3)
            d.line([S * .22, y, S * .78, y], fill=tuple(c - 9 for c in CUSHION) + (255,), width=2)
        d.rectangle([2, 2, S - 3, Hh - 3], outline=dark, width=3)
        if is_back:
            for x in (S * .36, S * .64):
                d.rectangle([x - S * .05, 0, x + S * .05, Hh * .7], fill=(96, 100, 104, 255))
            d.rectangle([S * .42, Hh * .62, S * .58, Hh * .74], fill=(160, 162, 160, 255))
            H.text(d, (S * .5, Hh * .9), 'К-36Д-3,5', Hh * .065, fill=(20, 22, 24), bold=True)
    return fn


def p_k35_stripes(d, im):
    S, Hh = im.width, im.height
    d.rectangle([0, 0, S, Hh], fill=(220, 180, 28, 255))
    for k in range(-Hh, S, 14):
        d.polygon([(k, Hh), (k + 7, Hh), (k + 7 + Hh, 0), (k + Hh, 0)], fill=(18, 18, 18, 255))


def p_k35_headpad(d, im):
    S, Hh = im.width, im.height
    d.rectangle([0, 0, S, Hh], fill=(28, 30, 33, 255))
    for k in range(3):
        y0 = Hh * (0.1 + k * 0.28)
        d.rounded_rectangle([S * .1, y0, S * .9, y0 + Hh * .24], radius=S * .06, fill=(44, 46, 50, 255),
                            outline=(16, 16, 18, 255), width=2)


def p_headbox(d, im):
    S, Hh = im.width, im.height
    H.noisy(d, im, HEADPAD, 5, seed=60)
    d.rectangle([S * .1, Hh * .1, S * .9, Hh * .9], outline=(60, 62, 58, 255), width=6)
    H.text(d, (S * .5, Hh * .5), 'К-36Д-3.5', Hh * .2, fill=(40, 40, 40), bold=True)


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
            # the MFI-35: an LCD in a flat bezel with a row of option keys along each edge
            w = spec[2]
            displays.append({'Seat': 0, 'Center': H.r5(c + f.N * H.DISPLAY_LIFT), 'Normal': H.r5(f.N), 'Up': H.r5(f.U),
                             'Width': w, 'Depth': 0.035, 'Kind': spec[1], 'Style': 'LCD'})
            if name == 'ksei':                                      # the KSEI: a small LCD in a plain bezel, four keys under it
                m.frame_rect(c, f.N, f.U, w, w, 0.010, 0.010, sw['black'])
                _, n_, u_, r_ = H.frame(c, f.N, f.U)
                for k in range(4):
                    m.box(c - u_ * (w / 2 + 0.005) + r_ * (k - 1.5) * w / 4.2 + n_ * 0.012, (r_, u_, n_),
                          (0.007, 0.003, 0.003), sw['mgrey'])
                continue
            m.frame_rect(c, f.N, f.U, w, w, 0.022, 0.012, sw['black'])
            _, n_, u_, r_ = H.frame(c, f.N, f.U)
            off = w / 2 + 0.011
            for k in range(5):
                o_ = (k - 2) * w / 5.5
                for q in (c + u_ * off + r_ * o_, c - u_ * off + r_ * o_):
                    m.box(q + n_ * 0.014, (r_, u_, n_), (0.008, 0.005, 0.003), sw['mgrey'])
                for q in (c + r_ * off + u_ * o_, c - r_ * off + u_ * o_):
                    m.box(q + n_ * 0.014, (r_, u_, n_), (0.005, 0.008, 0.003), sw['mgrey'])
        elif kind == 'paint_disc':
            rect = atlas.paint(32, 32, p_disc_red)
            m.disc(c + f.N * 0.008, f.N, f.U, spec[1] / 2, rect, seg=12)
            m.ring(c, f.N, f.U, spec[1] / 2, spec[1] / 2 + 0.004, 0.008, sw['bezel'], seg=12)
        elif kind == 'paint_rect':
            w, h = spec[1], spec[2]
            painter = {'weapons': p_weapons, 'gear': p_gear, 'pui': p_pui, 'engines': p_engines, 'lights': p_lights}[name]
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

    # -- ILS-31 HUD: the body on the coaming, its aft control strip, the lens, the combiner posts and frame (no
    # glass); the picture the game projects (HeadUpDisplay) fills the frame, centred on the pilot's line of sight
    hud_z0, hud_z1 = E[2] + PANEL_Z + 0.02, E[2] + PANEL_Z + 0.30
    hud_y0 = E[1] + TOP_C + 0.025
    hud_y1 = hud_y0 + 0.032                      # a low body: its control strip stays under the sight line
    ctrl = atlas.paint(160, 40, p_hud_ctrl)
    lens = atlas.paint(64, 64, p_hud_lens)
    m.box([E[0], (hud_y0 + hud_y1) / 2, (hud_z0 + hud_z1) / 2], (X, Y, Z), (0.100, (hud_y1 - hud_y0) / 2, (hud_z1 - hud_z0) / 2),
          sw['dgrey'], front=(2, -1, ctrl, -X, Y))
    m.rect_tex([E[0], hud_y1 + 0.001, (hud_z0 + hud_z1) / 2 + 0.03], [0, 1, 0], [0, 0, 1], 0.10, 0.10, lens)
    g_lean = math.radians(22)
    g_up = np.array([0, math.cos(g_lean), -math.sin(g_lean)])
    g_n = H.unit(np.cross(X, g_up))
    g_h, g_hw = 0.220, 0.110                   # a little larger than the real glass, for the game
    g_h = min(g_h, 2 * HUD_TOP[0] - 0.012)        # its top under the windscreen bow
    g_bot = np.array([E[0], E[1] - g_up[1] * g_h / 2, hud_z0 + 0.02])
    g_top = g_bot + g_up * g_h
    stem = g_bot[1] - 0.006 - hud_y1
    if stem > 0.004:                               # two thin posts: the view under the glass stays open
        for s in (-1, 1):
            m.box([E[0] + s * (g_hw + 0.004), hud_y1 + stem / 2, g_bot[2]], (X, Y, Z), (0.004, stem / 2, 0.005), sw['dgrey'])
    m.box(g_bot - g_up * 0.006, (X, g_up, g_n), (g_hw + 0.008, 0.006, 0.012), sw['dgrey'])
    for s in (-1, 1):
        m.box((g_bot + g_top) / 2 + X * s * (g_hw + 0.004), (X, g_up, g_n), (0.004, g_h / 2, 0.005), sw['dgrey'])
    m.box(g_top + g_up * 0.004, (X, g_up, g_n), (g_hw + 0.008, 0.004, 0.005), sw['dgrey'])
    hud = dict(centre=(g_bot + g_top) / 2, normal=-g_n, up=g_up, width=2 * g_hw, height=g_h)
    # the HUD's control unit on the centre facet's top under the HUD (the Su-27 reference views), black, knobs aft
    face = atlas.paint(320, 76, p_iksh_face)
    H.facet_box(m, centre, 0.0, E[1] - 0.152, 0.235, 0.055, 0.04, sw['black'], front=face)
    # the IKSh-1M's housing under its body, down into the coaming (from outside one block, not a slab over the nose)
    hb = E[1] - 0.25
    m.box([E[0], (hb + hud_y0 - 0.002) / 2, (hud_z0 + 0.03 + hud_z1) / 2], (X, Y, Z),
          (0.098, (hud_y0 - 0.002 - hb) / 2, (hud_z1 - hud_z0 - 0.03) / 2), sw['black'])

    # -- side walls, consoles, the closing faces under the panel
    for side in (1, -1):                                            # +1 = left (x > 0)
        xw = E[0] + WALL_X * side
        wall = atlas.paint(200, 110, p_su35_wall(side))
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
        con = atlas.paint(72, 400, p_su35_console(CONSOLE_L if side > 0 else CONSOLE_R, side > 0))

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
    m.flat([P(-fw, FLOOR_Y + 0.012, BACK_Z), P(fw, FLOOR_Y + 0.012, BACK_Z),
            P(fw, FLOOR_Y + 0.012, PANEL_Z + 0.30), P(-fw, FLOOR_Y + 0.012, PANEL_Z + 0.30)], sw['floor'],
           normal=[0, 1, 0])
    # rudder pedals under the panel
    for s in (1, -1):
        m.box(P(s * 0.12, -0.81, 0.60), (X, H.unit([0, 1, -0.35]), H.unit([0, 0.35, 1])), (0.045, 0.07, 0.01), sw['mgrey'])
        m.box(P(s * 0.12, -0.875, 0.64), (X, Y, Z), (0.012, 0.03, 0.06), sw['dgrey'])

    # -- throttle (RUD) on the left console
    tz = 0.20
    m.box(P(0.355, CONSOLE_Y + 0.055, tz), (X, H.unit([0, 1, 0.25]), H.unit([0, -0.25, 1])), (0.009, 0.06, 0.009), sw['metal'])
    grip_ax = (X, H.unit([0, 1, 0.3]), H.unit([0, -0.3, 1]))
    m.box(P(0.355, CONSOLE_Y + 0.135, tz + 0.02), grip_ax, (0.03, 0.045, 0.04), sw['black'])
    m.box(P(0.355, CONSOLE_Y + 0.185, tz + 0.03), grip_ax, (0.026, 0.01, 0.032), sw['dgrey'])
    m.box(P(0.332, CONSOLE_Y + 0.15, tz + 0.045), grip_ax, (0.004, 0.008, 0.008), sw['red'])

    # -- K-36D-3.5 ejection seat, raised to this model's floor
    PS = lambda x, y, z: P(x, y + SEAT_DY, z)   # noqa: E731 (the MiG-29 seat's dimensions: the floor sits at the same depth under the eye)
    # (the K-36D-3.5: blue-grey, bolstered cushions, a headbox with side wings and a three-pad headrest, the striped
    # canopy breakers on its top, the red double loop between the knees on a striped root)
    pan = atlas.paint(72, 72, p_k35_cushion(False))
    back = atlas.paint(72, 108, p_k35_cushion(True))
    headpad = atlas.paint(56, 64, p_k35_headpad)
    stripes = atlas.paint(64, 12, p_k35_stripes)
    ax_b = (X, H.unit([0, 1, -0.14]), H.unit([0, 0.14, 1]))
    m.box(PS(0, -0.915, -0.004), (X, Y, Z), (0.196, 0.025, 0.22), sw['frame'])          # base on the higher floor
    m.box(PS(0, -0.887, 0.026), (X, Y, Z), (0.19, 0.035, 0.21), sw['cushion'], front=(1, 1, pan, -X, Z))
    for s in (1, -1):
        m.box(PS(s * 0.212, -0.882, -0.014), (X, Y, Z), (0.012, 0.15, 0.24), sw['frame'])
        m.box(PS(s * 0.17, -0.44, -0.404), ax_b, (0.02, 0.54, 0.025), sw['frame'])   # guide beams, their tops under the glass
        m.box(PS(s * 0.192, -0.82, 0.20), (X, Y, Z), (0.006, 0.06, 0.04), sw['black'])    # the leg-restraint guides
        m.box(PS(s * 0.188, -0.03, -0.27), (X, Y, Z), (0.025, 0.12, 0.05), sw['frame'])   # the headbox's side wings
    m.box(PS(0, -0.502, -0.234), ax_b, (0.19, 0.33, 0.03), sw['cushion'], front=(2, 1, back, X, ax_b[1]))
    m.box(PS(0, -0.502, -0.294), ax_b, (0.205, 0.36, 0.035), sw['frame'])
    m.box(PS(0, -0.03, -0.314), (X, Y, Z), (0.16, 0.15, 0.10), sw['frame'])   # headbox, under the glass
    m.box(PS(0, -0.02, -0.199), (X, Y, Z), (0.11, 0.09, 0.015), sw['black'], front=(2, 1, headpad, X, Y))
    m.box(PS(0, 0.122, -0.300), (X, Y, Z), (0.15, 0.004, 0.075), sw['black'], front=(1, 1, stripes, X, -Z))
    m.box(PS(0, -0.835, 0.10), (X, Y, Z), (0.17, 0.006, 0.02), sw['mgrey'])                # the lap belt and buckle
    m.box(PS(0, -0.828, 0.10), (X, Y, Z), (0.03, 0.008, 0.026), sw['metal'])
    red = atlas.swatch((178, 34, 30))                                 # the ejection handle: a red double loop
    for s in (1, -1):
        for xl in (0.02, 0.09):
            m.box(PS(s * xl, -0.818, 0.256), (X, Y, Z), (0.007, 0.034, 0.007), red)
        m.box(PS(s * 0.055, -0.780, 0.256), (X, Y, Z), (0.042, 0.007, 0.007), red)
    m.box(PS(0, -0.852, 0.256), (X, Y, Z), (0.032, 0.01, 0.01), sw['black'], front=(2, 1, stripes, X, Y))

    # -- control stick (RUS): the boot stays with the cockpit, the rest goes into the stick bone
    sz = 0.40
    m.box(P(0, -0.875, sz), (X, Y, Z), (0.045, 0.035, 0.045), sw['dgrey'])
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
    foot = P(0, -0.85, sz)
    return m, (stick, foot), gauges, displays, hud


def old_interior(E):
    """boxes (vehicle-local lo, hi) of the old cockpit: the tub inside the walls above the floor from behind the
    seat to the old panel, the old panel and coaming over the nose deck, the old HUD frame, the old pedals (the
    windscreen arch stays)"""
    e = np.array(E)
    return [(e + [-0.44, -0.90, BACK_Z], e + [0.44, SILL_Y - 0.005, 0.66]),
            (e + [-0.435, -0.62, 0.40], e + [0.435, 0.00, 1.15]),
            (e + [-0.13, -0.13, 0.50], e + [0.13, 0.07, 0.72]),
            (e + [-0.30, -0.905, 0.30], e + [0.30, -0.62, 1.00])]


SILL_Z = [-1.00, -0.80, -0.60, -0.40, -0.20, 0.00, 0.20, 0.40, 0.60, 0.80, 1.00]     # the canopy's sill (canopy_su_35.SECTIONS)
SILL_X = [0.338, 0.444, 0.465, 0.491, 0.53, 0.526, 0.502, 0.487, 0.47, 0.448, 0.424]
SILL_YS = [-0.04, -0.15, -0.194, -0.225, -0.24, -0.249, -0.257, -0.27, -0.29, -0.325, -0.374]


def repaint_tub(ac, atlas, E):
    """the model's old turquoise tub left round the new cockpit, and its light skin inside the canopy's sill line
    (the rim beside the panel that stood up as white spikes, the spine under the canopy behind the seat): repainted
    in the dark blue-grey of the cockpit's deck instead of removed (they close the fuselage)"""
    tex = Image.open(L.texture_path(VID)).convert('RGB')
    TW, TH = tex.size
    deck, floor = atlas.swatch(DECK), atlas.swatch(FLOOR)
    lo, hi = np.array([-0.75, -1.30, -1.40]), np.array([0.75, 0.45, 1.00])
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
            sx, sy = np.interp(c[2], SILL_Z, SILL_X), np.interp(c[2], SILL_Z, SILL_YS)
            inside = -0.98 < c[2] < 0.85 and abs(c[0]) < sx - 0.01 and c[1] < sy + 0.02 and r + g + bl > 200
            if (r < 70 and g > 110 and bl > 120) or inside:
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
    atlas = H.A.Atlas(VID)
    removed['faces repainted'] = repaint_tub(ac, atlas, E)
    mesh, stick, gauges, displays, hud = build(atlas, E)
    pm = H.install(ac, mesh, stick, SOURCE_BONE, STICK_BONE, gauges, displays, hud, E)
    print(f'{VID}: removed {removed}; new cockpit {len(pm["polys"])} faces, {len(gauges)} gauges')
    if not dry:
        H.write(ac, atlas)


if __name__ == '__main__':
    main(sys.argv[1:])
