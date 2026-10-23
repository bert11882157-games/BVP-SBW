#!/usr/bin/env python3
"""Su-15TM cockpit, built by hand for this model (as mig_25pd.py, mig_23mld.py, su_27.py), from the real panel layout.

Measured off the model (the faces of Su-15T.mtb in checks/orig, work/elite_expansion_2/su15/NOTES.md; all heights and
distances from the pilot's eye at (0, 2.5842, 2.6899); 1 block = 1 m on this model):
  * a narrow cockpit: the sill skin's inner wall stands at x 0.317 from the ledge (y 2.018, 0.57 m under the eye) up to the
    sill top (y 2.29, -0.294); below the ledge the tub is 0.453 wide (hidden under the ledge); the floor at y 1.519
    (-1.065); the old console boxes' tops at y 1.884 (-0.70);
  * the old panel (boxes with painted dials) stood 0.50 .. 0.64 m ahead of the eye; the windscreen's base on the nose deck
    is 1.055 m ahead (y 2.37, -0.21); the model's seat group: back z -0.32, headrest face z -0.27, head top +0.13.
Built:
  * a three-facet main panel 0.56 m ahead, 12 degrees back, its wings turned 25 degrees toward the pilot to the walls:
    the Taifun-M (Smerch) radar scope, the round tube in its rubber hood, at the top centre, over the KPP attitude director
    and NPP horizontal situation (left), the Mach / airspeed and the altimeter (right), VVI and AoA between them, turn and
    slip, fuel; airspeed, accelerometer, the weapons panel and the gear panel on the left wing; the twin tachometer,
    exhaust gas temperature, hydraulics, oxygen, SPO-10 radar warning and the warning-light block on the right wing (the
    engine instruments: two R-13F2-300);
  * the black coaming forward to the windscreen's base bar (1.055 m ahead of the eye);
  * the PKI reflector sight on the coaming (HeadUpDisplay Style REFLECTOR, seat 0; the Su-15TM had no head-up display);
  * low side consoles with switch panels, side walls, rudder pedals, two throttle levers on the left console, a KS-4
    ejection seat and the control stick (into the stick bone).
Seat: the Su-15 family flew on the KS-4 (a Soviet seat with a headrest, a face-blind handle above the head and the
armoured back; the airframe's model carries an unnamed seat group, which is replaced here): UNVERIFIED for the TM
specifically (Decision taken in the report).
Instruments the game draws (CockpitGauges, FlightDisplays, HeadUpDisplay) are live. The canopy glass is canopy_su_15tm.py's.

Run on the airframe's originals (su15/run_su15_cockpit.ps1 restores them), after canopy_su_15tm.py.
Usage: python3 tools/cockpit_models/su_15tm.py [--dry-run]
"""
import json, math, os, sys
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import cockpit_lib as L   # noqa: E402
import handbuilt as H     # noqa: E402

VID = 'su_15tm'
SOURCE_BONE = 'wreck_fuselage_1__hull'
STICK_BONE = 'wreck_fuselage_0__control_stick_pitch'

TURQ = (80, 122, 124)          # the Soviet grey-green / turquoise of the panel
TURQ_D = (54, 84, 86)
BLACK = (24, 25, 26)
DGREY = (44, 46, 48)
MGREY = (88, 92, 94)
FRAME = (62, 68, 64)           # seat structure
CUSHION = (58, 62, 52)
HEADPAD = (92, 96, 88)
YELLOW = (226, 186, 30)
RED = (196, 36, 30)
METAL = (128, 132, 134)
FLOOR = (46, 50, 50)

# ---------------------------------------------------------------- the measured cockpit (metres from the eye)
WALL_X = 0.310                 # the side walls and consoles' outer edge (the sill skin's inner face is at 0.317)
SILL_Y = -0.294                # the sill's top
FLOOR_Y = -1.06
BACK_Z = -0.60                 # behind the seat (the spine fairing's front face is at -0.658)
CONSOLE_Y = -0.70
CONSOLE_IN = 0.235
PANEL_Z = 0.56                 # centre facet reference plane
LEAN = math.radians(12)
HINGE = 0.13                   # centre facet half width
WING_X = 0.300                 # the panel wings end here
WING = math.radians(25)
WING_LEN = (WING_X - HINGE) / math.cos(WING)
TOP_C, TOP_W = -0.150, -0.310  # centre facet top, wing top at its end (under the sill)
BOT_C, BOT_W = -0.72, CONSOLE_Y
FRONT_Z = 1.052                # the coaming runs forward to the windscreen's base bar (rear face 1.055 ahead of the eye)
FRONT_Y_C, FRONT_Y_W = -0.285, -0.300
FRONT_X = 0.300                # the coaming's half width at the base bar

