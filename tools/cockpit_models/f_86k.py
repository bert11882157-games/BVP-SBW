#!/usr/bin/env python3
"""North American F-86K Sabre cockpit, built by hand for this model (as f_86k.py), after the real panel.

Measured off the model with sections.py (all heights and distances from the pilot's eye at (0, 2.5283, 3.0240);
1 block = 1 m on this model; it has no canopy glass of its own, only an old frame):
  * the canopy's sill at y -0.27, x +-0.31 beside the eye; the old consoles' tops at y -0.65; the floor at y -1.18;
    the old panel about 0.55 m ahead; the windscreen's arch 0.50 m ahead, its base frame 1.02 m ahead;
    the sliding canopy's rails running back over the spine to 1.10 m behind the eye.
Built:
  * a three-facet black main panel 0.58 m ahead: the attitude indicator at the top centre over the heading indicator,
    the Machmeter / climb / clock / fuel column right of them, the altimeter left, the APG-37 radar scope in its hood
    low in the centre; airspeed, g-meter and the gear handle on the left wing; rpm, jet pipe temperature and the
    warning panel on the right wing; English placards and lamp legends;
  * the black coaming forward to the windscreen, the gunsight on it;
  * side walls and consoles, rudder pedals, the throttle on the left console, the seat and the control stick (into
    the stick bone).
Instruments the game draws (CockpitGauges, FlightDisplays, HeadUpDisplay) are live. No glass.

Run on the committed originals (git checkout be357476 -- the f_86k geo, asset and both textures first).
Usage: python3 tools/cockpit_models/f_86k.py [--dry-run]
"""
import math, os, sys
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import cockpit_lib as L   # noqa: E402
import handbuilt as H     # noqa: E402

VID = 'f_86k'
SOURCE_BONE = 'wreck_fuselage_0__hull'
STICK_BONE = 'wreck_fuselage_0__control_stick_pitch'

TURQ = (40, 42, 44)            # the black-grey panels
TURQ_D = (34, 36, 38)
BLACK = (24, 25, 26)
DGREY = (44, 46, 48)
MGREY = (88, 92, 94)
FRAME = (52, 56, 54)           # the seat's dark grey structure
CUSHION = (74, 70, 54)         # its olive-brown cushions
HEADPAD = (92, 96, 88)
YELLOW = (226, 186, 30)
RED = (196, 36, 30)
METAL = (128, 132, 134)
FLOOR = (46, 50, 50)

# ---------------------------------------------------------------- the measured cockpits (metres from each eye)
# one entry per crew station; use() makes a station's measurements the module's working constants for build()
STATIONS = {
    'front': dict(SEAT=0, WALL_X=0.270, SILL_Y=-0.33, FLOOR_Y=-1.18, BACK_Z=-0.45, CONSOLE_Y=-0.65, CONSOLE_IN=0.234,
                  PANEL_Z=0.58, LEAN=math.radians(12), HINGE=0.17, WING=math.radians(25), TOP_C=-0.300, TOP_W=-0.340,
                  BOT_C=-0.800, FRONT_Z=0.98, FRONT_Y_C=-0.300, FRONT_Y_W=-0.330, FRONT_X=0.160),
}


def use(name):
    g = globals()
    g.update(STATIONS[name])
    g['STATION'] = name
    g['BOT_W'] = g['CONSOLE_Y']
    g['WING_LEN'] = (g['WALL_X'] - g['HINGE']) / math.cos(g['WING'])
    g['LAYOUT'] = LAYOUT_FRONT if name == 'front' else LAYOUT_REAR
    g['R_C'] = np.array([-1.0, 0, 0])                                   # the crewman's right
    g['U_C'] = np.array([0, math.cos(LEAN), math.sin(LEAN)])
    g['N_C'] = np.array([0, math.sin(LEAN), -math.cos(LEAN)])           # toward him


def facets(E):
    o = E + np.array([0, (TOP_C + BOT_C) / 2, PANEL_Z])
    centre = H.Facet(o, R_C, U_C, N_C)
    wings = {}
    for side in (1, -1):                                       # +1: the right wing
        R = R_C * side * math.cos(WING) + N_C * math.sin(WING)
        N = N_C * math.cos(WING) - R_C * side * math.sin(WING)
        hinge = o + R_C * side * HINGE
        f = H.Facet(hinge, R, U_C, N)                          # a: metres outward from the hinge
        wings[side] = f
    return centre, wings[1], wings[-1]


