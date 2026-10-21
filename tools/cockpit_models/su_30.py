#!/usr/bin/env python3
"""Su-30 cockpits (both seats), built by hand for this model (as mig_29.py and su_27.py), from the real layouts.

Measured off the model with sections.py and eyeview.py (1 block = 1 m on this model). This Su-30 is its own, simpler
model, not the Su-27's: below the canopy's lower edge its fuselage sides slope outward (as the MiG-15's), with the
side consoles under them.
  * Front seat (eye at (0, 3.4688, 5.7188)): the glass meets the fuselage at y -0.28, x +-0.45; the sides slope out
    to x +-0.70 at y -0.55; the consoles at y -0.76 from x 0.40 outward; the floor at y -1.11; the old panel face
    only 0.40 m ahead, its top at y -0.13, the deck sloping down to the windscreen base 0.75 m ahead.
  * Rear seat (eye 1.156 m behind and 0.19 m above the front eye): the glass comes down to y -0.33 at x +-0.33; the
    consoles at y -0.86; the floor at y -1.30 (the seat stands on a raised base); the front seat's back 0.76 m
    ahead, the old rear panel just behind it, its top at y -0.27.
Built, for each seat: a three-facet main panel with its instruments, the black coaming, side consoles with switch
panels and grey-green linings over the sloping sides, rudder pedals, the throttle, a K-36DM seat and a stick.
  * Front: the Su-27 layout (DCS Su-27 manual, figure 20) on a panel 0.38 m ahead; the ILS-31 HUD; the stick goes
    into the stick bone.
  * Rear: attitude over HSI, VVI and altimeter, airspeed and AoA on the left wing, tachometer and fuel on the right,
    the multi-function display and the Ekran low in the centre; a fixed stick.
Instruments the game draws (CockpitGauges, FlightDisplays, HeadUpDisplay) are live for both seats. No glass.

Run on the committed originals (git checkout be357476 -- the su_30 geo, asset and both textures first).
Usage: python3 tools/cockpit_models/su_30.py [--dry-run]
"""
import math, os, sys
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import cockpit_lib as L   # noqa: E402
import handbuilt as H     # noqa: E402

VID = 'su_30'
SOURCE_BONE = 'wreck_fuselage_0__hull'
STICK_BONE = 'wreck_fuselage_0__control_stick_pitch'

TURQ = (74, 150, 154)          # Soviet cockpit turquoise
TURQ_D = (52, 112, 116)
BLACK = (24, 25, 26)
DGREY = (44, 46, 48)
MGREY = (88, 92, 94)
FRAME = (62, 68, 64)           # K-36 seat structure
CUSHION = (58, 62, 52)
HEADPAD = (92, 96, 88)
YELLOW = (226, 186, 30)
RED = (196, 36, 30)
METAL = (128, 132, 134)
FLOOR = (46, 50, 50)
WALLC = (104, 112, 104)        # grey-green linings over the sloping sides
WALLC_D = (84, 92, 86)

# ---------------------------------------------------------------- the measured cockpit (metres from the eye)
# one entry per crew station; use() makes a station's measurements the module's working constants for build()
STATIONS = {
    'front': dict(SEAT=0, WALL_X=0.440, CONSOLE_OUT=0.660, SILL_Y=-0.29, FLOOR_Y=-1.11, BACK_Z=-0.47, CONSOLE_Y=-0.76,
                  CONSOLE_IN=0.36, PANEL_Z=0.38, LEAN=math.radians(12), HINGE=0.20, WING=math.radians(25),
                  TOP_C=-0.130, TOP_W=-0.390, BOT_C=-0.700, BOT_W=-0.700, FRONT_Z=0.66, FRONT_Y_C=-0.290,
                  FRONT_Y_W=-0.400, SEAT_DY=0.0, HAS_HUD=True, LIN=((0.45, -0.30), (0.65, -0.75))),
    'rear': dict(SEAT=1, WALL_X=0.360, CONSOLE_OUT=0.620, SILL_Y=-0.33, FLOOR_Y=-1.30, BACK_Z=-0.55, CONSOLE_Y=-0.86,
                 CONSOLE_IN=0.30, PANEL_Z=0.58, LEAN=math.radians(12), HINGE=0.18, WING=math.radians(25),
                 TOP_C=-0.270, TOP_W=-0.400, BOT_C=-0.850, BOT_W=-0.860, FRONT_Z=0.64, FRONT_Y_C=-0.250,
                 FRONT_Y_W=-0.360, SEAT_DY=-0.12, HAS_HUD=False, LIN=((0.355, -0.34), (0.61, -0.85))),
}


HUD_TOP = [1.0]                # how far above the eye the view under the bow reaches at the glass (main sets it)


def use(name):
    g = globals()
    g.update(STATIONS[name])
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


