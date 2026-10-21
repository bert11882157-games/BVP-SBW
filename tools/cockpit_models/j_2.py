#!/usr/bin/env python3
"""J-2 (the Chinese-built MiG-15bis) cockpit, built by hand for this model (as mig_15bis.py), from the real panel
layout.

Measured off the model with sections.py and eyeview.py (all heights and distances from the pilot's eye at
(0, 2.0947, 2.8857); 1 block = 1 m on this model). The airframe is the MiG-15's, its cockpit a little longer:
  * the canopy glass meets the fuselage at y -0.22, x +-0.38; under it the sides slope out to x +-0.68 at y -0.55,
    where the side consoles sit (y -0.60 from x 0.27 outward); the floor at y -1.01;
  * the old panel block's face stands 0.58 m ahead with its top at y -0.21; the windscreen's lower edge at y -0.20.
Built: the MiG-15bis cockpit (mig_15bis.py: the DCS MiG-15bis manual's figure 4.5 layout on a black panel, the
ASP-3N gunsight, grey-green consoles and linings) set to these measurements, the panel 0.56 m ahead.
Instruments the game draws (CockpitGauges) are live. No glass.

Run on the committed originals (git checkout be357476 -- the j_2 geo, asset and both textures first).
Usage: python3 tools/cockpit_models/j_2.py [--dry-run]
"""
import math, os, sys
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import cockpit_lib as L   # noqa: E402
import handbuilt as H     # noqa: E402

VID = 'j_2'
SOURCE_BONE = 'wreck_fuselage_0__hull'
STICK_BONE = 'wreck_fuselage_0__control_stick_pitch'

TURQ = (34, 35, 37)            # the early jets' black instrument panel
TURQ_D = (24, 25, 26)
WALLC = (104, 112, 104)        # grey-green cockpit sides and consoles
WALLC_D = (84, 92, 86)
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
WALL_X = 0.360                 # the panel's wings end here, under the glass's lower edge
CONSOLE_OUT = 0.600            # the consoles run out over the sloping fuselage sides
SILL_Y = -0.24
FLOOR_Y = -1.01
BACK_Z = -0.45                 # behind the seat
CONSOLE_Y = -0.595
CONSOLE_IN = 0.27
PANEL_Z = 0.56                 # centre facet reference plane
LEAN = math.radians(10)
HINGE = 0.200                  # centre facet half width
WING = math.radians(15)
WING_LEN = (WALL_X - HINGE) / math.cos(WING)
TOP_C, TOP_W = -0.210, -0.300  # centre facet top, wing top at its end (4 cm under the glass)
BOT_C, BOT_W = -0.640, -0.600
FRONT_Z = 0.75                 # the coaming runs forward to the windscreen
FRONT_Y_C, FRONT_Y_W = -0.240, -0.300

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


