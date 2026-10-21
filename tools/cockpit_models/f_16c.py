#!/usr/bin/env python3
"""F-16C Block 50 cockpit, built by hand for this model (as mig_29.py), from the real panel layout.

Measured off the model with sections.py and eyeview.py (all heights and distances from the pilot's eye at
(0, 2.603, 2.8695); 1 block = 1 m on this model):
  * a box cockpit: the walls' inner faces at x +-0.34 from the floor (y -1.12) up to where the canopy glass meets
    them (y -0.42 beside the seat, -0.47 0.6 m ahead, the glass narrowing to x +-0.30 there);
  * the old panel block's face stands 0.60 m ahead, the glareshield top at y -0.25 running forward to y -0.36 at
    1.05 m ahead; the seat's back 0.45 m behind the eye.
Built:
  * a dark grey three-facet main panel 0.58 m ahead, 12 degrees back, its narrow wings turned 25 degrees to the
    walls, their tops sloping down under the glass (as the real panel's shoulders): after the "F16 Cockpit, Asian
    Aerospace 2006" Block 52 photograph and the xflight.de Block 50/52 drawing (work/cockpit_diagrams): the ICP
    under the HUD, the two MFDs either side of the centre column with the standby attitude, HSI and VVI in it;
    airspeed / altimeter / AoA and the gear panel on the left; the RWR scope, fuel, engine rpm and FTIT on the right,
    the caution light panel low on the right;
  * the black glareshield forward to the windscreen, the wide-angle HUD on it (the game projects the picture);
  * grey side consoles, side walls, rudder pedals, the throttle on the left console, the side stick on the right
    console (into the stick bone), an ACES II seat.
Instruments the game draws (CockpitGauges, FlightDisplays, HeadUpDisplay) are live. No glass.

Run on the committed originals (git checkout be357476 -- the f_16c geo, asset and both textures first).
Usage: python3 tools/cockpit_models/f_16c.py [--dry-run]
"""
import math, os, sys
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import cockpit_lib as L   # noqa: E402
import handbuilt as H     # noqa: E402

VID = 'f_16c'
SOURCE_BONE = 'wreck_fuselage_1__hull'
STICK_BONE = 'wreck_fuselage_1__control_stick_pitch'

TURQ = (58, 60, 63)            # the F-16's dark grey panel and consoles
TURQ_D = (44, 46, 48)
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

# ---------------------------------------------------------------- the measured cockpit (metres from the eye)
WALL_X = 0.330                 # panel wings and consoles end here (the walls at 0.34)
SILL_Y = -0.43
FLOOR_Y = -1.12
BACK_Z = -0.45                 # behind the seat
CONSOLE_Y = -0.74
CONSOLE_IN = 0.20
PANEL_Z = 0.58                 # centre facet reference plane
LEAN = math.radians(12)
HINGE = 0.250                  # centre facet half width: the two MFDs and the centre column
WING = math.radians(25)
WING_LEN = (WALL_X - HINGE) / math.cos(WING)
TOP_C, TOP_W = -0.270, -0.490  # centre facet top; the wings' tops slope down under the narrowing glass
BOT_C, BOT_W = -0.720, CONSOLE_Y
FRONT_Z = 1.00                 # the glareshield runs forward to the windscreen base
FRONT_Y_C, FRONT_Y_W = -0.330, -0.440
SEAT_DY = -0.10                # the floor sits 3 cm lower under the eye than the MiG-29's, the seat 10 cm lower

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


