#!/usr/bin/env python3
"""Shenyang J-15D cockpits (both seats), built by hand for this model (as su_30.py: the J-15D is the two-seat J-15,
its tandem cockpits the Su-30 family's), from the real layouts.

Measured off the model with sections.py and glass_sections.py (1 block = 1 m on this model):
  * Front seat (eye at (0, 3.4675, 5.8262)): the glass meets the fuselage at y -0.33..-0.37, x +-0.45..0.50; the sides
    slope out to x +-0.68 at y -0.72; the consoles at y -0.72; the floor at y -1.18; the old panel face 0.44 m ahead,
    its top at y -0.25; the windscreen narrowing to the nose 1.05 m ahead.
  * Rear seat (eye 1.139 m behind and 0.19 m above the front eye): the glass comes down to y -0.40 at x +-0.30, the
    sides sloping out under it; the consoles at y -0.96; the floor at y -1.21; the front seat's back 0.55 m ahead.
Built, for each seat: a three-facet main panel, the black coaming, side consoles with switch panels and linings over
the sloping sides, rudder pedals, the throttle, a K-36DM seat and a stick; English placards (the game's fonts have no
Chinese). The panels are the J-15D's own glass cockpits (the J-16 / J-15S family; references are thin: the J-15D's
described as a wide-angle narrow-frame holographic HUD in front and a single-piece panoramic colour LCD in the rear):
  * Front: the wide-angle HUD with its up-front control panel under it; a large centre MFD flanked by two MFDs, the
    standby instruments and the warning panel under them, the key and EW panels and the gear lever low on the wings.
    The stick goes into the stick bone.
  * Rear (the EW officer's): the up-front control panel, the panoramic display (two live windows in one bezel), the
    standby set, the warning panel and the jammer control panel; a fixed stick.
Instruments the game draws (CockpitGauges, FlightDisplays, HeadUpDisplay) are live for both seats. No glass.

Run on the committed originals (git checkout be357476 -- the j_15d geo, asset and both textures first).
Usage: python3 tools/cockpit_models/j_15d.py [--dry-run]
"""
import math, os, sys
import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import cockpit_lib as L   # noqa: E402
import handbuilt as H     # noqa: E402

VID = 'j_15d'
SOURCE_BONE = 'wreck_fuselage_0__hull'
STICK_BONE = 'wreck_fuselage_0__control_stick_pitch'

TURQ = (78, 92, 96)            # the J-15's blue-grey panel finish
TURQ_D = (56, 66, 70)
DECK = (48, 58, 56)            # the model's old turquoise tub and its rim skin inside the canopy, repainted
BLACK = (24, 25, 26)
DGREY = (44, 46, 48)
MGREY = (88, 92, 94)
FRAME = (56, 62, 58)           # K-36 seat structure
CUSHION = (72, 70, 58)         # the K-36DM's olive-brown cushions
HEADPAD = (40, 42, 40)
YELLOW = (226, 186, 30)
RED = (196, 36, 30)
METAL = (128, 132, 134)
FLOOR = (46, 50, 50)
WALLC = (84, 96, 100)          # blue-grey linings over the sloping sides
WALLC_D = (62, 72, 76)

# ---------------------------------------------------------------- the measured cockpit (metres from the eye)
# one entry per crew station; use() makes a station's measurements the module's working constants for build()
STATIONS = {
    'front': dict(SEAT=0, WALL_X=0.430, CONSOLE_OUT=0.660, SILL_Y=-0.37, FLOOR_Y=-1.18, BACK_Z=-0.47, CONSOLE_Y=-0.74,
                  CONSOLE_IN=0.36, PANEL_Z=0.47, LEAN=math.radians(12), HINGE=0.20, WING=math.radians(25),
                  TOP_C=-0.240, TOP_W=-0.480, BOT_C=-0.720, BOT_W=-0.740, FRONT_Z=0.76, FRONT_Y_C=-0.320,
                  FRONT_Y_W=-0.480, FRONT_X=0.290, SEAT_DY=0.0, HAS_HUD=True, LIN=((0.45, -0.38), (0.67, -0.73))),
    'rear': dict(SEAT=1, WALL_X=0.300, CONSOLE_OUT=0.685, SILL_Y=-0.42, FLOOR_Y=-1.21, BACK_Z=-0.55, CONSOLE_Y=-0.94,
                 CONSOLE_IN=0.25, PANEL_Z=0.55, LEAN=math.radians(12), HINGE=0.19, WING=math.radians(25),
                 TOP_C=-0.300, TOP_W=-0.440, BOT_C=-0.900, BOT_W=-0.940, FRONT_Z=0.62, FRONT_Y_C=-0.280,
                 FRONT_Y_W=-0.420, FRONT_X=0.260, SEAT_DY=-0.05, HAS_HUD=False, LIN=((0.31, -0.43), (0.62, -0.80))),
}


HUD_TOP = [1.0]                # how far above the eye the view under the bow reaches at the glass (main sets it)


def use(name):
    g = globals()
    g.update(STATIONS[name])
    g['STATION'] = name
    g['WING_LEN'] = (g['WALL_X'] - g['HINGE']) / math.cos(g['WING'])
    g['LAYOUT'] = LAYOUT_FRONT if name == 'front' else LAYOUT_REAR
    g['R_C'] = np.array([-1.0, 0, 0])
    g['U_C'] = np.array([0, math.cos(LEAN), math.sin(LEAN)])
    g['N_C'] = np.array([0, math.sin(LEAN), -math.cos(LEAN)])




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