def top_of(side, a):
    return TOP_C if side == 0 else TOP_C + (TOP_W - TOP_C) * a / WING_LEN


# The pilot's panel. facet: 0 centre (a: metres to the pilot's right of the centre line), +1 right wing, -1 left
# wing (a: metres outward from the hinge). y: from the eye.
LAYOUT_FRONT = [
    # centre facet
    ('caution',  0, -0.140, -0.320, ('paint_disc', 0.022), 'master warning lamp'),
    ('adi',      0, -0.010, -0.385, ('game', 'ATTITUDE', 0.085), 'attitude indicator'),
    ('hsi',      0, -0.010, -0.500, ('game', 'HEADING', 0.080), 'heading indicator'),
    ('mach',     0,   0.112, -0.370, ('game', 'MACH', 0.046), 'Machmeter'),
    ('vvi',      0,   0.112, -0.445, ('game', 'VVI', 0.046), 'vertical speed'),
    ('clock',    0,   0.112, -0.520, ('game', 'CLOCK', 0.042), 'clock'),
    ('fuel',     0,   0.112, -0.600, ('game', 'FUEL_QTY', 0.046), 'fuel quantity'),
    ('alt2',     0, -0.128, -0.430, ('game', 'ALTIMETER', 0.050), 'altimeter'),
    ('radar',    0, -0.010, -0.640, ('display', 'RADAR', 0.100), 'APG-37 radar scope'),
    # left wing
    ('ias',     -1,   0.062, -0.395, ('game', 'AIRSPEED', 0.054), 'airspeed'),
    ('g',       -1,   0.062, -0.475, ('game', 'G_METER', 0.044), 'accelerometer'),
    ('gear',    -1,   0.065, -0.585, ('paint_rect', 0.036, 0.075), 'gear control panel'),
    # right wing
    ('rpm',      1,   0.062, -0.395, ('game', 'ENGINE_RPM', 0.052), 'J47 rpm'),
    ('egt',      1,   0.062, -0.475, ('game', 'EGT', 0.046), 'jet pipe temperature'),
    ('warning',  1,   0.065, -0.585, ('annunciator', 0.100, 0.055), 'warning panel'),
]
LAYOUT_REAR = []


# ---------------------------------------------------------------- painted faces

def p_weapons(d, im):
    S, Hh = im.width, im.height
    d.rectangle([0, 0, S, Hh], fill=(20, 22, 22, 255))
    labs = ['GUNS', 'R/P', 'BOMBS', 'STORE', 'SALVO', 'JETT']
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
    H.text(d, (S / 2, Hh * .55), 'U/C', S * .16)
    d.rectangle([S * .4, Hh * .62, S * .6, Hh * .95], fill=(140, 140, 140, 255))


def p_afcs(d, im):
    S, Hh = im.width, im.height
    d.rectangle([0, 0, S, Hh], fill=(20, 22, 22, 255))
    labs = ['DÄMP', 'ROLL', 'HÖJD', 'ÅTERST', 'FRÅN']
    for k, lab in enumerate(labs):
        w = S / len(labs)
        H.lamp(d, [k * w + 6, Hh * .14, (k + 1) * w - 6, Hh * .86], (210, 210, 200) if k < 4 else (200, 60, 50),
               lab, lit=(k == 0), px=Hh * .22)


def p_radctl(d, im):
    """the radar operator's control panel: the mode switch, the range and gain knobs, the lock-on push-lights"""
    S, Hh = im.width, im.height
    d.rectangle([0, 0, S, Hh], fill=(20, 22, 22, 255))
    for k, lab in enumerate(['FÖRST', 'AVST', 'LJUS', 'TILT']):
        x, y, r = S * (0.10 + k * 0.16), Hh * .42, Hh * .22
        for a in range(-120, 121, 40):
            t_ = math.radians(a)
            d.line([x + r * 1.2 * math.sin(t_), y - r * 1.2 * math.cos(t_), x + r * 1.45 * math.sin(t_),
                    y - r * 1.45 * math.cos(t_)], fill=(210, 210, 200, 255), width=2)
        d.ellipse([x - r, y - r, x + r, y + r], fill=(10, 10, 11, 255), outline=(110, 110, 110, 255), width=2)
        d.line([x, y, x + r * .7 * math.sin(.4 * k + .3), y - r * .7 * math.cos(.4 * k + .3)], fill=(235, 235, 225, 255),
               width=3)
        H.text(d, (x, Hh * .88), lab, Hh * .14)
    for k, lab in enumerate(['SÖK', 'LÅS', 'FRÅN']):
        x0 = S * (0.70 + k * 0.10)
        H.lamp(d, [x0 - S * .04, Hh * .2, x0 + S * .04, Hh * .62], (210, 210, 200) if k < 2 else (200, 60, 50), None,
               lit=(k == 0))
        H.text(d, (x0, Hh * .86), lab, Hh * .14)