# Instrument layout after the DCS MiG-15bis manual, figure 4.5. facet: 0 centre (a: metres to the pilot's right of
# the centre line), +1 right wing, -1 left wing (a: metres outward from the hinge). y: from the eye.
LAYOUT = [
    ('kus',       0, -0.147, -0.301, ('game', 'AIRSPEED', 0.075), 'KUS-1200 airspeed'),
    ('vd17',      0, -0.147, -0.404, ('game', 'ALTIMETER', 0.075), 'VD-17 altimeter'),
    ('alt_hi',    0, -0.147, -0.505, ('game', 'FILLER_TEMP', 0.071), 'high altimeter'),
    ('agk47',     0, -0.046, -0.307, ('game', 'ATTITUDE', 0.088), 'AGK-47B horizon'),
    ('clock',     0, -0.055, -0.425, ('game', 'CLOCK', 0.073), 'AChKhO clock'),
    ('mach',      0, -0.046, -0.524, ('game', 'MACH', 0.073), 'Machmeter'),
    ('var75',     0,   0.059, -0.329, ('game', 'VVI', 0.075), 'VAR-75 climb'),
    ('fuel',      0,   0.059, -0.450, ('game', 'FUEL_QTY', 0.075), 'fuel quantity'),
    ('ark',       0,   0.150, -0.301, ('game', 'FILLER_TEMP', 0.075), 'ARK-5 radio compass'),
    ('gik',       0,   0.150, -0.391, ('game', 'HEADING', 0.075), 'GIK-1 compass'),
    ('press',     0,   0.152, -0.500, ('game', 'FILLER_OIL', 0.071), 'oil / fuel pressure'),
    ('rpm',       1,   0.050, -0.311, ('game', 'ENGINE_RPM', 0.075), 'tachometer'),
    ('egt',       1,   0.050, -0.406, ('game', 'EGT', 0.075), 'exhaust gas temperature'),
    ('eng_small', 1,   0.045, -0.496, ('game', 'FILLER_FUEL', 0.058), 'fuel pressure'),
    ('voltamp',   1,   0.128, -0.412, ('game', 'FILLER_VOLTS', 0.053), 'volt-ammeter'),
    ('cabin_alt', 1,   0.121, -0.500, ('game', 'FILLER_TEMP', 0.065), 'cabin altitude'),
    ('oxygen',   -1,   0.042, -0.307, ('game', 'FILLER_OXYGEN', 0.060), 'oxygen indicator'),
    ('oxy_press',-1,   0.120, -0.440, ('game', 'FILLER_OXYGEN', 0.058), 'oxygen pressure'),
    ('gear',     -1,   0.060, -0.530, ('paint_rect', 0.080, 0.057), 'gear indicator'),
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
                x, yy = to_px(-0.10 + k * 0.20, -0.600)
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
    """the ASP-3N's aft face: the reticle unit's window, the brightness / base / range knobs"""
    S, Hh = im.width, im.height
    H.noisy(d, im, DGREY, 4, seed=9)
    d.rectangle([S * .22, Hh * .12, S * .78, Hh * .62], fill=(16, 22, 26, 255), outline=(90, 90, 90, 255), width=4)
    d.ellipse([S * .38, Hh * .2, S * .62, Hh * .54], fill=(34, 48, 58, 255))
    for k, lab in enumerate(['ЯРК', 'БАЗА', 'ДАЛЬН']):  # ASP-3N
        x = S * (0.2 + k * 0.3)
        d.ellipse([x - 14, Hh * .74 - 14, x + 14, Hh * .74 + 14], fill=(22, 22, 22, 255), outline=(140, 140, 140, 255),
                  width=3)
        H.text(d, (x, Hh * .93), lab, Hh * .09)


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

    # -- ASP-3N gunsight on the coaming: the body, its aft face, the reflector frame (no glass)
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
    g_bot, g_top = g_c - g_up * g_h / 2, g_c + g_up * g_h / 2
    body_top = base_y + 0.07                                       # the sight head's own height
    body_c = np.array([E[0], (base_y + body_top) / 2, E[2] + PANEL_Z + 0.11])
    m.box(body_c, (X, Y, Z), (0.065, (body_top - base_y) / 2, 0.10), sw['dgrey'], front=(2, -1, sight, -X, Y))
    if g_bot[1] - 0.006 > body_top + 0.003:                        # two thin posts up to the glass's lower clamp
        for s in (-1, 1):
            m.box([E[0] + s * (g_hw + 0.004), (body_top + g_bot[1] - 0.006) / 2, g_bot[2]], (X, Y, Z),
                  (0.004, (g_bot[1] - 0.006 - body_top) / 2, 0.005), sw['dgrey'])
    m.box(g_bot - g_up * 0.005, (X, g_up, g_n), (g_hw + 0.008, 0.005, 0.010), sw['dgrey'])
    for s in (-1, 1):
        m.box(g_c + X * s * (g_hw + 0.004), (X, g_up, g_n), (0.004, g_h / 2, 0.005), sw['dgrey'])
    m.box(g_top + g_up * 0.004, (X, g_up, g_n), (g_hw + 0.008, 0.004, 0.005), sw['dgrey'])
    hud = dict(centre=g_c, normal=-g_n, up=g_up, width=2 * g_hw, height=g_h, style='GYRO')

    # -- consoles over the fuselage's sloping sides (those sides are the cockpit walls: no walls of our own)
    sw['wall_d'] = atlas.swatch(WALLC_D)
    for side in (1, -1):                                            # +1 = left (x > 0)
        xi, xo = E[0] + CONSOLE_IN * side, E[0] + CONSOLE_OUT * side
        cy, fy = E[1] + CONSOLE_Y, E[1] + FLOOR_Y + 0.02
        zb, zf = E[2] + BACK_Z + 0.06, E[2] + PANEL_Z + 0.08     # the consoles run on under the panel to the bulkhead
        con = atlas.paint(72, 300, H.p_console(WALLC, 20 + side, [0.11, 0.08, 0.13, 0.07, 0.12, 0.09, 0.10, 0.08] if side > 0
                                               else [0.09, 0.12, 0.08, 0.11, 0.10, 0.09, 0.13, 0.08],
                                               slot=(0.30, 0.30, 0.42, 0.62) if side > 0 else None))
        top = [[xi, cy, zb], [xo, cy, zb], [xo, cy, zf], [xi, cy, zf]]
        m.face(top, [m.uv(con, 0, 0), m.uv(con, 1, 0), m.uv(con, 1, 1), m.uv(con, 0, 1)], normal=[0, 1, 0])
        m.flat([[xi, fy, zb], [xi, fy, zf], [xi, cy, zf], [xi, cy, zb]], sw['wall_d'], normal=[-side, 0, 0])
        m.flat([[xi, fy, zb], [xo, fy, zb], [xo, cy, zb], [xi, cy, zb]], sw['wall_d'], normal=[0, 0, -1])
        m.flat([[xi, fy, zf], [xo, fy, zf], [xo, cy, zf], [xi, cy, zf]], sw['wall_d'], normal=[0, 0, 1])
        # a grey-green lining over the sloping side, from the glass line down to the console
        xs, xc = E[0] + 0.355 * side, E[0] + (CONSOLE_OUT - 0.01) * side
        ys, yc = E[1] + SILL_Y - 0.01, E[1] + CONSOLE_Y + 0.005
        lining = atlas.paint(150, 60, H.p_wall(WALLC_D, WALLC, 40 + side))
        zl = E[2] + PANEL_Z + 0.08                                  # the lining runs on to the bulkhead
        q = [[xc, yc, zb - 0.06], [xc, yc, zl], [xs, ys, zl], [xs, ys, zb - 0.06]]
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
    def PF(x, y, z):                                                 # on this model's floor
        return P(x, y + FLOOR_Y + 1.09, z)

    def PS(x, y, z):                                                 # the seat: only as high as the floor needs
        return P(x, y + max(0.0, FLOOR_Y + 0.97), z)

    # the bulkhead just behind the main panel, the fuselage's own cross-section there: no skin shows round the panel
    half = [(0.37, -0.25), (0.66, -0.56), (0.66, -1.0), (0.0, -1.0)]
    ring_ = [(x, y) for x, y in half] + [(-x, y) for x, y in reversed(half)]
    m.flat([P(x, y, PANEL_Z + 0.08) for x, y in ring_], atlas.swatch((28, 30, 30)), normal=[0, 0, -1])
    # rudder pedals under the panel
    for s in (1, -1):
        m.box(PF(s * 0.12, -0.97, 0.56), (X, H.unit([0, 1, -0.35]), H.unit([0, 0.35, 1])), (0.045, 0.07, 0.01), sw['mgrey'])
        m.box(PF(s * 0.12, -1.03, 0.60), (X, Y, Z), (0.012, 0.03, 0.06), sw['dgrey'])

    # -- throttle (RUD) on the left console
    tz = 0.20
    m.box(P(0.335, CONSOLE_Y + 0.055, tz), (X, H.unit([0, 1, 0.25]), H.unit([0, -0.25, 1])), (0.009, 0.06, 0.009), sw['metal'])
    grip_ax = (X, H.unit([0, 1, 0.3]), H.unit([0, -0.3, 1]))
    m.box(P(0.335, CONSOLE_Y + 0.135, tz + 0.02), grip_ax, (0.03, 0.045, 0.04), sw['black'])
    m.box(P(0.335, CONSOLE_Y + 0.185, tz + 0.03), grip_ax, (0.026, 0.01, 0.032), sw['dgrey'])
    m.box(P(0.312, CONSOLE_Y + 0.15, tz + 0.045), grip_ax, (0.004, 0.008, 0.008), sw['red'])

    # -- the MiG-15's ejection seat (the MiG-29 seat's dimensions: the floor sits at the same depth under the eye)
    cushion = atlas.paint(64, 64, H.p_cushion(CUSHION))
    back = atlas.paint(64, 96, H.p_cushion(CUSHION, straps=True))
    headbox = atlas.paint(64, 64, p_headbox)
    stripes = atlas.paint(64, 16, H.p_stripes)
    ax_b = (X, H.unit([0, 1, -0.14]), H.unit([0, 0.14, 1]))
    m.box(PS(0, -1.002, -0.004), (X, Y, Z), (0.196, 0.08, 0.22), sw['frame'])
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
    m.box(PF(0, -0.99, sz), (X, Y, Z), (0.045, 0.10, 0.045), sw['dgrey'])
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
    foot = PF(0, -0.92, sz)
    return m, (stick, foot), gauges, displays, hud


def old_interior(E):
    """boxes (vehicle-local lo, hi) of the old cockpit: the tub inside the glass line above the floor from behind the
    seat to the old panel block, the old consoles over the sloping sides (whose skin stays), the old sight"""
    e = np.array(E)
    return [(e + [-0.36, -1.00, BACK_Z], e + [0.36, SILL_Y - 0.005, 0.86]),
            (e + [-0.78, -1.03, BACK_Z - 0.05], e + [0.78, -0.59, 0.88]),
            (e + [-0.24, -0.25, 0.30], e + [0.24, -0.10, 0.88])]


def main(argv):
    dry = '--dry-run' in argv
    ac = L.Aircraft(VID)
    E = ac.eye(0)
    removed = H.remove_faces(ac, old_interior(E))
    removed['old sight in the view'] = H.remove_sight_clutter(ac, E)
    HUD_TOP[0] = H.view_ceiling(ac, ac.eye(0), PANEL_Z + 0.06)
    atlas = H.A.Atlas(VID)
    mesh, stick, gauges, displays, hud = build(atlas, E)
    pm = H.install(ac, mesh, stick, SOURCE_BONE, STICK_BONE, gauges, displays, hud, E)
    print(f'{VID}: removed {removed}; new cockpit {len(pm["polys"])} faces, {len(gauges)} gauges')
    if not dry:
        H.write(ac, atlas)


if __name__ == '__main__':
    main(sys.argv[1:])