# Front seat: the J-16 / J-15D layout. facet: 0 centre (a: metres to the pilot's right of the centre line), +1 right
# wing, -1 left wing (a: metres outward from the hinge). y: from the eye.
LAYOUT_FRONT = [
    # centre facet (the up-front control panel stands on its top, under the HUD: make_hud)
    ('caution',  0, -0.165, -0.315, ('paint_disc', 0.024), 'master caution lamp'),
    ('mfd_c',    0,  0.000, -0.430, ('display', 'RADAR', 0.190), 'centre MFD'),
    ('sb_ias',   0, -0.130, -0.620, ('game', 'AIRSPEED', 0.050), 'standby airspeed'),
    ('sb_adi',   0, -0.045, -0.620, ('game', 'ATTITUDE', 0.058), 'standby attitude'),
    ('sb_alt',   0,  0.045, -0.620, ('game', 'ALTIMETER', 0.050), 'standby altimeter'),
    ('clock',    0,  0.135, -0.620, ('game', 'CLOCK', 0.040), 'clock'),
    ('ekran',    0,  0.000, -0.690, ('annunciator', 0.200, 0.040), 'warning panel'),
    # left wing
    ('mfd_l',   -1,  0.125, -0.470, ('display', 'STORES', 0.150), 'left MFD'),
    ('keys',    -1,  0.125, -0.615, ('paint_rect', 0.150, 0.040), 'option key panel'),
    ('gear',    -1,  0.190, -0.690, ('paint_rect', 0.040, 0.060), 'gear control panel'),
    # right wing
    ('mfd_r',    1,  0.125, -0.470, ('display', 'RADAR', 0.150), 'right MFD'),
    ('ew',       1,  0.125, -0.615, ('paint_rect', 0.150, 0.040), 'countermeasures panel'),
    ('fuel',     1,  0.125, -0.690, ('game', 'FUEL_QTY', 0.045), 'fuel quantity'),
]
# Rear seat (the EW officer's): the up-front control panel, the panoramic display, the standby set, the jammer panel
LAYOUT_REAR = [
    ('caution',  0, -0.160, -0.350, ('paint_disc', 0.022), 'master caution lamp'),
    ('ufcp_r',   0,  0.010, -0.355, ('paint_rect', 0.240, 0.060), 'up-front control panel'),
    ('pano',     0,  0.000, -0.520, ('panorama', 0.360, 0.190), 'single-piece panoramic colour display'),
    ('clock',    0, -0.140, -0.690, ('game', 'CLOCK', 0.040), 'clock'),
    ('sb_adi',   0, -0.055, -0.690, ('game', 'ATTITUDE', 0.055), 'standby attitude'),
    ('sb_alt',   0,  0.030, -0.690, ('game', 'ALTIMETER', 0.045), 'standby altimeter'),
    ('sb_ias',   0,  0.110, -0.690, ('game', 'AIRSPEED', 0.045), 'standby airspeed'),
    ('ekran',    0,  0.000, -0.770, ('annunciator', 0.240, 0.045), 'warning panel'),
    ('ew_r',     0,  0.000, -0.850, ('paint_rect', 0.300, 0.050), 'jammer control panel'),
    ('keys_r',  -1,  0.060, -0.560, ('paint_rect', 0.080, 0.100), 'option key panel'),
    ('fuel',     1,  0.060, -0.540, ('game', 'FUEL_QTY', 0.045), 'fuel quantity'),
    ('rpm',      1,  0.060, -0.640, ('game', 'ENGINE_RPM', 0.045), 'twin tachometer'),
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
                w, h = (spec[2], spec[2]) if spec[0] == 'display' else (spec[1], spec[2])   # (panorama: w x h)
                sx, sy = (w / 2 + 0.006) * px_m, (h / 2 + 0.006) * px_m
                d.rectangle([x - sx, yy - sy, x + sx, yy + sy], fill=(20, 26, 26, 255))
        if side == 0:
            for k, lab in enumerate(['BRAKE', 'EMER JETT']):
                x, yy = to_px(-0.16 + k * 0.32, BOT_C + 0.02)
                d.rectangle([x - 70, yy - 14, x + 70, yy + 14], fill=(18, 18, 18, 255))
                H.text(d, (x, yy), lab, 18)
    return fn


def p_weapons(d, im):
    S, Hh = im.width, im.height
    d.rectangle([0, 0, S, Hh], fill=(20, 22, 22, 255))
    labs = ['GUN', 'MISSILE', 'ROCKETS', 'JETT']                   # the master arm block: a red guard over jettison
    for k, lab in enumerate(labs):
        x = S * (0.13 + k * 0.245)
        H.lamp(d, [x - 14, Hh * .1, x + 14, Hh * .3], (210, 210, 200), None, lit=(k == 0))
        d.rectangle([x - 4, Hh * .4, x + 4, Hh * .66], fill=(190, 190, 190, 255))
        if k == 3:
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


def p_keypanel(labels, rows=1):
    """a black panel of square push keys with legends (the MFD option keys, the countermeasures and jammer panels)"""
    def fn(d, im):
        S, Hh = im.width, im.height
        d.rectangle([0, 0, S, Hh], fill=(20, 22, 22, 255))
        per = (len(labels) + rows - 1) // rows
        w, hh = S / per, Hh / rows
        for k, lab in enumerate(labels):
            r, c_ = divmod(k, per)
            x0, y0 = c_ * w, r * hh
            H.lamp(d, [x0 + 5, y0 + hh * .12, x0 + w - 5, y0 + hh * .62], (200, 200, 190) if '*' not in lab else (90, 200, 90),
                   None, lit=('*' in lab))
            H.text(d, (x0 + w / 2, y0 + hh * .82), lab.strip('*'), min(hh * .2, w * .2))
    return fn


def p_ufcp(d, im):
    """the up-front control panel: its two-line LCD window over a keypad, the mode keys either side"""
    S, Hh = im.width, im.height
    d.rectangle([0, 0, S, Hh], fill=(20, 22, 22, 255))
    d.rectangle([S * .28, Hh * .08, S * .72, Hh * .38], fill=(14, 40, 26, 255), outline=(70, 72, 74, 255), width=3)
    H.text(d, (S * .5, Hh * .23), 'NAV  000', Hh * .14, fill=(110, 230, 140))
    for r in range(2):
        for c_ in range(5):
            x, y = S * (0.31 + c_ * 0.095), Hh * (0.5 + r * 0.24)
            d.rectangle([x - S * .035, y - Hh * .08, x + S * .035, y + Hh * .08], fill=(46, 48, 50, 255))
            H.text(d, (x, y), str((r * 5 + c_ + 1) % 10), Hh * .1)
    for k, lab in enumerate(['A/A', 'A/G', 'NAV', 'EW']):
        for x in (S * .12, S * .88):
            y = Hh * (0.18 + k * 0.21)
            H.lamp(d, [x - S * .08, y - Hh * .07, x + S * .08, y + Hh * .07], (200, 200, 190), None, lit=False)
        H.text(d, (S * .12, Hh * (0.18 + k * 0.21)), lab, Hh * .09)
        H.text(d, (S * .88, Hh * (0.18 + k * 0.21)), ['TCN', 'ILS', 'IFF', 'DATA'][k], Hh * .09)


def p_disc_red(d, im):
    S = im.width
    d.ellipse([0, 0, S - 1, S - 1], fill=(120, 18, 14, 255))
    d.ellipse([S * .18, S * .14, S * .55, S * .4], fill=(220, 90, 80, 255))


def make_hud(m, atlas, sw, E, X, Y, Z):
    """the wide-angle holographic HUD on the front coaming; returns the HeadUpDisplay picture (the frame's face)"""
    hud_z0, hud_z1 = E[2] + PANEL_Z + 0.02, E[2] + PANEL_Z + 0.18     # (short: the windscreen comes down close ahead)
    hud_y0 = E[1] + TOP_C + 0.025
    hud_y1 = hud_y0 + 0.032                      # a low body: its control strip stays under the sight line
    ctrl = atlas.paint(160, 40, p_hud_ctrl)
    lens = atlas.paint(64, 64, p_hud_lens)
    m.box([E[0], (hud_y0 + hud_y1) / 2, (hud_z0 + hud_z1) / 2], (X, Y, Z), (0.085, (hud_y1 - hud_y0) / 2, (hud_z1 - hud_z0) / 2),
          sw['dgrey'], front=(2, -1, ctrl, -X, Y))
    m.rect_tex([E[0], hud_y1 + 0.001, (hud_z0 + hud_z1) / 2 + 0.03], [0, 1, 0], [0, 0, 1], 0.10, 0.10, lens)
    g_lean = math.radians(22)
    g_up = np.array([0, math.cos(g_lean), -math.sin(g_lean)])
    g_n = H.unit(np.cross(X, g_up))
    # this model's windscreen comes down low and close: the combiner stands 3 cm behind the panel's face, over its
    # control unit, where the glass still clears its top (0.09 m over the eye line there)
    g_h, g_hw = 0.170, 0.110                   # wide-angle: a wide glass
    g_h = min(g_h, 2 * HUD_TOP[0] - 0.012)        # its top under the windscreen bow
    g_bot = np.array([E[0], E[1] - g_up[1] * g_h / 2, E[2] + PANEL_Z - 0.03])
    g_c, g_h = H.fit_sight_under(VID, E, g_bot + g_up * g_h / 2, g_up, g_h, g_hw)   # (its top corners under the glass)
    g_bot = g_c - g_up * g_h / 2
    g_top = g_bot + g_up * g_h
    foot = E[1] + TOP_C - 0.004                    # (the control unit's top)
    stem = g_bot[1] - 0.006 - foot
    if stem > 0.004:                               # two thin posts: the view under the glass stays open
        for s in (-1, 1):
            m.box([E[0] + s * (g_hw + 0.004), foot + stem / 2, g_bot[2]], (X, Y, Z), (0.004, stem / 2, 0.005), sw['dgrey'])
    # the holographic HUD's one wide glass in a narrow frame: rounded top corners, held thinly at its foot and sides
    H.combiner(m, g_bot, g_up, -g_n, 2 * g_hw, g_h, 'rounded', 0.025, ('bottom', 'side'), 0.005, sw['dgrey'])
    # the housing under the body, down into the coaming (from outside one block, not a slab over the nose)
    hb = E[1] + TOP_C - 0.10
    m.box([E[0], (hb + hud_y0 - 0.002) / 2, (hud_z0 + 0.03 + hud_z1) / 2], (X, Y, Z),
          (0.083, (hud_y0 - 0.002 - hb) / 2, (hud_z1 - hud_z0 - 0.03) / 2), sw['black'])
    return dict(centre=(g_bot + g_top) / 2, normal=-g_n, up=g_up, width=2 * g_hw, height=g_h)


def p_hud_ctrl(d, im):
    """the ILS-31 body's aft edge: its vent slots"""
    S, Hh = im.width, im.height
    d.rectangle([0, 0, S, Hh], fill=(28, 29, 30, 255))
    for k in range(10):
        x = S * (0.08 + k * 0.088)
        d.rectangle([x, Hh * .3, x + S * .045, Hh * .7], fill=(10, 10, 11, 255))


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


def p_ils_face(d, im):
    """the Su-30's ILS-31 control unit, its face toward the pilot: the brightness knob, the day / night and filter
    toggles, the ready lamp and the test button"""
    S, Hh = im.width, im.height
    d.rectangle([0, 0, S, Hh], fill=(22, 23, 24, 255))
    d.rectangle([S * .03, Hh * .08, S * .97, Hh * .92], fill=(30, 32, 33, 255), outline=(54, 56, 58, 255), width=3)
    kx, ky, kr = S * .22, Hh * .46, Hh * .25
    for k in range(7):
        a = math.radians(-120 + k * 40)
        d.line([kx + kr * 1.2 * math.sin(a), ky - kr * 1.2 * math.cos(a), kx + kr * 1.45 * math.sin(a),
                ky - kr * 1.45 * math.cos(a)], fill=(220, 220, 210, 255), width=3)
    d.ellipse([kx - kr, ky - kr, kx + kr, ky + kr], fill=(14, 14, 15, 255), outline=(96, 98, 100, 255), width=4)
    d.line([kx, ky, kx - kr * .6, ky - kr * .7], fill=(230, 230, 220, 255), width=5)
    for k, lab in enumerate(['DAY/NT', 'FILTER']):
        x = S * (0.45 + k * 0.16)
        d.ellipse([x - 8, Hh * .44 - 8, x + 8, Hh * .44 + 8], fill=(110, 112, 110, 255))
        d.rectangle([x - 3, Hh * .2, x + 3, Hh * .44], fill=(205, 205, 200, 255))
        H.text(d, (x, Hh * .7), lab, Hh * .12)
    H.lamp(d, [S * .78, Hh * .2, S * .92, Hh * .5], (60, 170, 70), None, lit=True)
    d.ellipse([S * .81, Hh * .6, S * .89, Hh * .86], fill=(150, 30, 26, 255), outline=(20, 20, 20, 255), width=3)
    H.text(d, (S * .22, Hh * .88), 'HUD', Hh * .12, bold=True)


# the side consoles, front to back: (share of the length, title, kind); the rear seat has the instructor's set
CONSOLES = {
    'front': ([(0.10, 'START', 'guarded'), (0.08, 'VENT', 'rotary'), (0.26, 'THROTTLE', 'throttle'), (0.13, 'VHF', 'freq'),
               (0.10, 'ADF', 'freq'), (0.11, 'LAND LT', 'toggles'), (0.10, 'FUEL', 'toggles'), (0.10, 'OXYGEN', 'rotary')],
              [(0.12, 'NAVIGATION', 'rotary'), (0.10, 'WEAPONS', 'guarded'), (0.11, 'IFF', 'freq'),
               (0.15, 'POWER', 'toggles'), (0.13, 'CB', 'breakers'), (0.11, 'LIGHTS', 'rotary'), (0.10, 'RADAR', 'lamps'),
               (0.16, 'CANOPY', 'guarded')]),
    'rear': ([(0.10, 'FAIL INPUT', 'lamps'), (0.26, 'THROTTLE', 'throttle'), (0.13, 'ICS', 'rotary'), (0.12, 'LIGHTS', 'toggles'),
              (0.12, 'FUEL', 'toggles'), (0.12, 'OXYGEN', 'rotary'), (0.13, 'INSTR', 'guarded')],
             [(0.12, 'SIM FAIL', 'lamps'), (0.12, 'NAVIGATION', 'rotary'), (0.15, 'POWER', 'toggles'),
              (0.15, 'CB', 'breakers'), (0.12, 'AIR COND', 'rotary'), (0.16, 'CANOPY', 'guarded'), (0.10, 'ICS', 'freq')]),
}


def p_su30_console(rows, mirror, finish):
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


def p_su30_lining(side, seat):
    """the lining over a sloping fuselage side: seams and rivets, and a small panel (left: the canopy and emergency
    handles; right: a breaker strip); the rear seat's a plain one with its oxygen port"""
    def paint(d, im):
        S, Hh = im.width, im.height
        d.rectangle([0, 0, S, Hh], fill=WALLC_D + (255,))
        for x in (S * .33, S * .66):
            d.line([x, 0, x, Hh], fill=(48, 60, 55, 255), width=3)
        for y in (Hh * .12, Hh * .88):
            for x in range(6, S, 14):
                d.ellipse([x - 2, y - 2, x + 2, y + 2], fill=(96, 112, 104, 255))
        if seat == 0 and side > 0:
            d.rectangle([S * .4, Hh * .3, S * .58, Hh * .7], fill=(22, 24, 24, 255))
            d.rectangle([S * .43, Hh * .38, S * .48, Hh * .62], fill=(190, 160, 30, 255))
            d.rectangle([S * .5, Hh * .38, S * .55, Hh * .62], fill=(170, 30, 26, 255))
        elif seat == 0:
            d.rectangle([S * .38, Hh * .28, S * .62, Hh * .72], fill=(22, 24, 24, 255))
            for r_ in range(3):
                for j in range(6):
                    x, y = S * (0.41 + 0.036 * j), Hh * (0.38 + 0.12 * r_)
                    d.ellipse([x - 3, y - 3, x + 3, y + 3], fill=(6, 6, 6, 255), outline=(140, 140, 130, 255))
        else:
            d.ellipse([S * .45, Hh * .35, S * .55, Hh * .62], fill=(30, 32, 30, 255), outline=(100, 100, 96, 255), width=3)
    return paint


def p_k36_cushion(is_back):
    """the K-36DM's olive-brown cushion: quilted in stitched panels; the back with the harness and its stencil"""
    def fn(d, im):
        S, Hh = im.width, im.height
        d.rectangle([0, 0, S, Hh], fill=CUSHION + (255,))
        dark = tuple(c - 20 for c in CUSHION) + (255,)
        for k in range(1, 4):
            d.line([S * k / 4, 0, S * k / 4, Hh], fill=dark, width=3)
        for k in range(1, 6 if is_back else 4):
            y = Hh * k / (6 if is_back else 4)
            d.line([0, y, S, y], fill=tuple(c - 10 for c in CUSHION) + (255,), width=2)
        d.rectangle([2, 2, S - 3, Hh - 3], outline=dark, width=3)
        if is_back:
            for x in (S * .32, S * .68):
                d.rectangle([x - S * .05, 0, x + S * .05, Hh * .72], fill=(86, 90, 72, 255))
            d.rectangle([S * .42, Hh * .62, S * .58, Hh * .74], fill=(150, 150, 146, 255))
            H.text(d, (S * .5, Hh * .9), 'K-36DM', Hh * .07, fill=(30, 30, 26), bold=True)
    return fn


def p_k36_headpad(d, im):
    S, Hh = im.width, im.height
    d.rectangle([0, 0, S, Hh], fill=(30, 31, 30, 255))
    d.rounded_rectangle([S * .08, Hh * .1, S * .92, Hh * .9], radius=S * .1, fill=(46, 47, 45, 255),
                        outline=(18, 18, 18, 255), width=3)


def p_k36_stripes(d, im):
    S, Hh = im.width, im.height
    d.rectangle([0, 0, S, Hh], fill=(220, 180, 28, 255))
    for k in range(-Hh, S, 16):
        d.polygon([(k, Hh), (k + 8, Hh), (k + 8 + Hh, 0), (k + Hh, 0)], fill=(18, 18, 18, 255))


def p_headbox(d, im):
    S, Hh = im.width, im.height
    H.noisy(d, im, HEADPAD, 5, seed=60)
    d.rectangle([S * .1, Hh * .1, S * .9, Hh * .9], outline=(60, 62, 58, 255), width=6)
    H.text(d, (S * .5, Hh * .5), 'K-36DM', Hh * .2, fill=(40, 40, 40), bold=True)


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
            gauges.append({'Seat': SEAT, 'Kind': spec[1], 'Center': H.r5(c + f.N * H.GAUGE_LIFT), 'Normal': H.r5(f.N),
                           'Up': H.r5(f.U), 'Diameter': spec[2], 'Depth': 0.03})
        elif kind == 'annunciator':
            w, h = spec[1], spec[2]
            gauges.append({'Seat': SEAT, 'Kind': 'ANNUNCIATOR', 'Center': H.r5(c + f.N * 0.004), 'Normal': H.r5(f.N),
                           'Up': H.r5(f.U), 'Diameter': w, 'Width': w, 'Height': h, 'Depth': 0.02, 'Columns': cols,
                           'Lamps': lamps})
            m.frame_rect(c, f.N, f.U, w, h, 0.005, 0.008, sw['bezel'])
        elif kind == 'display':
            # an LCD in a flat bezel with a row of option keys along each edge
            w = spec[2]
            displays.append({'Seat': SEAT, 'Center': H.r5(c + f.N * H.DISPLAY_LIFT), 'Normal': H.r5(f.N), 'Up': H.r5(f.U),
                             'Width': w, 'Depth': 0.035, 'Kind': spec[1], 'Style': 'LCD'})
            m.frame_rect(c, f.N, f.U, w, w, 0.022, 0.012, sw['black'])
            _, n_, u_, r_ = H.frame(c, f.N, f.U)
            off = w / 2 + 0.011
            for k in range(5):
                o_ = (k - 2) * w / 5.5
                for q in (c + u_ * off + r_ * o_, c - u_ * off + r_ * o_):
                    m.box(q + n_ * 0.014, (r_, u_, n_), (0.008, 0.005, 0.003), sw['mgrey'])
                for q in (c + r_ * off + u_ * o_, c - r_ * off + u_ * o_):
                    m.box(q + n_ * 0.014, (r_, u_, n_), (0.005, 0.008, 0.003), sw['mgrey'])
        elif kind == 'panorama':
            # the rear seat's single-piece panoramic LCD: one wide bezel, the picture in two windows side by side
            # (the game draws square displays), the tactical picture left, the stores / EW page right
            w, h = spec[1], spec[2]
            m.frame_rect(c, f.N, f.U, w, h, 0.014, 0.012, sw['black'])
            _, n_, u_, r_ = H.frame(c, f.N, f.U)
            ws = min(h - 0.012, w / 2 - 0.008)
            for k, kd in ((-1, 'RADAR'), (1, 'STORES')):
                cc = c + r_ * (k * (w / 4 - 0.002))
                displays.append({'Seat': SEAT, 'Center': H.r5(cc + f.N * H.DISPLAY_LIFT), 'Normal': H.r5(f.N),
                                 'Up': H.r5(f.U), 'Width': ws, 'Depth': 0.03, 'Kind': kd, 'Style': 'LCD'})
            for k in range(8):                                     # its option keys along the bottom edge
                m.box(c - u_ * (h / 2 + 0.007) + r_ * (k - 3.5) * w / 8.6 + n_ * 0.012, (r_, u_, n_), (0.009, 0.004, 0.003),
                      sw['mgrey'])
        elif kind == 'paint_disc':
            rect = atlas.paint(32, 32, p_disc_red)
            m.disc(c + f.N * 0.008, f.N, f.U, spec[1] / 2, rect, seg=12)
            m.ring(c, f.N, f.U, spec[1] / 2, spec[1] / 2 + 0.004, 0.008, sw['bezel'], seg=12)
        elif kind == 'paint_rect':
            w, h = spec[1], spec[2]
            painter = {'weapons': p_weapons, 'gear': p_gear, 'afcs': p_afcs, 'ufcp_r': p_ufcp,
                       'keys': p_keypanel(['MENU', 'SMS', 'RDR', 'TSD', 'FCS', 'ENG']),
                       'keys_r': p_keypanel(['MENU', 'ESM', 'JAM', 'TSD', 'DATA', 'REC'], rows=3),
                       'ew': p_keypanel(['*ON', 'CHAFF', 'FLARE', 'AUTO', 'JETT']),
                       'ew_r': p_keypanel(['*POD L', '*POD R', 'ESM', 'JAM', 'STBY', 'EMCON'])}[name]
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
    hud = None
    if HAS_HUD:
        hud = make_hud(m, atlas, sw, E, X, Y, Z)
        H.facet_box(m, centre, 0.0, E[1] + TOP_C - 0.030, 0.24, 0.055, 0.04, sw['black'],
                    front=atlas.paint(240, 56, p_ufcp))                  # the up-front control panel under it


    # -- consoles under the sloping sides, and grey-green linings over the slopes (no walls of our own)
    sw['wall_d'] = atlas.swatch(WALLC_D)
    for side in (1, -1):                                            # +1 = left (x > 0)
        xi, xo = E[0] + CONSOLE_IN * side, E[0] + CONSOLE_OUT * side
        cy, fy = E[1] + CONSOLE_Y, E[1] + FLOOR_Y + 0.02
        zb, zf = E[2] + BACK_Z + 0.06, E[2] + PANEL_Z - 0.06
        con = atlas.paint(80, 400, p_su30_console(CONSOLES[STATION][0 if side > 0 else 1], side > 0, WALLC))
        top = [[xi, cy, zb], [xo, cy, zb], [xo, cy, zf], [xi, cy, zf]]
        m.face(top, [m.uv(con, 0, 0), m.uv(con, 1, 0), m.uv(con, 1, 1), m.uv(con, 0, 1)], normal=[0, 1, 0])
        m.flat([[xi, fy, zb], [xi, fy, zf], [xi, cy, zf], [xi, cy, zb]], sw['wall_d'], normal=[-side, 0, 0])
        m.flat([[xi, fy, zb], [xo, fy, zb], [xo, cy, zb], [xi, cy, zb]], sw['wall_d'], normal=[0, 0, -1])
        m.flat([[xi, fy, zf], [xo, fy, zf], [xo, cy, zf], [xi, cy, zf]], sw['wall_d'], normal=[0, 0, 1])
        (lx0, ly0), (lx1, ly1) = LIN
        xs, xc = E[0] + lx0 * side, E[0] + lx1 * side
        ys, yc = E[1] + ly0, E[1] + ly1
        lining = atlas.paint(150, 60, p_su30_lining(side, SEAT))
        q = [[xc, yc, zb - 0.06], [xc, yc, zf], [xs, ys, zf], [xs, ys, zb - 0.06]]
        q = [[v[0] - side * 0.004, v[1] + 0.003, v[2]] for v in q]   # inside the fuselage's own side
        m.quad_tex(q if side > 0 else [q[1], q[0], q[3], q[2]], lining, toward=[E[0], E[1] - 0.3, (zb + zf) / 2])
    # under the centre facet: its lower closing face back to the footwell
    bl, br = centre.at(HINGE, E[1] + BOT_C), centre.at(-HINGE, E[1] + BOT_C)
    m.flat([bl, br, br + np.array([0, 0, 0.12]), bl + np.array([0, 0, 0.12])], sw['turq_d'], normal=[0, -1, 0])
    for s in ((1, -1) if CONSOLE_IN - HINGE > 0.02 else ()):   # the footwell sides under the wings (none
        #                                                         where the console meets the hinge: it would lie on the facet)
        e = centre.at(-HINGE * s, E[1] + BOT_C)
        m.flat([e, centre.at(-HINGE * s, E[1] + BOT_W), [E[0] + s * CONSOLE_IN, E[1] + BOT_W, e[2]],
                [E[0] + s * CONSOLE_IN, E[1] + BOT_C, e[2]]], sw['turq_d'], normal=[0, 0, -1])
    # the floor: a dark plate over the model's own (white) floor, from behind the seat to the pedals
    fw = CONSOLE_IN + 0.04                                          # under the consoles' inner faces too
    m.flat([P(-fw, FLOOR_Y + 0.012, BACK_Z), P(fw, FLOOR_Y + 0.012, BACK_Z),     # (clear of the old floor under it)
            P(fw, FLOOR_Y + 0.012, PANEL_Z + 0.20), P(-fw, FLOOR_Y + 0.012, PANEL_Z + 0.20)], sw['floor'],
           normal=[0, 1, 0])
    # rudder pedals under the panel
    for s in (1, -1):
        m.box(P(s * 0.12, FLOOR_Y + 0.14, PANEL_Z + 0.02), (X, H.unit([0, 1, -0.35]), H.unit([0, 0.35, 1])), (0.045, 0.07, 0.01), sw['mgrey'])
        m.box(P(s * 0.12, FLOOR_Y + 0.035, PANEL_Z + 0.06), (X, Y, Z), (0.012, 0.03, 0.06), sw['dgrey'])

    # -- throttle (RUD) on the left console
    tz = min(0.20, PANEL_Z - 0.25)
    m.box(P(CONSOLE_IN + 0.075, CONSOLE_Y + 0.055, tz), (X, H.unit([0, 1, 0.25]), H.unit([0, -0.25, 1])), (0.009, 0.06, 0.009), sw['metal'])
    grip_ax = (X, H.unit([0, 1, 0.3]), H.unit([0, -0.3, 1]))
    m.box(P(CONSOLE_IN + 0.075, CONSOLE_Y + 0.135, tz + 0.02), grip_ax, (0.03, 0.045, 0.04), sw['black'])
    m.box(P(CONSOLE_IN + 0.075, CONSOLE_Y + 0.185, tz + 0.03), grip_ax, (0.026, 0.01, 0.032), sw['dgrey'])
    m.box(P(CONSOLE_IN + 0.052, CONSOLE_Y + 0.15, tz + 0.045), grip_ax, (0.004, 0.008, 0.008), sw['red'])

    # -- K-36DM ejection seat (the MiG-29 seat's dimensions), on the station's floor
    PS = lambda x, y, z: P(x, y + SEAT_DY, z)   # noqa: E731
    # (quilted olive cushions with the harness, the black headpad and striped headbox top, the leg guards, the
    # stabiliser booms, the lap belt, and the red double loop between the knees on a striped root)
    pan = atlas.paint(72, 72, p_k36_cushion(False))
    back = atlas.paint(72, 108, p_k36_cushion(True))
    headpad = atlas.paint(64, 56, p_k36_headpad)
    stripes = atlas.paint(64, 12, p_k36_stripes)
    ax_b = (X, H.unit([0, 1, -0.14]), H.unit([0, 0.14, 1]))
    base_top = -0.97 + SEAT_DY                                       # the seat's base down to the floor
    m.box(P(0, (base_top + FLOOR_Y) / 2, -0.004), (X, Y, Z), (0.196, (base_top - FLOOR_Y) / 2, 0.22), sw['frame'])
    m.box(PS(0, -0.887, 0.026), (X, Y, Z), (0.19, 0.035, 0.21), sw['cushion'], front=(1, 1, pan, -X, Z))
    for s in (1, -1):
        m.box(PS(s * 0.212, -0.882, -0.014), (X, Y, Z), (0.012, 0.15, 0.24), sw['frame'])
        m.box(PS(s * 0.17, -0.44, -0.404), ax_b, (0.02, 0.54, 0.025), sw['frame'])   # guide beams, their tops under the glass
        lg = H.unit([0, 1, 0.6])                                                       # the leg guards at the pan's front
        m.box(PS(s * 0.214, -0.80, 0.20), (H.unit([1, 0, -0.25 * s]), lg, H.unit(np.cross([1, 0, -0.25 * s], lg))),
              (0.008, 0.07, 0.035), sw['frame'])
        bm = (X, H.unit([0, 1, -0.5]), H.unit([0, 0.5, 1]))                            # the stabiliser booms
        m.box(PS(s * 0.10, 0.10, -0.43), bm, (0.012, 0.09, 0.012), sw['metal'])
    m.box(PS(0, -0.502, -0.234), ax_b, (0.19, 0.33, 0.03), sw['cushion'], front=(2, 1, back, X, ax_b[1]))
    m.box(PS(0, -0.502, -0.294), ax_b, (0.205, 0.36, 0.035), sw['frame'])
    m.box(PS(0, -0.03, -0.314), (X, Y, Z), (0.16, 0.15, 0.10), sw['frame'])   # headbox, under the glass
    m.box(PS(0, -0.02, -0.199), (X, Y, Z), (0.11, 0.09, 0.015), sw['black'], front=(2, 1, headpad, X, Y))
    m.box(PS(0, 0.122, -0.300), (X, Y, Z), (0.15, 0.004, 0.075), sw['black'], front=(1, 1, stripes, X, -Z))
    m.box(PS(0, -0.835, 0.10), (X, Y, Z), (0.17, 0.006, 0.02), sw['mgrey'])                # the lap belt and buckle
    m.box(PS(0, -0.828, 0.10), (X, Y, Z), (0.03, 0.008, 0.026), sw['metal'])
    red = atlas.swatch((178, 34, 30))
    for s in (1, -1):
        for xl in (0.02, 0.09):
            m.box(PS(s * xl, -0.818, 0.256), (X, Y, Z), (0.007, 0.034, 0.007), red)
        m.box(PS(s * 0.055, -0.780, 0.256), (X, Y, Z), (0.042, 0.007, 0.007), red)
    m.box(PS(0, -0.852, 0.256), (X, Y, Z), (0.032, 0.01, 0.01), sw['black'], front=(2, 1, stripes, X, Y))

    # -- control stick (RUS): the boot stays with the cockpit, the rest goes into the stick bone
    sz = 0.40
    m.box(P(0, FLOOR_Y + 0.045, sz), (X, Y, Z), (0.045, 0.045, 0.045), sw['dgrey'])
    if FLOOR_Y + 0.09 < -0.92 + SEAT_DY:                             # a tall boot up to the column's foot
        m.box(P(0, (FLOOR_Y + 0.09 - 0.92 + SEAT_DY) / 2, sz), (X, Y, Z), (0.03, (-0.92 + SEAT_DY - FLOOR_Y - 0.09) / 2, 0.03), sw['dgrey'])
    stick = H.Mesh(atlas)
    col_ax = (X, H.unit([0, 1, -0.07]), H.unit([0, 0.07, 1]))
    stick.box(PS(0, -0.70, sz - 0.002), col_ax, (0.011, 0.215, 0.011), sw['metal'])
    g_ax = (X, H.unit([0, 1, 0.21]), H.unit([0, -0.21, 1]))
    gc = PS(0, -0.437, sz - 0.008)
    stick.box(gc, g_ax, (0.021, 0.062, 0.027), sw['black'])
    stick.box(gc + g_ax[1] * 0.066, g_ax, (0.017, 0.006, 0.022), sw['dgrey'])
    stick.box(gc + g_ax[1] * 0.07 + g_ax[2] * 0.010, g_ax, (0.006, 0.004, 0.006), sw['red'])
    stick.box(gc + g_ax[2] * 0.031 + g_ax[1] * 0.015, g_ax, (0.008, 0.012, 0.005), sw['metal'])
    stick.box(gc - g_ax[2] * 0.034 + g_ax[1] * 0.01, g_ax, (0.012, 0.035, 0.004), sw['metal'])
    foot = PS(0, -0.90, sz)
    return m, (stick, foot), gauges, displays, hud


def old_interior(E0, E1):
    """boxes (vehicle-local lo, hi) of the old cockpits: each tub inside the glass line above its floor, the old
    consoles under the sloping sides (whose skin stays), the old front panel and its deck, the old pedals, the old
    seats"""
    f, r = np.array(E0), np.array(E1)
    return [(f + [-0.44, -1.17, -0.47], f + [0.44, -0.345, 0.45]),
            (f + [-0.66, -1.17, -0.47], f + [0.66, -0.725, 0.45]),
            (f + [-0.40, -0.80, 0.40], f + [0.40, -0.22, 0.80]),
            (f + [-0.12, -0.22, 0.40], f + [0.12, 0.10, 0.60]),
            (f + [-0.35, -1.17, 0.30], f + [0.35, -0.75, 0.82]),
            (r + [-0.30, -1.20, -0.55], r + [0.30, -0.425, 0.70]),
            (r + [-0.62, -1.20, -0.55], r + [0.62, -0.965, 0.70]),
            (f + [-0.25, -0.90, -0.70], f + [0.25, 0.12, -0.30])]         # the old front seat, its back and headrest


def repaint_tub(ac, atlas, E):
    """the model's old turquoise tub round both new cockpits, and its light rim skin inside the canopy's sill line
    (front-eye frame, the canopy's own sections): repainted in the deck's dark grey-green instead of removed (they
    close the fuselage)"""
    import canopy_j_15d as CS
    sz = [z for z, s in CS.SECTIONS]; sx = [s[0][0] for z, s in CS.SECTIONS]; sy = [s[0][1] for z, s in CS.SECTIONS]
    tex = Image.open(L.texture_path(VID)).convert('RGB')
    TW, TH = tex.size
    deck, black = atlas.swatch(DECK), atlas.swatch(BLACK)
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
            if not (-2.80 < c[2] < 1.30 and abs(c[0]) < 0.75 and -1.6 < c[1] < 0.5):
                continue
            u, v = np.mean([pm['uvs'][q[2]] for q in poly], axis=0)
            r, g, bl = tex.getpixel((min(int(u * TW), TW - 1), min(int((1 - v) * TH), TH - 1)))
            inside = c[2] < 0.90 and abs(c[0]) < np.interp(c[2], sz, sx) - 0.01 and c[1] < np.interp(c[2], sz, sy) + 0.02
            # the old rear consoles' tops beside the new ones (their painted teal panels show past the new consoles' ends)
            rear_con = -1.70 < c[2] < -0.45 and abs(c[0]) > 0.25 and -0.80 < c[1] < -0.70
            if (r < 70 and g > 110 and bl > 120) or (inside and r + g + bl > 200) or rear_con:
                # (ahead of the windscreen: the old deck the old glass covered, black there on the untouched model)
                pm['uvs'].append(atlas.flat(deck if c[2] < 0.90 else black))
                k = len(pm['uvs']) - 1
                for q in poly:
                    q[2] = k
                n += 1
    return n


def main(argv):
    dry = '--dry-run' in argv
    ac = L.Aircraft(VID)
    E0, E1 = ac.eye(0), ac.eye(1)
    removed = H.remove_faces(ac, old_interior(E0, E1))
    removed['old sight in the view'] = H.remove_sight_clutter(ac, E0)
    atlas = H.A.Atlas(VID)
    removed['faces repainted'] = repaint_tub(ac, atlas, E0)
    use('front')
    HUD_TOP[0] = H.view_ceiling(ac, E0, PANEL_Z + 0.06)
    mesh, stick, gauges, displays, hud = build(atlas, E0)
    use('rear')
    mesh_r, (stick_r, _), gauges_r, displays_r, _ = build(atlas, E1)
    # the bulkhead between the seats: the rear footwell's front wall behind the front seat, following the sloping
    # sides (rear-seat frame: 0.62 m ahead of the rear eye, clear of the front seat's rails)
    zb_ = E1[2] + 0.62
    wall = [E1 + [x, y, 0.62] for x, y in ((0.30, -0.42), (0.62, -0.78), (0.62, -1.21), (-0.62, -1.21), (-0.62, -0.78), (-0.30, -0.42))]
    mesh_r.flat(wall, atlas.swatch(WALLC_D), normal=[0, 0, -1])
    del zb_
    mesh.faces += mesh_r.faces + stick_r.faces                        # the rear stick stays fixed in the cockpit
    ac.asset['CockpitGauges'] = {'Gauges': []}                        # both seats' instruments are written anew
    ac.asset['FlightDisplays'] = {'Displays': []}
    pm = H.install(ac, mesh, stick, SOURCE_BONE, STICK_BONE, gauges + gauges_r, displays + displays_r, hud, E0)
    print(f'{VID}: removed {removed}; new cockpits {len(pm["polys"])} faces, {len(gauges)} + {len(gauges_r)} gauges')
    if not dry:
        H.write(ac, atlas)


if __name__ == '__main__':
    main(sys.argv[1:])