def p_disc_red(d, im):
    S = im.width
    d.ellipse([0, 0, S - 1, S - 1], fill=(120, 18, 14, 255))
    d.ellipse([S * .18, S * .14, S * .55, S * .4], fill=(220, 90, 80, 255))


def p_sight(d, im):
    """the sight's aft face: the reticle unit's window, the brightness / range / caging knobs"""
    S, Hh = im.width, im.height
    H.noisy(d, im, DGREY, 4, seed=9)
    d.rectangle([S * .22, Hh * .12, S * .78, Hh * .62], fill=(16, 22, 26, 255), outline=(90, 90, 90, 255), width=4)
    d.ellipse([S * .38, Hh * .2, S * .62, Hh * .54], fill=(34, 48, 58, 255))
    for k, lab in enumerate(['DIM', 'RANGE', 'CAGE']):
        x = S * (0.2 + k * 0.3)
        d.ellipse([x - 14, Hh * .74 - 14, x + 14, Hh * .74 + 14], fill=(22, 22, 22, 255), outline=(140, 140, 140, 255),
                  width=3)
        H.text(d, (x, Hh * .93), lab, Hh * .09)


def p_headbox(d, im):
    S, Hh = im.width, im.height
    H.noisy(d, im, HEADPAD, 5, seed=60)
    d.rectangle([S * .1, Hh * .1, S * .9, Hh * .9], outline=(60, 62, 58, 255), width=6)
    H.text(d, (S * .5, Hh * .5), 'NAA', Hh * .2, fill=(40, 40, 40), bold=True)


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

    # -- the main panel: three facets, each a 2 cm slab, the face the plain panel swatch (the recesses behind the
    # instruments and the plate seams are modelled below)
    for side in (0, -1, 1):
        a0, a1 = ext[side]
        yt = max(top_of(side, a0), top_of(side, a1)); yb = bot[side]
        rect = sw['turq']
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

    # -- the recesses behind the instruments and the plate seams, modelled on the face
    recess = atlas.swatch((20, 26, 26))
    for name, side, a, y, spec, _ in LAYOUT:
        f = fac[side]; c = at(side, a, y)
        if spec[0] in ('game', 'paint_disc'):
            m.disc(c + f.N * 0.0028, f.N, f.U, spec[-1] / 2 * (1 + H.RIM) + 0.004, recess, seg=16)
        else:
            w, h = (spec[2], spec[2]) if spec[0] == 'display' else (spec[1], spec[2])
            m.rect_tex(c + f.N * 0.0028, f.N, f.U, w + 0.012, h + 0.012, recess)
    seams = ((0, (-0.68,)), (-1, (-0.42,)), (1, (-0.425,))) if SEAT == 0 else ((0, (-0.45,)), (-1, (-0.25,)), (1, (-0.25,)))
    for side, ys in seams:
        a0, a1 = ext[side]
        for y in ys:
            p0, p1 = at(side, a0 + 0.004, y), at(side, a1 - 0.004, y)
            n = fac[side].N * 0.0015; u = fac[side].U * 0.0015
            m.flat([p0 - u + n, p1 - u + n, p1 + u + n, p0 + u + n], sw['turq_d'], normal=fac[side].N)
    # -- instruments
    cols, lamps = L.lamps_of('cw', 'raf')            # (English legends)
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
            painter = {'weapons': p_weapons, 'gear': p_gear, 'afcs': p_afcs, 'radctl': p_radctl}[name]
            ph = 64 if h >= w else max(16, int(round(120 * h / w)))
            pw = 120 if w >= h else max(16, int(round(64 * w / h)))
            rect = atlas.paint(pw, ph, painter)
            m.rect_tex(c + f.N * 0.004, f.N, f.U, w, h, rect)
            m.frame_rect(c, f.N, f.U, w, h, 0.004, 0.006, sw['bezel'])
            if name == 'gear':                                     # the gear lever stands out of its panel
                m.box(c + f.N * 0.04 - f.U * 0.02, (f.R, f.U, f.N), (0.006, 0.006, 0.035), sw['metal'])
                m.box(c + f.N * 0.075 - f.U * 0.02, (f.R, f.U, f.N), (0.012, 0.012, 0.010), sw['red'])

    # -- coaming: lofted from the panel lip forward (the pilot's to the windscreen base, the operator's to the pilot's
    # seat back)
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

    # -- the pilot's gyro gunsight on the coaming: the body, its aft face, the reflector frame (no glass)
    hud = None
    if SEAT == 0:
        sight = atlas.paint(96, 64, p_sight)
        g_h, g_hw = 0.110, 0.070
        lean = math.radians(35)
        g_up = np.array([0, math.cos(lean), -math.sin(lean)])
        g_n = H.unit(np.cross(X, g_up))
        base_y = E[1] + TOP_C + 0.045                                  # the coaming's top
        g_c = np.array([E[0], E[1], E[2] + PANEL_Z + 0.10])
        g_c, g_h = H.fit_sight_under(VID, E, g_c, g_up, g_h, g_hw)   # (the reflector under the windscreen glass)
        g_bot, g_top = g_c - g_up * g_h / 2, g_c + g_up * g_h / 2
        body_top = base_y + 0.07
        body_c = np.array([E[0], (base_y + body_top) / 2, E[2] + PANEL_Z + 0.08])
        m.box(body_c, (X, Y, Z), (0.075, (body_top - base_y) / 2, 0.08), sw['dgrey'], front=(2, -1, sight, -X, Y))
        if g_bot[1] - 0.006 > body_top + 0.003:                        # two thin posts up to the glass's lower clamp
            for s in (-1, 1):
                m.box([E[0] + s * (g_hw + 0.004), (body_top + g_bot[1] - 0.006) / 2, g_bot[2]], (X, Y, Z),
                      (0.004, (g_bot[1] - 0.006 - body_top) / 2, 0.005), sw['dgrey'])
        H.combiner(m, g_bot, g_up, -g_n, 2 * g_hw, g_h, 'chamfer', 0.025, ('bottom', 'side'), 0.008, sw['dgrey'])
        hud = dict(centre=g_c, normal=-g_n, up=g_up, width=2 * g_hw, height=g_h, style='GYRO')

    # -- side walls, consoles, the closing faces under the panel
    for side in (1, -1):                                            # +1 = left (x > 0)
        xw = E[0] + WALL_X * side
        wall = atlas.paint(120, 60, H.p_wall(TURQ_D, TURQ, 30 + side + 10 * SEAT))
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
        con = atlas.paint(240, 36, H.rotated(H.p_console(TURQ, 20 + side + 10 * SEAT,
                                                       [0.11, 0.08, 0.13, 0.07, 0.12, 0.09, 0.10, 0.08] if side > 0
                                                       else [0.09, 0.12, 0.08, 0.11, 0.10, 0.09, 0.13, 0.08],
                                                       slot=(0.44, 0.30, 0.56, 0.62) if side > 0 and SEAT == 0 else None)))
        top = [[xi, cy, zb], [xo, cy, zb], [xo, cy, zf_o], [xi, cy, zf_i]]
        m.face(top, [m.uv(con, 1, 0), m.uv(con, 1, 1), m.uv(con, 0, 1), m.uv(con, 0, 0)], normal=[0, 1, 0])
        m.flat([[xi, fy, zb], [xi, fy, zf_i], [xi, cy, zf_i], [xi, cy, zb]], sw['turq_d'], normal=[-side, 0, 0])
        m.flat([[xi, fy, zb], [xo, fy, zb], [xo, cy, zb], [xi, cy, zb]], sw['turq_d'], normal=[0, 0, -1])
        m.box([xw - side * 0.015, E[1] + SILL_Y - 0.005, E[2] + (BACK_Z + PANEL_Z - 0.1) / 2], (X, Y, Z),
              (0.03, 0.01, (PANEL_Z - 0.1 - BACK_Z) / 2), sw['dgrey'])                     # canopy rail ledge
    # under the centre facet: its lower closing face back to the footwell
    bl, br = centre.at(HINGE, E[1] + BOT_C), centre.at(-HINGE, E[1] + BOT_C)
    m.flat([bl, br, br + np.array([0, 0, 0.12]), bl + np.array([0, 0, 0.12])], sw['turq_d'], normal=[0, -1, 0])
    for s in ((1, -1) if CONSOLE_IN - HINGE > 0.02 else ()):   # the footwell sides under the wings
        e = centre.at(-HINGE * s, E[1] + BOT_C)
        m.flat([e, centre.at(-HINGE * s, E[1] + BOT_W), [E[0] + s * CONSOLE_IN, E[1] + BOT_W, e[2]],
                [E[0] + s * CONSOLE_IN, E[1] + BOT_C, e[2]]], sw['turq_d'], normal=[0, 0, -1])
    # the floor: a dark plate over the model's own floor, from behind the seat to under the panel
    fw = CONSOLE_IN + 0.04
    m.flat([P(-fw, FLOOR_Y + 0.03, BACK_Z), P(fw, FLOOR_Y + 0.03, BACK_Z),
            P(fw, FLOOR_Y + 0.03, PANEL_Z + (0.30 if SEAT == 0 else 0.0)),
            P(-fw, FLOOR_Y + 0.03, PANEL_Z + (0.30 if SEAT == 0 else 0.0))], sw['floor'], normal=[0, 1, 0])

    if SEAT == 0:
        # rudder pedals under the panel
        for s in (1, -1):
            m.box(P(s * 0.12, -0.85, PANEL_Z + 0.08), (X, H.unit([0, 1, -0.35]), H.unit([0, 0.35, 1])), (0.045, 0.07, 0.01),
                  sw['mgrey'])
            m.box(P(s * 0.12, -0.91, PANEL_Z + 0.12), (X, Y, Z), (0.012, 0.03, 0.06), sw['dgrey'])
        # the throttle on the left console
        tz = 0.20
        tx = CONSOLE_IN + 0.026                                    # (the narrow console's middle)
        m.box(P(tx, CONSOLE_Y + 0.055, tz), (X, H.unit([0, 1, 0.25]), H.unit([0, -0.25, 1])), (0.009, 0.06, 0.009), sw['metal'])
        grip_ax = (X, H.unit([0, 1, 0.3]), H.unit([0, -0.3, 1]))
        m.box(P(tx, CONSOLE_Y + 0.135, tz + 0.02), grip_ax, (0.03, 0.045, 0.04), sw['black'])
        m.box(P(tx, CONSOLE_Y + 0.185, tz + 0.03), grip_ax, (0.026, 0.01, 0.032), sw['dgrey'])
        m.box(P(tx - 0.023, CONSOLE_Y + 0.15, tz + 0.045), grip_ax, (0.004, 0.008, 0.008), sw['red'])

    # -- the Saab ejection seat (the MiG-29 seat's dimensions), on this model's floor
    cushion = atlas.paint(64, 64, H.p_cushion(CUSHION))
    back = atlas.paint(48, 64, H.p_cushion(CUSHION, straps=True))
    headbox = atlas.paint(64, 64, p_headbox)
    stripes = atlas.paint(64, 16, H.p_stripes)
    ax_b = (X, H.unit([0, 1, -0.14]), H.unit([0, 0.14, 1]))
    base_top = -0.922
    m.box(P(0, (base_top + FLOOR_Y + 0.03) / 2, -0.004), (X, Y, Z), (0.196, (base_top - FLOOR_Y - 0.03) / 2, 0.22), sw['frame'])
    m.box(P(0, -0.887, 0.026), (X, Y, Z), (0.19, 0.035, 0.21), sw['cushion'], front=(1, 1, cushion, -X, Z))
    for s in (1, -1):
        m.box(P(s * 0.212, -0.882, -0.014), (X, Y, Z), (0.012, 0.15, 0.24), sw['frame'])
        m.box(P(s * 0.17, -0.44, -0.404), ax_b, (0.02, 0.54, 0.025), sw['frame'])   # guide beams, their tops under the glass
    m.box(P(0, -0.502, -0.234), ax_b, (0.19, 0.33, 0.03), sw['cushion'], front=(2, 1, back, X, ax_b[1]))
    m.box(P(0, -0.502, -0.294), ax_b, (0.205, 0.36, 0.035), sw['frame'])
    m.box(P(0, -0.03, -0.314), (X, Y, Z), (0.16, 0.15, 0.10), sw['frame'])   # headbox, under the glass
    m.box(P(0, -0.02, -0.199), (X, Y, Z), (0.11, 0.09, 0.015), sw['cushion'], front=(2, 1, headbox, X, Y))
    for s in (1, -1):                                                # the seat-pan firing handle, yellow and black
        m.box(P(s * 0.05, -0.817, 0.256), (X, Y, Z), (0.008, 0.035, 0.0075), sw['yellow'], front=(2, 1, stripes, X, Y))
    m.box(P(0, -0.782, 0.256), (X, Y, Z), (0.058, 0.008, 0.008), sw['yellow'], front=(2, 1, stripes, X, Y))

    # -- the pilot's control stick: the boot stays with the cockpit, the rest goes into the stick bone
    if SEAT != 0:
        return m, None, gauges, displays, hud
    sz = 0.320                                                      # (the stick bone's pivot goes to its foot)
    m.box(P(0, (FLOOR_Y + 0.03 - 0.89) / 2, sz), (X, Y, Z), (0.045, (-0.89 - FLOOR_Y - 0.03) / 2, 0.045), sw['dgrey'])
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