HUD_TOP = [0.06]               # how far above the eye the sight's glass may reach (the windscreen plate's underside there: +0.065)
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
        wings[side] = H.Facet(hinge, R, U_C, N)                # a: metres outward from the hinge
    return centre, wings[1], wings[-1]


def top_of(side, a):
    return TOP_C if side == 0 else TOP_C + (TOP_W - TOP_C) * a / WING_LEN


# Instrument layout of the Su-15TM front panel: the Taifun-M radar scope at the top centre, the pilot-navigation flight
# instruments (KPP, NPP) left, the Mach/airspeed and altitude dials right, the engine group on the right wing.
# facet: 0 centre (a: metres to the pilot's right of the centre line), +1 right wing, -1 left wing (a: outward from the
# hinge). y: from the eye.
LAYOUT = [
    # centre facet
    ('scope',    0,   0.000, -0.262, ('display', 'RADAR', 0.088), 'Taifun-M radar scope (round tube, hooded)'),
    ('caution',  0, -0.115, -0.205, ('paint_disc', 0.022), 'master caution lamp'),
    ('clock',    0,  0.108, -0.205, ('game', 'CLOCK', 0.030), 'AChS-1 clock'),
    ('p_radar',  0,   0.000, -0.418, ('plate', 0.150, 0.016, 'РЛС ТАЙФУН-М'), 'scope placard'),
    ('adi',      0, -0.072, -0.485, ('game', 'ATTITUDE', 0.070), 'KPP attitude director'),
    ('hsi',      0, -0.072, -0.563, ('game', 'HEADING', 0.068), 'NPP horizontal situation'),
    ('mach',     0,  0.072, -0.485, ('game', 'MACH', 0.070), 'Mach / true airspeed'),
    ('alt',      0,  0.072, -0.563, ('game', 'ALTIMETER', 0.068), 'VD-17 altimeter'),
    ('vvi',      0,  0.000, -0.470, ('game', 'VVI', 0.044), 'VAR-300 vertical speed'),
    ('aoa',      0,  0.000, -0.522, ('game', 'AOA', 0.040), 'UUA AoA'),
    ('turn',     0, -0.072, -0.640, ('game', 'TURN_SLIP', 0.046), 'EUP turn and slip'),
    ('fuel',     0,  0.072, -0.640, ('game', 'FUEL_QTY', 0.046), 'fuel quantity'),
    # left wing
    ('ias',     -1,   0.090, -0.340, ('game', 'AIRSPEED', 0.066), 'KUS airspeed'),
    ('g',       -1,   0.090, -0.430, ('game', 'G_METER', 0.046), 'accelerometer'),
    ('weapons', -1,   0.085, -0.525, ('paint_rect', 0.110, 0.055), 'weapons panel'),
    ('gear',    -1,   0.140, -0.610, ('paint_rect', 0.040, 0.085), 'gear control panel'),
    # right wing
    ('rpm',      1,   0.070, -0.335, ('game', 'ENGINE_RPM', 0.056), 'ITE-2T twin tachometer'),
    ('egt',      1,   0.140, -0.395, ('game', 'EGT', 0.046), 'exhaust gas temperature'),
    ('hyd',      1,   0.070, -0.420, ('game', 'FILLER_HYDRAULIC', 0.040), 'hydraulic pressures'),
    ('oxy',      1,   0.140, -0.470, ('game', 'FILLER_OXYGEN', 0.034), 'oxygen'),
    ('spo10',    1,   0.075, -0.515, ('game', 'RWR', 0.050), 'SPO-10 radar warning'),
    ('warning',  1,   0.095, -0.595, ('annunciator', 0.115, 0.065), 'warning lights'),
    ('p_eng',    1,   0.095, -0.660, ('plate', 0.115, 0.016, 'ДВИГАТЕЛИ'), 'engine placard'),
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
            elif spec[0] == 'plate':
                pass
            else:
                w, h = (spec[2], spec[2]) if spec[0] == 'display' else (spec[1], spec[2])
                sx, sy = (w / 2 + 0.006) * px_m, (h / 2 + 0.006) * px_m
                d.rectangle([x - sx, yy - sy, x + sx, yy + sy], fill=(20, 26, 26, 255))
    return fn


def p_weapons(d, im):
    S, Hh = im.width, im.height
    d.rectangle([0, 0, S, Hh], fill=(20, 22, 22, 255))
    labs = ['Р-98', 'Р-60', 'ПТБ', 'УПК']
    for k, lab in enumerate(labs):
        x = S * (0.14 + k * 0.24)
        H.lamp(d, [x - 18, Hh * .14, x + 18, Hh * .38], (210, 210, 200), None, lit=(k == 0))
        d.rectangle([x - 4, Hh * .48, x + 4, Hh * .70], fill=(190, 190, 190, 255))
        H.text(d, (x, Hh * .86), lab, Hh * .15)


def p_gear(d, im):
    S, Hh = im.width, im.height
    d.rectangle([0, 0, S, Hh], fill=(20, 22, 22, 255))
    for k in range(3):
        y = Hh * (0.10 + 0.10 * k)
        H.lamp(d, [S * .2, y, S * .8, y + Hh * .07], (60, 150, 70), None, lit=True)
    H.text(d, (S / 2, Hh * .55), 'ШАССИ', S * .17)
    d.rectangle([S * .4, Hh * .62, S * .6, Hh * .95], fill=(140, 140, 140, 255))


def p_disc_red(d, im):
    S = im.width
    d.ellipse([0, 0, S - 1, S - 1], fill=(120, 18, 14, 255))
    d.ellipse([S * .18, S * .14, S * .55, S * .4], fill=(220, 90, 80, 255))


def p_sight(d, im):
    """the PKI reflector sight's aft face: the reticle unit's window, the brightness / range / cage knobs"""
    S, Hh = im.width, im.height
    H.noisy(d, im, DGREY, 4, seed=9)
    d.rectangle([S * .22, Hh * .12, S * .78, Hh * .62], fill=(16, 22, 26, 255), outline=(90, 90, 90, 255), width=4)
    d.ellipse([S * .38, Hh * .2, S * .62, Hh * .54], fill=(34, 48, 58, 255))
    for k, lab in enumerate(['ЯРК', 'ДАЛЬН', 'ПКИ']):
        x = S * (0.2 + k * 0.3)
        d.ellipse([x - 14, Hh * .74 - 14, x + 14, Hh * .74 + 14], fill=(22, 22, 22, 255), outline=(140, 140, 140, 255),
                  width=3)
        H.text(d, (x, Hh * .93), lab, Hh * .09)


def p_headbox(d, im):
    S, Hh = im.width, im.height
    H.noisy(d, im, HEADPAD, 5, seed=60)
    d.rectangle([S * .1, Hh * .1, S * .9, Hh * .9], outline=(60, 62, 58, 255), width=6)
    H.text(d, (S * .5, Hh * .5), 'КС-4', Hh * .24, fill=(40, 40, 40), bold=True)


def p_armour(d, im):
    S, Hh = im.width, im.height
    H.noisy(d, im, (74, 78, 74), 4, seed=61)
    for k in range(1, 4):
        d.line([0, Hh * k / 4, S, Hh * k / 4], fill=(56, 60, 56, 255), width=3)
    for x in (S * .12, S * .88):
        for y in (Hh * .1, Hh * .9):
            d.ellipse([x - 5, y - 5, x + 5, y + 5], fill=(120, 124, 120, 255))


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
            # the Taifun-M scope is a round tube: the game's square raster stands in a round bezel and a round rubber hood
            # (deep on top), sized round the game's housing (BEZEL 0.12 of the width each side) corners
            _, n_, u_, r_ = H.frame(c, f.N, f.U)
            r_in = (w * (1 + 2 * L.DISPLAY_BEZEL) / 2) * math.sqrt(2) + 0.003
            r_out = r_in + 0.016
            m.disc(c + n_ * 0.0028, n_, u_, r_in, sw['black'], seg=28)
            m.ring(c, n_, u_, r_in, r_out, 0.009, sw['black'], seg=28)
            seg = 28
            for k in range(seg):
                a0, a1 = -0.35 + math.pi * 1.7 * k / seg, -0.35 + math.pi * 1.7 * (k + 1) / seg
                d0, d1 = r_ * math.cos(a0) + u_ * math.sin(a0), r_ * math.cos(a1) + u_ * math.sin(a1)
                dep0, dep1 = 0.012 + 0.058 * max(0.0, math.sin(a0)), 0.012 + 0.058 * max(0.0, math.sin(a1))
                q = [c + d0 * r_out + n_ * 0.009, c + d1 * r_out + n_ * 0.009, c + d1 * r_out + n_ * (0.009 + dep1),
                     c + d0 * r_out + n_ * (0.009 + dep0)]
                m.flat(q, sw['black'], toward=c + (d0 + d1) * 5 + n_ * 0.03)          # outer skin of the hood
                q2 = [c + d0 * r_in + n_ * 0.009, c + d1 * r_in + n_ * 0.009, c + d1 * r_in + n_ * (0.009 + dep1),
                      c + d0 * r_in + n_ * (0.009 + dep0)]
                m.flat(q2, sw['black'], toward=c + n_ * 0.03)                          # its inner skin, lit dark
                m.flat([q[3], q[2], q2[2], q2[3]], sw['black'], toward=c + n_ * 0.2)   # the lip, toward the pilot
        elif kind == 'paint_disc':
            rect = atlas.paint(32, 32, p_disc_red)
            m.disc(c + f.N * 0.008, f.N, f.U, spec[1] / 2, rect, seg=12)
            m.ring(c, f.N, f.U, spec[1] / 2, spec[1] / 2 + 0.004, 0.008, sw['bezel'], seg=12)
        elif kind == 'paint_rect':
            w, h = spec[1], spec[2]
            painter = {'weapons': p_weapons, 'gear': p_gear}[name]
            ph = 96 if h >= w else max(16, int(round(160 * h / w)))
            pw = 160 if w >= h else max(16, int(round(96 * w / h)))
            rect = atlas.paint(pw, ph, painter)
            m.rect_tex(c + f.N * 0.004, f.N, f.U, w, h, rect)
            m.frame_rect(c, f.N, f.U, w, h, 0.004, 0.006, sw['bezel'])
            if name == 'gear':                                     # the gear lever stands out of its panel
                m.box(c + f.N * 0.04 - f.U * 0.02, (f.R, f.U, f.N), (0.006, 0.006, 0.035), sw['metal'])
                m.box(c + f.N * 0.075 - f.U * 0.02, (f.R, f.U, f.N), (0.012, 0.012, 0.010), sw['red'])
        elif kind == 'plate':                                      # a Cyrillic placard: black plate, white letters
            w, h, label = spec[1], spec[2], spec[3]
            pw = max(24, int(w * 1000)); ph = max(12, int(h * 1000))
            rect = atlas.paint(pw, ph, H.p_label_plate(label))
            m.rect_tex(c + f.N * 0.003, f.N, f.U, w, h, rect)

    # -- coaming: lofted from the panel lip forward to the windscreen's base bar
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
    for t in (0.0, 0.25, 0.5, 0.75, 1.0):
        row = []
        for le in lip_edge:
            s = (le[0] - E[0]) / max(abs(lip_edge[0][0] - E[0]), 1e-6)
            xf = E[0] + s * FRONT_X
            yf = E[1] + FRONT_Y_C + (FRONT_Y_W - FRONT_Y_C) * s * s
            z = le[2] + (E[2] + FRONT_Z - le[2]) * t
            x = le[0] + (xf - le[0]) * t
            y = le[1] + (yf - le[1]) * t + 0.02 * math.sin(math.pi * t) * (1 - s * s)
            if abs(x - E[0]) > 0.20:                       # beside the wings the coaming stays under the old deck's sloping skin (the side check)
                y = min(y, E[1] - 0.300)
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
    for j in (0, len(lip_edge) - 1):                                # the coaming's side ends down to the floor of the nose bay
        col = [rows[i][j] for i in range(len(rows))]
        for i in range(len(col) - 1):
            p, q = col[i], col[i + 1]
            yb_ = E[1] + SILL_Y - 0.10
            m.flat([p, q, [q[0], yb_, q[2]], [p[0], yb_, p[2]]], sw['black'], toward=[E[0], yb_, (p[2] + q[2]) / 2])

    # -- PKI reflector sight on the coaming: the body, its aft face, the reflector frame and its glass
    sight = atlas.paint(96, 64, p_sight)
    g_h, g_hw = 0.085, 0.055
    g_h = min(g_h, 2 * HUD_TOP[0] - 0.012)
    lean = math.radians(35)
    g_up = np.array([0, math.cos(lean), -math.sin(lean)])
    g_n = H.unit(np.cross(X, g_up))
    base_y = E[1] + TOP_C + 0.045                                  # the coaming's top at the lip
    g_c = np.array([E[0], E[1] - 0.012, E[2] + PANEL_Z - 0.04])
    g_bot, g_top = g_c - g_up * g_h / 2, g_c + g_up * g_h / 2
    body_top = base_y + 0.055
    body_c = np.array([E[0], (base_y + body_top) / 2, E[2] + PANEL_Z - 0.03])
    m.box(body_c, (X, Y, Z), (0.055, (body_top - base_y) / 2, 0.075), sw['dgrey'], front=(2, -1, sight, -X, Y))
    if g_bot[1] - 0.006 > body_top + 0.003:
        for s in (-1, 1):
            m.box([E[0] + s * (g_hw + 0.004), (body_top + g_bot[1] - 0.006) / 2, g_bot[2]], (X, Y, Z),
                  (0.004, (g_bot[1] - 0.006 - body_top) / 2, 0.005), sw['dgrey'])
    m.box(g_bot - g_up * 0.005, (X, g_up, g_n), (g_hw + 0.008, 0.005, 0.010), sw['dgrey'])
    for s in (-1, 1):
        m.box(g_c + X * s * (g_hw + 0.004), (X, g_up, g_n), (0.004, g_h / 2, 0.005), sw['dgrey'])
    m.box(g_top + g_up * 0.004, (X, g_up, g_n), (g_hw + 0.008, 0.004, 0.005), sw['dgrey'])
    hud = dict(centre=g_c, normal=-g_n, up=g_up, width=2 * g_hw, height=g_h, style='REFLECTOR')
    gl = (g_hw - 0.001, g_h / 2 - 0.001)
    glass = [g_c - X * gl[0] - g_up * gl[1], g_c + X * gl[0] - g_up * gl[1], g_c + X * gl[0] + g_up * gl[1],
             g_c - X * gl[0] + g_up * gl[1]]
    hud['glass'] = [glass[0], glass[1], glass[2], glass[0], glass[2], glass[3]]

    # -- side walls, consoles, the closing faces under the panel (the sill skin's inner face stands at x 0.317)
    for side in (1, -1):                                            # +1 = left (x > 0)
        xw = E[0] + WALL_X * side
        wall = atlas.paint(120, 60, H.p_wall(TURQ_D, TURQ, 30 + side))
        z0, z1 = E[2] + BACK_Z, E[2] + PANEL_Z - 0.10
        qa = [xw, E[1] + CONSOLE_Y - 0.02, z0]; qb = [xw, E[1] + CONSOLE_Y - 0.02, z1]
        qc = [xw, E[1] + SILL_Y - 0.01, z1]; qd = [xw, E[1] + SILL_Y - 0.01, z0]
        ua, ub, uc, ud = m.uv(wall, 0, 0), m.uv(wall, 1, 0), m.uv(wall, 1, 1), m.uv(wall, 0, 1)
        if side < 0:
            ua, ub, uc, ud = ub, ua, ud, uc
        m.face([qa, qb, qc], [ua, ub, uc], normal=[-side, 0, 0])
        m.face([qa, qc, qd], [ua, uc, ud], normal=[-side, 0, 0])
        wing = left if side > 0 else right

        def wing_bottom_z(x):
            a = (abs(x) - HINGE) / math.cos(WING)
            return wing.at(a, E[1] + BOT_W)[2]
        xi, xo = E[0] + CONSOLE_IN * side, E[0] + WALL_X * side
        zf_i, zf_o = wing_bottom_z(CONSOLE_IN), wing_bottom_z(WALL_X)
        cy, fy = E[1] + CONSOLE_Y, E[1] + FLOOR_Y + 0.02
        zb = E[2] + BACK_Z + 0.06
        con = atlas.paint(240, 36, H.rotated(H.p_console(TURQ, 20 + side, [0.11, 0.08, 0.13, 0.07, 0.12, 0.09, 0.10, 0.08] if side > 0
                                               else [0.09, 0.12, 0.08, 0.11, 0.10, 0.09, 0.13, 0.08],
                                               slot=(0.44, 0.30, 0.56, 0.62) if side > 0 else None)))
        top = [[xi, cy, zb], [xo, cy, zb], [xo, cy, zf_o], [xi, cy, zf_i]]
        m.face(top, [m.uv(con, 1, 0), m.uv(con, 1, 1), m.uv(con, 0, 1), m.uv(con, 0, 0)], normal=[0, 1, 0])
        m.flat([[xi, fy, zb], [xi, fy, zf_i], [xi, cy, zf_i], [xi, cy, zb]], sw['turq_d'], normal=[-side, 0, 0])
        m.flat([[xi, fy, zb], [xo, fy, zb], [xo, cy, zb], [xi, cy, zb]], sw['turq_d'], normal=[0, 0, -1])
    # under the centre facet: its lower closing face back to the footwell
    bl, br = centre.at(HINGE, E[1] + BOT_C), centre.at(-HINGE, E[1] + BOT_C)
    m.flat([bl, br, br + np.array([0, 0, 0.12]), bl + np.array([0, 0, 0.12])], sw['turq_d'], normal=[0, -1, 0])
    # the floor: a dark plate over the model's own floor (y 1.519), from behind the seat to the pedals
    fw = 0.30
    m.flat([P(-fw, FLOOR_Y + 0.03, BACK_Z), P(fw, FLOOR_Y + 0.03, BACK_Z),
            P(fw, FLOOR_Y + 0.03, PANEL_Z + 0.30), P(-fw, FLOOR_Y + 0.03, PANEL_Z + 0.30)], sw['floor'],
           normal=[0, 1, 0])
    # rudder pedals under the panel
    for s in (1, -1):
        m.box(P(s * 0.12, -0.945, 0.50), (X, H.unit([0, 1, -0.35]), H.unit([0, 0.35, 1])), (0.045, 0.07, 0.01), sw['mgrey'])
        m.box(P(s * 0.12, -1.005, 0.54), (X, Y, Z), (0.012, 0.03, 0.06), sw['dgrey'])

    # -- two throttle levers (two R-13F2-300) on the left console
    tz = 0.10
    for tx in (0.255, 0.290):
        m.box(P(tx, CONSOLE_Y + 0.055, tz), (X, H.unit([0, 1, 0.25]), H.unit([0, -0.25, 1])), (0.006, 0.06, 0.006), sw['metal'])
        grip_ax = (X, H.unit([0, 1, 0.3]), H.unit([0, -0.3, 1]))
        m.box(P(tx, CONSOLE_Y + 0.135, tz + 0.02), grip_ax, (0.016, 0.042, 0.030), sw['black'])
        m.box(P(tx, CONSOLE_Y + 0.183, tz + 0.03), grip_ax, (0.014, 0.009, 0.026), sw['dgrey'])
    m.box(P(0.255, CONSOLE_Y + 0.15, tz + 0.043), grip_ax, (0.003, 0.008, 0.008), sw['red'])

    # -- KS-4 ejection seat: an armoured back shield, headrest pad, the face-blind handle above the head, arm rests; at the
    # old seat group's place in the model (back z -0.32, headbox front face z -0.27, head top +0.13)
    cushion = atlas.paint(64, 64, H.p_cushion(CUSHION))
    back = atlas.paint(48, 64, H.p_cushion(CUSHION, straps=True))
    armour = atlas.paint(48, 64, p_armour)
    headbox = atlas.paint(64, 64, p_headbox)
    stripes = atlas.paint(64, 16, H.p_stripes)
    ax_b = (X, H.unit([0, 1, -0.10]), H.unit([0, 0.10, 1]))
    m.box(P(0, -1.010, -0.10), (X, Y, Z), (0.16, 0.04, 0.26), sw['frame'])                       # the seat base (floor -1.065)
    m.box(P(0, -0.925, -0.06), (X, Y, Z), (0.15, 0.03, 0.26), sw['cushion'], front=(1, 1, cushion, -X, Z))   # the pan
    for s in (1, -1):
        m.box(P(s * 0.185, -0.89, -0.10), (X, Y, Z), (0.010, 0.12, 0.25), sw['frame'])           # side plates
        m.box(P(s * 0.13, -0.54, -0.335), ax_b, (0.016, 0.44, 0.020), sw['frame'])               # guide rails up to the headbox
        m.box(P(s * 0.196, -0.84, 0.05), (X, Y, Z), (0.010, 0.010, 0.15), sw['dgrey'])           # arm rest
    m.box(P(0, -0.56, -0.375), ax_b, (0.17, 0.38, 0.016), sw['metal'], front=(2, 1, armour, X, ax_b[1]))   # the armoured shield
    m.box(P(0, -0.55, -0.350), ax_b, (0.145, 0.35, 0.014), sw['cushion'], front=(2, 1, back, X, ax_b[1]))
    m.box(P(0, -0.040, -0.325), (X, Y, Z), (0.14, 0.130, 0.055), sw['frame'])                    # headbox (top +0.09)
    m.box(P(0, -0.025, -0.2695), (X, Y, Z), (0.10, 0.085, 0.0045), sw['cushion'], front=(2, 1, headbox, X, Y))
    # the face-blind: a rolled canvas hood over the headbox and its yellow-and-black striped handle
    m.box(P(0, 0.108, -0.325), (X, Y, Z), (0.10, 0.018, 0.045), sw['dgrey'])
    m.box(P(0, 0.108, -0.2745), (X, Y, Z), (0.045, 0.011, 0.006), sw['yellow'], front=(2, 1, stripes, X, Y))
    for s in (1, -1):
        m.box(P(s * 0.100, 0.108, -0.2745), (X, Y, Z), (0.006, 0.013, 0.006), sw['metal'])

    # -- control stick (RUS): the boot stays with the cockpit, the rest goes into the stick bone
    sz = 0.40
    m.box(P(0, -1.03, sz), (X, Y, Z), (0.045, 0.04, 0.045), sw['dgrey'])
    stick = H.Mesh(atlas)
    col_ax = (X, H.unit([0, 1, -0.07]), H.unit([0, 0.07, 1]))
    stick.box(P(0, -0.80, sz - 0.002), col_ax, (0.011, 0.19, 0.011), sw['metal'])
    g_ax = (X, H.unit([0, 1, 0.21]), H.unit([0, -0.21, 1]))
    gc = P(0, -0.60, sz - 0.006)
    stick.box(gc, g_ax, (0.021, 0.062, 0.027), sw['black'])
    stick.box(gc + g_ax[1] * 0.066, g_ax, (0.017, 0.006, 0.022), sw['dgrey'])
    stick.box(gc + g_ax[1] * 0.07 + g_ax[2] * 0.010, g_ax, (0.006, 0.004, 0.006), sw['red'])
    stick.box(gc + g_ax[2] * 0.031 + g_ax[1] * 0.015, g_ax, (0.008, 0.012, 0.005), sw['metal'])
    stick.box(gc - g_ax[2] * 0.034 + g_ax[1] * 0.01, g_ax, (0.012, 0.035, 0.004), sw['metal'])
    foot = P(0, -1.00, sz)
    return m, (stick, foot), gauges, displays, hud


# ---------------------------------------------------------------- the airframe

# the old interior, cut out by runs of faces of the hull piece: (bone, first, last, box lo, box hi). The box is checked
# before a run is cut; the old frame (rails, bows, windscreen ribs, roof bow) is canopy_su_15tm.py's.
CUT_RUNS = [('wreck_fuselage_1__hull', 450, 916, (-0.453, 1.496, 2.01), (0.453, 2.72, 3.755))]    # seat group, old panel, consoles, tub parts, sight


def find_run(P, polys, a, c, lo, hi, tol=0.012):
    n = c - a + 1
    mins = np.array([P[[v[0] for v in p]].min(0) if p else np.full(3, 9e9) for p in polys])
    maxs = np.array([P[[v[0] for v in p]].max(0) if p else np.full(3, -9e9) for p in polys])
    for s in sorted(range(max(0, a - 700), min(len(polys) - n, a + 700) + 1), key=lambda s: abs(s - a)):
        if np.all(np.abs(mins[s:s + n].min(0) - lo) < tol) and np.all(np.abs(maxs[s:s + n].max(0) - hi) < tol):
            return s
    return None


def cut_old(ac):
    """removes the old interior (CUT_RUNS); a run not found stops the script unless a cockpit bone is already in the geo"""
    already = any(b['name'].endswith('__cockpit') and b.get('poly_mesh', {}).get('polys') for b in ac.bones)
    gone = {}
    for name, a, c, lo, hi in CUT_RUNS:
        pm = ac.bone(name)['poly_mesh']
        P = np.array(pm['positions'], float) * np.array([1, 1, -1]) / 16.0
        s = find_run(P, pm['polys'], a, c, lo, hi)
        if s is None:
            if already:
                continue
            raise SystemExit(f'{name}: the interior run {a}-{c} (box {lo}, {hi}) is not in the airframe: the face list changed; '
                             're-read CUT_RUNS (nothing was written)')
        gone.setdefault(name, set()).update(range(s, s + c - a + 1))
    for name, idx in gone.items():
        pm = ac.bone(name)['poly_mesh']
        pm['polys'] = [p for i, p in enumerate(pm['polys']) if i not in idx]
    return sum(len(v) for v in gone.values())


def install(ac, mesh, stick, gauges, displays, hud, eye, seat=0):
    """as handbuilt.install, but the canopy's CanopyGlass stays (the sight's glass is added to it)"""
    pm = mesh.poly_mesh()
    piece = SOURCE_BONE.split('__', 1)[0]
    ac.bones[:] = [b for b in ac.bones if b['name'] != piece + '__cockpit']
    hull = ac.bone('hull')
    at = next(i for i, b in enumerate(ac.bones) if b['name'] == SOURCE_BONE) + 1
    ac.bones.insert(at, {'name': piece + '__cockpit', 'parent': 'hull', 'pivot': list(hull['pivot']), 'poly_mesh': pm})
    rig = ac.asset.get('AircraftRig')
    mesh_s, foot = stick
    px = H.r5(np.array(foot) * np.array([16.0, 16.0, -16.0]))
    for b in ac.bones:
        if b['name'] in ('control_stick_roll', 'control_stick_pitch') or b['name'].endswith('__control_stick_pitch'):
            b['pivot'] = px
            if b['name'].endswith('__control_stick_pitch'):
                b['poly_mesh'] = {'normalized_uvs': True, 'positions': [], 'normals': [], 'uvs': [], 'polys': []}
    ac.bone(STICK_BONE)['poly_mesh'] = mesh_s.poly_mesh()
    for sf in (rig or {}).get('Surfaces', []):
        if sf.get('Bone') in ('control_stick_roll', 'control_stick_pitch'):
            sf['Pivot'] = px
    a = ac.asset
    a['CockpitGauges'] = {'Schema': 1, 'Frame': 'VEHICLE_LOCAL_BLOCKS', 'Gauges': gauges}
    a['FlightDisplays'] = {'Schema': 1, 'Frame': 'VEHICLE_LOCAL_BLOCKS', 'Displays': displays}
    a['HeadUpDisplay'] = {'Schema': 1, 'Frame': 'VEHICLE_LOCAL_BLOCKS', 'Seat': seat, 'Center': H.r5(hud['centre']),
                          'Normal': H.r5(hud['normal']), 'Up': H.r5(hud['up']), 'Width': round(float(hud['width']), 5),
                          'Height': round(float(hud['height']), 5), 'Eye': H.r5(eye), 'Style': hud['style']}
    if hud.get('glass'):                       # the reflector's glass, on top of the canopy's (the canopy script rewrote CanopyGlass)
        L.add_glass(a, hud['glass'])
    return pm


def main(argv):
    dry = '--dry-run' in argv
    ac = L.Aircraft(VID)
    E = ac.eye(0)
    removed = cut_old(ac)
    atlas = H.A.Atlas(VID)
    mesh, stick, gauges, displays, hud = build(atlas, E)
    pm = install(ac, mesh, stick, gauges, displays, hud, E)
    print(f'{VID}: removed {removed} old interior faces; new cockpit {len(pm["polys"])} faces, {len(gauges)} gauges, '
          f'{len(displays)} display')
    if not dry:
        H.write(ac, atlas)


if __name__ == '__main__':
    main(sys.argv[1:])