# Front seat: the instrument layout after the DCS Su-27 manual, figure 20. facet: 0 centre (a: metres to the pilot's right of the
# centre line), +1 right wing, -1 left wing (a: metres outward from the hinge). y: from the eye.
LAYOUT_FRONT = [
    # centre facet
    ('caution',  0, -0.170, -0.190, ('paint_disc', 0.026), 'master caution lamp'),
    ('adi',      0, -0.065, -0.320, ('game', 'ATTITUDE', 0.095), 'KPP attitude director'),
    ('hsi',      0, -0.065, -0.442, ('game', 'HEADING', 0.095), 'PNP horizontal situation'),
    ('vvi',      0,    0.060, -0.310, ('game', 'VVI', 0.064), 'VAR-300 vertical speed'),
    ('alt',      0,    0.060, -0.405, ('game', 'ALTIMETER', 0.066), 'VM-15 altimeter'),
    ('radalt',   0,    0.060, -0.500, ('game', 'RADAR_ALT', 0.058), 'radio altimeter'),
    ('clock',    0,    0.150, -0.205, ('game', 'CLOCK', 0.046), 'AChS-1 clock'),
    ('mach',     0,    0.150, -0.290, ('game', 'MACH', 0.058), 'Machmeter'),
    ('fuel',     0,    0.150, -0.400, ('game', 'FUEL_QTY', 0.058), 'fuel quantity'),
    ('ekran',    0,    0.000, -0.585, ('annunciator', 0.170, 0.075), 'Ekran warning panel'),
    ('afcs',     0,    0.000, -0.668, ('paint_rect', 0.170, 0.040), 'autopilot push-lights'),
    # left wing
    ('aoa',     -1,    0.060, -0.261, ('game', 'AOA', 0.056), 'UAP-14 AoA'),
    ('g',       -1,    0.060, -0.325, ('game', 'G_METER', 0.050), 'UAP-14 accelerometer'),
    ('ias',     -1,    0.160, -0.368, ('game', 'AIRSPEED', 0.064), 'KUS-3 airspeed'),
    ('weapons', -1,    0.117, -0.455, ('paint_rect', 0.150, 0.080), 'weapons control panel'),
    ('gear',    -1,    0.229, -0.480, ('paint_rect', 0.050, 0.100), 'gear control panel'),
    # right wing
    ('hdd',      1,    0.132, -0.408, ('display', 'RADAR', 0.105), 'IPV head-down display'),
    ('spo15',    1,    0.032, -0.405, ('game', 'RWR', 0.043), 'SPO-15 Beryoza'),
    ('rpm',      1,    0.233, -0.416, ('game', 'ENGINE_RPM', 0.043), 'twin tachometer'),
    ('egt',      1,    0.080, -0.503, ('game', 'EGT', 0.037), 'exhaust gas temperature'),
    ('hyd',      1,    0.200, -0.501, ('game', 'FILLER_HYDRAULIC', 0.034), 'hydraulic pressures'),
    ('oxy',      1,    0.140, -0.580, ('game', 'FILLER_OXYGEN', 0.032), 'oxygen'),
]
# Rear seat: the flight group, the engine and fuel pair, the multi-function display and the Ekran
LAYOUT_REAR = [
    ('caution',  0, -0.135, -0.315, ('paint_disc', 0.024), 'master caution lamp'),
    ('clock',    0,    0.130, -0.320, ('game', 'CLOCK', 0.040), 'clock'),
    ('adi',      0, -0.060, -0.405, ('game', 'ATTITUDE', 0.088), 'attitude director'),
    ('hsi',      0, -0.060, -0.520, ('game', 'HEADING', 0.088), 'horizontal situation'),
    ('vvi',      0,    0.065, -0.405, ('game', 'VVI', 0.060), 'vertical speed'),
    ('alt',      0,    0.065, -0.500, ('game', 'ALTIMETER', 0.062), 'altimeter'),
    ('mfd',      0,    0.000, -0.660, ('display', 'STORES', 0.138), 'multi-function display'),
    ('ekran',    0,    0.000, -0.790, ('annunciator', 0.160, 0.050), 'Ekran warning panel'),
    ('ias',     -1,    0.070, -0.405, ('game', 'AIRSPEED', 0.060), 'airspeed'),
    ('aoa',     -1,    0.070, -0.495, ('game', 'AOA', 0.050), 'AoA'),
    ('rpm',      1,    0.070, -0.405, ('game', 'ENGINE_RPM', 0.056), 'twin tachometer'),
    ('fuel',     1,    0.070, -0.495, ('game', 'FUEL_QTY', 0.050), 'fuel quantity'),
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
                x, yy = to_px(-0.16 + k * 0.32, BOT_C + 0.02)
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
        H.text(d, (x, Hh * .86), lab, Hh * .2)


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


def make_hud(m, atlas, sw, E, X, Y, Z):
    """the ILS-31 HUD on the front coaming; returns the HeadUpDisplay picture (the frame's face)"""
    hud_z0, hud_z1 = E[2] + PANEL_Z + 0.02, E[2] + PANEL_Z + 0.30
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
    g_h, g_hw = 0.200, 0.095                   # a little larger than the real glass, for the game
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
    return dict(centre=(g_bot + g_top) / 2, normal=-g_n, up=g_up, width=2 * g_hw, height=g_h)


def p_hud_ctrl(d, im):
    S, Hh = im.width, im.height
    H.noisy(d, im, DGREY, 4, seed=9)
    for k, lab in enumerate(['ФИЛЬТР', 'ЯРК', 'ДН/НЧ', 'КОНТР']):
        x = S * (0.13 + k * 0.245)
        if k == 1:
            d.ellipse([x - Hh * .24, Hh * .38 - Hh * .24, x + Hh * .24, Hh * .38 + Hh * .24], fill=(20, 20, 20, 255), outline=(110, 110, 110, 255),
                      width=3)
        elif k == 3:
            H.lamp(d, [x - Hh * .28, Hh * .14, x + Hh * .28, Hh * .62], (60, 170, 70), None)
        else:
            d.rectangle([x - 3, Hh * .14, x + 3, Hh * .62], fill=(170, 170, 170, 255))
        H.text(d, (x, Hh * .86), lab, Hh * .2)


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


def p_headbox(d, im):
    S, Hh = im.width, im.height
    H.noisy(d, im, HEADPAD, 5, seed=60)
    d.rectangle([S * .1, Hh * .1, S * .9, Hh * .9], outline=(60, 62, 58, 255), width=6)
    H.text(d, (S * .5, Hh * .5), 'К-36ДМ', Hh * .2, fill=(40, 40, 40), bold=True)


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
            gauges.append({'Seat': SEAT, 'Kind': spec[1], 'Center': H.r5(c + f.N * H.GAUGE_LIFT), 'Normal': H.r5(f.N),
                           'Up': H.r5(f.U), 'Diameter': spec[2], 'Depth': 0.03})
        elif kind == 'annunciator':
            w, h = spec[1], spec[2]
            gauges.append({'Seat': SEAT, 'Kind': 'ANNUNCIATOR', 'Center': H.r5(c + f.N * 0.004), 'Normal': H.r5(f.N),
                           'Up': H.r5(f.U), 'Diameter': w, 'Width': w, 'Height': h, 'Depth': 0.02, 'Columns': cols,
                           'Lamps': lamps})
            m.frame_rect(c, f.N, f.U, w, h, 0.005, 0.008, sw['bezel'])
        elif kind == 'display':
            w = spec[2]
            displays.append({'Seat': SEAT, 'Center': H.r5(c + f.N * H.DISPLAY_LIFT), 'Normal': H.r5(f.N), 'Up': H.r5(f.U),
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
    hud = None
    if HAS_HUD:
        hud = make_hud(m, atlas, sw, E, X, Y, Z)


    # -- consoles under the sloping sides, and grey-green linings over the slopes (no walls of our own)
    sw['wall_d'] = atlas.swatch(WALLC_D)
    for side in (1, -1):                                            # +1 = left (x > 0)
        xi, xo = E[0] + CONSOLE_IN * side, E[0] + CONSOLE_OUT * side
        cy, fy = E[1] + CONSOLE_Y, E[1] + FLOOR_Y + 0.02
        zb, zf = E[2] + BACK_Z + 0.06, E[2] + PANEL_Z - 0.06
        con = atlas.paint(72, 300, H.p_console(WALLC, 20 + side + SEAT * 7, [0.11, 0.08, 0.13, 0.07, 0.12, 0.09, 0.10, 0.08] if side > 0
                                               else [0.09, 0.12, 0.08, 0.11, 0.10, 0.09, 0.13, 0.08],
                                               slot=(0.30, 0.30, 0.42, 0.62) if side > 0 else None))
        top = [[xi, cy, zb], [xo, cy, zb], [xo, cy, zf], [xi, cy, zf]]
        m.face(top, [m.uv(con, 0, 0), m.uv(con, 1, 0), m.uv(con, 1, 1), m.uv(con, 0, 1)], normal=[0, 1, 0])
        m.flat([[xi, fy, zb], [xi, fy, zf], [xi, cy, zf], [xi, cy, zb]], sw['wall_d'], normal=[-side, 0, 0])
        m.flat([[xi, fy, zb], [xo, fy, zb], [xo, cy, zb], [xi, cy, zb]], sw['wall_d'], normal=[0, 0, -1])
        m.flat([[xi, fy, zf], [xo, fy, zf], [xo, cy, zf], [xi, cy, zf]], sw['wall_d'], normal=[0, 0, 1])
        (lx0, ly0), (lx1, ly1) = LIN
        xs, xc = E[0] + lx0 * side, E[0] + lx1 * side
        ys, yc = E[1] + ly0, E[1] + ly1
        lining = atlas.paint(150, 60, H.p_wall(WALLC_D, WALLC, 40 + side + SEAT * 7))
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
    m.flat([P(-fw, FLOOR_Y + 0.006, BACK_Z), P(fw, FLOOR_Y + 0.006, BACK_Z),
            P(fw, FLOOR_Y + 0.006, PANEL_Z + 0.06), P(-fw, FLOOR_Y + 0.006, PANEL_Z + 0.06)], sw['floor'],
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
    cushion = atlas.paint(64, 64, H.p_cushion(CUSHION))
    back = atlas.paint(64, 96, H.p_cushion(CUSHION, straps=True))
    headbox = atlas.paint(64, 64, p_headbox)
    stripes = atlas.paint(64, 16, H.p_stripes)
    ax_b = (X, H.unit([0, 1, -0.14]), H.unit([0, 0.14, 1]))
    base_top = -0.97 + SEAT_DY                                       # the seat's base down to the floor
    m.box(P(0, (base_top + FLOOR_Y) / 2, -0.004), (X, Y, Z), (0.196, (base_top - FLOOR_Y) / 2, 0.22), sw['frame'])
    m.box(PS(0, -0.887, 0.026), (X, Y, Z), (0.19, 0.035, 0.21), sw['cushion'], front=(1, 1, cushion, -X, Z))
    for s in (1, -1):
        m.box(PS(s * 0.212, -0.882, -0.014), (X, Y, Z), (0.012, 0.15, 0.24), sw['frame'])
        m.box(PS(s * 0.17, -0.402, -0.404), ax_b, (0.02, 0.62, 0.025), sw['frame'])
    m.box(PS(0, -0.502, -0.234), ax_b, (0.19, 0.33, 0.03), sw['cushion'], front=(2, 1, back, X, ax_b[1]))
    m.box(PS(0, -0.502, -0.294), ax_b, (0.205, 0.36, 0.035), sw['frame'])
    m.box(PS(0, 0.008, -0.314), (X, Y, Z), (0.16, 0.20, 0.10), sw['frame'])
    m.box(PS(0, 0.018, -0.199), (X, Y, Z), (0.11, 0.11, 0.015), sw['cushion'], front=(2, 1, headbox, X, Y))
    for s in (1, -1):
        m.box(PS(s * 0.05, -0.817, 0.256), (X, Y, Z), (0.008, 0.035, 0.0075), sw['yellow'])
    m.box(PS(0, -0.782, 0.256), (X, Y, Z), (0.058, 0.008, 0.008), sw['yellow'], front=(2, -1, stripes, -X, Y))

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
    consoles under the sloping sides (whose skin stays), the old front panel and its deck, the old pedals"""
    f, r = np.array(E0), np.array(E1)
    return [(f + [-0.44, -1.09, -0.47], f + [0.44, -0.295, 0.40]),
            (f + [-0.70, -1.09, -0.47], f + [0.70, -0.745, 0.40]),
            (f + [-0.40, -0.80, 0.38], f + [0.40, -0.10, 0.75]),
            (f + [-0.35, -1.10, 0.30], f + [0.35, -0.75, 0.90]),
            (r + [-0.36, -1.29, -0.55], r + [0.36, -0.335, 0.70]),
            (r + [-0.66, -1.29, -0.55], r + [0.66, -0.845, 0.70]),
            (f + [-0.44, -0.30, -0.62], f + [0.44, 0.30, -0.30])]         # the old front seat's headrest, the old rear coaming


def main(argv):
    dry = '--dry-run' in argv
    ac = L.Aircraft(VID)
    E0, E1 = ac.eye(0), ac.eye(1)
    removed = H.remove_faces(ac, old_interior(E0, E1))
    removed['old sight in the view'] = H.remove_sight_clutter(ac, E0)
    atlas = H.A.Atlas(VID)
    use('front')
    HUD_TOP[0] = H.view_ceiling(ac, E0, PANEL_Z + 0.06)
    mesh, stick, gauges, displays, hud = build(atlas, E0)
    use('rear')
    mesh_r, (stick_r, _), gauges_r, displays_r, _ = build(atlas, E1)
    # the bulkhead between the seats: the rear footwell's front wall behind the front seat, following the sloping
    # sides (rear-seat frame: 0.62 m ahead of the rear eye, clear of the front seat's rails)
    zb_ = E1[2] + 0.62
    wall = [E1 + [x, y, 0.62] for x, y in ((0.36, -0.30), (0.62, -0.70), (0.62, -1.30), (-0.62, -1.30), (-0.62, -0.70), (-0.36, -0.30))]
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