# Instrument layout of the F-16C Block 50 front panel. facet: 0 centre (a: metres to the pilot's right of the centre
# line), +1 right wing, -1 left wing (a: metres outward from the hinge). y: from the eye.
LAYOUT = [
    ('icp',      0,  0.000, -0.335, ('paint_rect', 0.150, 0.090), 'integrated control panel (ICP) and DED'),
    ('mfd_l',    0, -0.152, -0.470, ('display', 'RADAR', 0.140), 'left MFD (radar)'),
    ('mfd_r',    0,  0.152, -0.470, ('display', 'STORES', 0.140), 'right MFD (stores)'),
    ('adi',      0,  0.000, -0.445, ('game', 'ATTITUDE', 0.062), 'standby attitude'),
    ('hsi',      0,  0.000, -0.530, ('game', 'HEADING', 0.064), 'HSI'),
    ('vvi',      0,  0.000, -0.610, ('game', 'VVI', 0.050), 'vertical velocity'),
    ('caution',  0, -0.205, -0.305, ('paint_disc', 0.024), 'master caution'),
    ('ias',     -1,  0.045, -0.445, ('game', 'AIRSPEED', 0.054), 'airspeed / Mach'),
    ('alt',     -1,  0.045, -0.515, ('game', 'ALTIMETER', 0.054), 'altimeter'),
    ('aoa',     -1,  0.045, -0.580, ('game', 'AOA', 0.040), 'AoA indexer'),
    ('gear',    -1,  0.045, -0.675, ('paint_rect', 0.050, 0.080), 'landing gear panel'),
    ('rwr',      1,  0.045, -0.445, ('game', 'RWR', 0.052), 'RWR azimuth indicator'),
    ('fuel',     1,  0.045, -0.515, ('game', 'FUEL_QTY', 0.048), 'fuel quantity'),
    ('rpm',      1,  0.045, -0.575, ('game', 'ENGINE_RPM', 0.044), 'engine rpm'),
    ('ftit',     1,  0.045, -0.630, ('game', 'EGT', 0.044), 'FTIT'),
    ('warning',  1,  0.045, -0.690, ('annunciator', 0.060, 0.050), 'caution light panel'),
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
            for k, lab in enumerate(['BRAKES', 'EMER JETT']):
                x, yy = to_px(-0.16 + k * 0.32, -0.700)
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
    H.text(d, (S / 2, Hh * .55), 'GEAR', S * .16)
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


def p_icp(d, im):
    """the ICP: the DED's green text window on top, the keypad under it, the rocker and wheels beside"""
    S, Hh = im.width, im.height
    d.rectangle([0, 0, S, Hh], fill=(40, 42, 44, 255))
    d.rectangle([S * .20, Hh * .05, S * .80, Hh * .32], fill=(10, 24, 14, 255), outline=(80, 80, 80, 255), width=3)
    H.text(d, (S * .5, Hh * .13), 'UHF 305.00  STPT 1', Hh * .08, fill=(110, 220, 120))
    H.text(d, (S * .5, Hh * .24), '12:34:56   HACK', Hh * .08, fill=(110, 220, 120))
    labs = ['1', '2', '3', 'COM', '4', '5', '6', 'IFF', '7', '8', '9', 'LIST', 'RCL', '0', 'ENTR', 'A-A']
    for k, lab in enumerate(labs):
        cx, cy = k % 4, k // 4
        x0, y0 = S * (0.18 + cx * 0.16), Hh * (0.38 + cy * 0.15)
        d.rectangle([x0, y0, x0 + S * .13, y0 + Hh * .12], fill=(70, 72, 74, 255), outline=(10, 10, 10, 255), width=2)
        H.text(d, (x0 + S * .065, y0 + Hh * .06), lab, Hh * .06)
    for x in (S * .06, S * .94):
        d.ellipse([x - S * .04, Hh * .45, x + S * .04, Hh * .62], fill=(20, 20, 20, 255), outline=(120, 120, 120, 255), width=2)


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
    S, Hh = im.width, im.height
    H.noisy(d, im, DGREY, 4, seed=9)
    for k, lab in enumerate(['SYM', 'BRT', 'DAY/NT', 'DECLT']):
        x = S * (0.13 + k * 0.245)
        if k == 1:
            d.ellipse([x - 22, Hh * .5 - 22, x + 22, Hh * .5 + 22], fill=(20, 20, 20, 255), outline=(110, 110, 110, 255),
                      width=3)
        elif k == 3:
            H.lamp(d, [x - 22, Hh * .3, x + 22, Hh * .7], (60, 170, 70), None)
        else:
            d.rectangle([x - 6, Hh * .25, x + 6, Hh * .65], fill=(170, 170, 170, 255))
        H.text(d, (x, Hh * .86), lab, Hh * .085)


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
    H.text(d, (S * .5, Hh * .5), 'ACES II', Hh * .2, fill=(40, 40, 40), bold=True)


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
    cols, lamps = L.lamps_of('cw', 'nato')
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
            painter = {'gear': p_gear, 'icp': p_icp}[name]
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

    # -- the HUD: the body on the coaming, its aft control strip, the lens, the combiner posts and frame (no
    # glass); the picture the game projects (HeadUpDisplay) fills the frame, centred on the pilot's line of sight
    hud_z0, hud_z1 = E[2] + PANEL_Z + 0.02, E[2] + PANEL_Z + 0.26
    hud_y0 = E[1] + TOP_C + 0.025
    hud_y1 = hud_y0 + 0.10
    ctrl = atlas.paint(128, 80, p_hud_ctrl)
    lens = atlas.paint(64, 64, p_hud_lens)
    m.box([E[0], (hud_y0 + hud_y1) / 2, (hud_z0 + hud_z1) / 2], (X, Y, Z), (0.100, (hud_y1 - hud_y0) / 2, (hud_z1 - hud_z0) / 2),
          sw['dgrey'], front=(2, -1, ctrl, -X, Y))
    m.rect_tex([E[0], hud_y1 + 0.001, (hud_z0 + hud_z1) / 2 + 0.03], [0, 1, 0], [0, 0, 1], 0.10, 0.10, lens)
    g_lean = math.radians(22)
    g_up = np.array([0, math.cos(g_lean), -math.sin(g_lean)])
    g_n = H.unit(np.cross(X, g_up))
    g_h, g_hw = 0.160, 0.085                   # a little larger than the real glass, for the game
    g_bot = np.array([E[0], E[1] - g_up[1] * g_h / 2, hud_z0 + 0.02])
    g_top = g_bot + g_up * g_h
    stem = g_bot[1] - 0.006 - hud_y1
    if stem > 0.004:
        m.box([E[0], hud_y1 + stem / 2, g_bot[2]], (X, Y, Z), (g_hw + 0.008, stem / 2, 0.012), sw['dgrey'])
    m.box(g_bot - g_up * 0.006, (X, g_up, g_n), (g_hw + 0.008, 0.006, 0.012), sw['dgrey'])
    for s in (-1, 1):
        m.box((g_bot + g_top) / 2 + X * s * (g_hw + 0.004), (X, g_up, g_n), (0.004, g_h / 2, 0.005), sw['dgrey'])
    m.box(g_top + g_up * 0.004, (X, g_up, g_n), (g_hw + 0.008, 0.004, 0.005), sw['dgrey'])
    hud = dict(centre=(g_bot + g_top) / 2, normal=-g_n, up=g_up, width=2 * g_hw, height=g_h)

    # -- side walls, consoles, the closing faces under the panel
    for side in (1, -1):                                            # +1 = left (x > 0)
        xw = E[0] + WALL_X * side
        wall = atlas.paint(180, 100, H.p_wall(TURQ_D, TURQ, 30 + side))
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
        con = atlas.paint(56, 300, H.p_console(TURQ, 20 + side, [0.11, 0.08, 0.13, 0.07, 0.12, 0.09, 0.10, 0.08] if side > 0
                                               else [0.09, 0.12, 0.08, 0.11, 0.10, 0.09, 0.13, 0.08],
                                               slot=(0.44, 0.30, 0.56, 0.62) if side > 0 else None))
        top = [[xi, cy, zb], [xo, cy, zb], [xo, cy, zf_o], [xi, cy, zf_i]]
        m.face(top, [m.uv(con, 0, 0), m.uv(con, 1, 0), m.uv(con, 1, 1), m.uv(con, 0, 1)], normal=[0, 1, 0])
        m.flat([[xi, fy, zb], [xi, fy, zf_i], [xi, cy, zf_i], [xi, cy, zb]], sw['turq_d'], normal=[-side, 0, 0])
        m.flat([[xi, fy, zb], [xo, fy, zb], [xo, cy, zb], [xi, cy, zb]], sw['turq_d'], normal=[0, 0, -1])
        m.box([xw - side * 0.015, E[1] + SILL_Y - 0.005, E[2] + (BACK_Z + PANEL_Z - 0.1) / 2], (X, Y, Z),
              (0.03, 0.01, (PANEL_Z - 0.1 - BACK_Z) / 2), sw['dgrey'])                     # canopy rail ledge
    # under the centre facet: its lower closing face back to the footwell
    bl, br = centre.at(HINGE, E[1] + BOT_C), centre.at(-HINGE, E[1] + BOT_C)
    m.flat([bl, br, br + np.array([0, 0, 0.12]), bl + np.array([0, 0, 0.12])], sw['turq_d'], normal=[0, -1, 0])
    for s in (1, -1):                                               # the footwell sides under the wings
        e = centre.at(-HINGE * s, E[1] + BOT_C)
        m.flat([e, centre.at(-HINGE * s, E[1] + BOT_W), [E[0] + s * CONSOLE_IN, E[1] + BOT_W, e[2]],
                [E[0] + s * CONSOLE_IN, E[1] + BOT_C, e[2]]], sw['turq_d'], normal=[0, 0, -1])
    # the floor: a dark plate over the model's own (white) floor, from behind the seat to the pedals
    fw = CONSOLE_IN + 0.04                                          # under the consoles' inner faces too
    m.flat([P(-fw, FLOOR_Y + 0.006, BACK_Z), P(fw, FLOOR_Y + 0.006, BACK_Z),
            P(fw, FLOOR_Y + 0.006, PANEL_Z + 0.06), P(-fw, FLOOR_Y + 0.006, PANEL_Z + 0.06)], sw['floor'],
           normal=[0, 1, 0])
    # the bulkhead just behind the main panel, the fuselage's own cross-section there: no skin shows round the panel
    half = [(0.0, -0.68), (0.33, -0.68), (0.33, -1.12), (0.0, -1.12)]
    ring_ = [(x, y) for x, y in half] + [(-x, y) for x, y in reversed(half)]
    m.flat([P(x, y, PANEL_Z + 0.08) for x, y in ring_], atlas.swatch((28, 30, 30)), normal=[0, 0, -1])
    # rudder pedals under the panel
    for s in (1, -1):
        m.box(P(s * 0.12, FLOOR_Y + 0.14, PANEL_Z - 0.02), (X, H.unit([0, 1, -0.35]), H.unit([0, 0.35, 1])), (0.045, 0.07, 0.01), sw['mgrey'])
        m.box(P(s * 0.12, FLOOR_Y + 0.035, PANEL_Z + 0.02), (X, Y, Z), (0.012, 0.03, 0.06), sw['dgrey'])

    # -- throttle on the left console
    tz = 0.20
    m.box(P(0.265, CONSOLE_Y + 0.055, tz), (X, H.unit([0, 1, 0.25]), H.unit([0, -0.25, 1])), (0.009, 0.06, 0.009), sw['metal'])
    grip_ax = (X, H.unit([0, 1, 0.3]), H.unit([0, -0.3, 1]))
    m.box(P(0.265, CONSOLE_Y + 0.135, tz + 0.02), grip_ax, (0.03, 0.045, 0.04), sw['black'])
    m.box(P(0.265, CONSOLE_Y + 0.185, tz + 0.03), grip_ax, (0.026, 0.01, 0.032), sw['dgrey'])
    m.box(P(0.242, CONSOLE_Y + 0.15, tz + 0.045), grip_ax, (0.004, 0.008, 0.008), sw['red'])

    # -- ACES II ejection seat, on this model's floor
    PS = lambda x, y, z: P(x, y + SEAT_DY, z)   # noqa: E731 (the MiG-29 seat's dimensions: the floor sits at the same depth under the eye)
    cushion = atlas.paint(64, 64, H.p_cushion(CUSHION))
    back = atlas.paint(64, 96, H.p_cushion(CUSHION, straps=True))
    headbox = atlas.paint(64, 64, p_headbox)
    stripes = atlas.paint(64, 16, H.p_stripes)
    ax_b = (X, H.unit([0, 1, -0.14]), H.unit([0, 0.14, 1]))
    base_top = -0.94 + SEAT_DY                                       # the seat's base down to the floor
    m.box(P(0, (base_top + FLOOR_Y) / 2, -0.004), (X, Y, Z), (0.20, (base_top - FLOOR_Y) / 2, 0.22), sw['frame'])
    m.box(PS(0, -0.887, 0.026), (X, Y, Z), (0.19, 0.035, 0.21), sw['cushion'], front=(1, 1, cushion, -X, Z))
    for s in (1, -1):
        m.box(PS(s * 0.212, -0.882, -0.014), (X, Y, Z), (0.012, 0.15, 0.24), sw['frame'])
        m.box(PS(s * 0.17, -0.402, -0.404), ax_b, (0.02, 0.62, 0.025), sw['frame'])
    m.box(PS(0, -0.502, -0.234), ax_b, (0.19, 0.33, 0.03), sw['cushion'], front=(2, 1, back, X, ax_b[1]))
    m.box(PS(0, -0.502, -0.294), ax_b, (0.205, 0.36, 0.035), sw['frame'])
    m.box(PS(0, 0.008, -0.314), (X, Y, Z), (0.16, 0.20, 0.10), sw['frame'])
    m.box(PS(0, 0.018, -0.199), (X, Y, Z), (0.11, 0.11, 0.015), sw['cushion'], front=(2, 1, headbox, X, Y))
    for s in (1, -1):
        m.box(PS(s * 0.05, -0.817, 0.256), (X, Y, Z), (0.008, 0.035, 0.008), sw['yellow'])
    m.box(PS(0, -0.782, 0.256), (X, Y, Z), (0.058, 0.008, 0.008), sw['yellow'], front=(2, -1, stripes, -X, Y))

    # -- the side stick on the right console's forward end, with the forearm rest behind it: the base stays with the
    # cockpit, the grip goes into the stick bone (pivot at its foot)
    sx, sz = -0.265, 0.12
    m.box(P(sx, CONSOLE_Y + 0.015, sz), (X, Y, Z), (0.035, 0.015, 0.045), sw['black'])
    m.box(P(sx, CONSOLE_Y + 0.04, sz - 0.20), (X, Y, Z), (0.045, 0.04, 0.12), sw['dgrey'])           # forearm rest
    stick = H.Mesh(atlas)
    g_ax = (X, H.unit([0, 1, 0.23]), H.unit([0, -0.23, 1]))
    gc = P(sx, CONSOLE_Y + 0.085, sz + 0.01)
    stick.box(gc, g_ax, (0.019, 0.055, 0.026), sw['black'])
    stick.box(gc + g_ax[1] * 0.058, g_ax, (0.016, 0.006, 0.022), sw['dgrey'])
    stick.box(gc + g_ax[1] * 0.062 + g_ax[2] * 0.008, g_ax, (0.005, 0.004, 0.005), sw['red'])
    stick.box(gc + g_ax[2] * 0.028 + g_ax[1] * 0.012, g_ax, (0.007, 0.011, 0.005), sw['metal'])
    foot = P(sx, CONSOLE_Y + 0.03, sz)
    return m, (stick, foot), gauges, displays, hud


def old_interior(E):
    """boxes (vehicle-local lo, hi) of the old cockpit: the tub inside the walls above the floor from behind the
    seat to the old panel, the old panel block and glareshield up to the windscreen base, the old seat"""
    e = np.array(E)
    return [(e + [-0.335, -1.11, BACK_Z - 0.20], e + [0.335, SILL_Y - 0.005, 0.62]),
            (e + [-0.32, -0.80, 0.50], e + [0.32, -0.20, 1.06]),
            (e + [-0.16, -0.30, -0.65], e + [0.16, 0.25, -0.30])]


def main(argv):
    dry = '--dry-run' in argv
    ac = L.Aircraft(VID)
    E = ac.eye(0)
    removed = H.remove_faces(ac, old_interior(E))
    atlas = H.A.Atlas(VID)
    mesh, stick, gauges, displays, hud = build(atlas, E)
    pm = H.install(ac, mesh, stick, SOURCE_BONE, STICK_BONE, gauges, displays, hud, E)
    print(f'{VID}: removed {removed}; new cockpit {len(pm["polys"])} faces, {len(gauges)} gauges')
    if not dry:
        H.write(ac, atlas)


if __name__ == '__main__':
    main(sys.argv[1:])