def old_interior(E0):
    """boxes (vehicle-local lo, hi) of the old cockpit: the tub inside the sill above the floor, the old consoles under
    the sloping sides, the old panel, coaming and sight up to the windscreen"""
    f = np.array(E0)
    return [(f + [-0.27, -1.17, -0.45], f + [0.27, -0.34, 0.55]),           # the tub, the old seat
            (f + [-0.37, -1.17, -0.45], f + [0.37, -0.60, 0.55]),           # the old consoles out under the sides
            (f + [-0.22, -0.45, 0.40], f + [0.22, 0.05, 1.05]),             # the old panel top and sight
            (f + [-0.25, -1.17, 0.30], f + [0.25, -0.40, 1.15])]            # the old panel and footwell


def repaint_walls(ac, atlas, E):
    """the model's old cockpit side walls outboard of the new walls, under the sills (light there): repainted in the
    panel's dark grey instead of removed (they close the fuselage; its outer skin, further out, faces out and stays)"""
    from PIL import Image
    tex = Image.open(L.texture_path(VID)).convert('RGB')
    TW, TH = tex.size
    dark = atlas.swatch(TURQ_D)
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
            if not (-0.45 < c[2] < 0.70 and 0.27 < abs(c[0]) < 0.42 and -1.17 < c[1] < -0.36):
                continue
            u, v = np.mean([pm['uvs'][q[2]] for q in poly], axis=0)
            r, g, bl = tex.getpixel((min(int(u * TW), TW - 1), min(int((1 - v) * TH), TH - 1)))
            V = P[[v[0] for v in poly]]
            nrm = sum(np.cross(V[k] - c, V[(k + 1) % len(V)] - c) for k in range(len(V)))
            inward = nrm[0] * c[0] < -0.5 * np.linalg.norm(nrm) * abs(c[0])     # (the outer skin faces out: it stays)
            if r + g + bl > 150 and inward:
                pm['uvs'].append(atlas.flat(dark))
                k = len(pm['uvs']) - 1
                for q in poly:
                    q[2] = k
                n += 1
    return n


def main(argv):
    dry = '--dry-run' in argv
    ac = L.Aircraft(VID)
    E0 = ac.eye(0)
    removed = H.remove_faces(ac, old_interior(E0))
    removed['old sight in the view'] = H.remove_sight_clutter(ac, E0)
    atlas = H.A.Atlas(VID)
    removed['old walls repainted'] = repaint_walls(ac, atlas, E0)
    use('front')
    mesh, stick, gauges, displays, hud = build(atlas, E0)
    pm = H.install(ac, mesh, stick, SOURCE_BONE, STICK_BONE, gauges, displays, hud, E0)
    print(f'{VID}: removed {removed}; new cockpit {len(pm["polys"])} faces, {len(gauges)} gauges')
    if not dry:
        H.write(ac, atlas)


if __name__ == '__main__':
    main(sys.argv[1:])
