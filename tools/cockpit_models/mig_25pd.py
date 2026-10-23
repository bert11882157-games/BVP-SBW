#!/usr/bin/env python3
"""MiG-25PD cockpit, built by hand for this model (as mig_23mld.py, su_27.py, mig_29.py), from the real panel layout.

Measured off the model (the faces of MiG-25.mtb in checks/orig, sections.py, eyeview.py; all heights and distances from
the pilot's eye at (0, 3.1644, 4.7353); 1 block = 1 m on this model):
  * the cockpit narrows toward the nose: the fuselage's inner wall stands at x 0.45 under the canopy's rear, 0.41 beside
    the panel (below y -0.59), and leans in to 0.32 at the sill ridge (y -0.32) there; the sill ridge falls from y -0.23
    (z -0.30) to -0.32 (z +0.57); the floor at y -1.085; the old console boxes' tops at y -0.67 from x 0.27 outward;
  * the old panel (cyan boxes) stood 0.57 m ahead of the eye; the nose deck under the windscreen falls from y -0.32 at the
    bow to -0.36 at the base bar (z +1.455); the pilot's K-36-class seat group: back at z -0.425..-0.295, headbox
    z -0.425..-0.245 topping y +0.09, the seat pan forward to z +0.39, the ejection handle at z +0.38.
Built:
  * a three-facet main panel 0.60 m ahead, 12 degrees back, its wings turned 25 degrees toward the pilot to the wall:
    the Smerch-A2 (RP-25) radar scope, the large round CRT in its rubber hood at the centre, over the KPP attitude
    director and NPP horizontal situation (left), the big Machmeter / airspeed and the altimeter (right), VVI and AoA,
    radio altimeter and fuel, the clock; airspeed, the weapons panel and the gear panel on the left wing; the twin
    tachometer, exhaust gas temperature, hydraulics, oxygen, SPO-10 radar warning and the warning-light block on the
    right wing (the 'engine instruments': two R-15B-300);
  * the black coaming forward to the windscreen's base, a long raked deck (the windscreen stands 1.455 m ahead);
  * the PPV reflector sight on the coaming (HeadUpDisplay Style REFLECTOR, seat 0; the MiG-25P/PD had no head-up display);
  * low side consoles with switch panels, side walls, rudder pedals, two throttle levers on the left console, a KM-1
    ejection seat and the control stick (into the stick bone).
Seat: the real MiG-25 (all P/PD/PDS and R models) sits on the KM-1 (a larger relative of the MiG-21 / MiG-23 KM-1 with an
armoured back shield, no head deflector, the ejection handles at the front centre; armedconflicts.com/-t48616); the
airframe's model carries a K-36-class seat group (the War Thunder MiG-25PD lists a K-36), which is replaced here by a KM-1
at the same place: Decision taken in the report.
Instruments the game draws (CockpitGauges, FlightDisplays, HeadUpDisplay) are live. The canopy glass is canopy_mig_25pd.py's.

Run on the airframe's originals (mig25/run_mig25_cockpit.ps1 restores them), after canopy_mig_25pd.py.
Usage: python3 tools/cockpit_models/mig_25pd.py [--dry-run]
"""
import json, math, os, sys
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import cockpit_lib as L   # noqa: E402
import handbuilt as H     # noqa: E402

VID = 'mig_25pd'
SOURCE_BONE = 'wreck_fuselage_0__hull'
STICK_BONE = 'wreck_fuselage_0__control_stick_pitch'

TURQ = (80, 122, 124)          # the Soviet grey-green / turquoise of the panel (references: the dark grey-green MiG-25 cockpit)
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
WALL_X = 0.385                 # consoles and side walls end here at the panel (the inner wall: 0.45 behind, 0.41 at the panel)
WALL_X_BACK = 0.42            # ... and here behind the seat
WING_X = 0.335                 # the panel wings end here (the wall leans in to 0.32 at the sill ridge)
SILL_Y = -0.30
FLOOR_Y = -1.085
BACK_Z = -0.44                 # behind the seat
BULK_Z = -0.435                # the rear bulkhead closing the hinge fairing's inside
CONSOLE_Y = -0.70
CONSOLE_IN = 0.27
PANEL_Z = 0.60                 # centre facet reference plane
LEAN = math.radians(12)
HINGE = 0.17                   # centre facet half width
WING = math.radians(25)
WING_LEN = (WING_X - HINGE) / math.cos(WING)
TOP_C, TOP_W = -0.150, -0.335  # centre facet top, wing top at its end (under the sill ridge)
BOT_C, BOT_W = -0.72, CONSOLE_Y
FRONT_Z = 1.455                # the coaming runs forward to the windscreen's base bar (z 6.19)
FRONT_Y_C, FRONT_Y_W = -0.345, -0.46
FRONT_X = 0.165                # the coaming's half width at the base (the windscreen narrows to the nose)

HUD_TOP = [0.10]               # how far above the eye the glass reaches at the sight (the windscreen's front bow at +0.10)
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


# Instrument layout of the MiG-25PD front panel: the RP-25 Smerch scope in the middle, the pilot-navigation flight
# instruments (KPP, NPP) left, the big Mach/airspeed and altitude dials right, the engine group on the right wing.
# facet: 0 centre (a: metres to the pilot's right of the centre line), +1 right wing, -1 left wing (a: outward from the
# hinge). y: from the eye.
LAYOUT = [
    # centre facet
    ('scope',    0,   0.000, -0.285, ('display', 'RADAR', 0.120), 'RP-25 Smerch-A2 radar scope (round tube, hooded)'),
    ('caution',  0, -0.135, -0.222, ('paint_disc', 0.022), 'master caution lamp'),
    ('clock',    0,  0.135, -0.228, ('game', 'CLOCK', 0.034), 'AChS-1 clock'),
    ('p_radar',  0,   0.000, -0.427, ('plate', 0.150, 0.016, 'РЛС СМЕРЧ-А2'), 'scope placard'),
    ('adi',      0, -0.095, -0.495, ('game', 'ATTITUDE', 0.082), 'KPP attitude director'),
    ('hsi',      0, -0.095, -0.585, ('game', 'HEADING', 0.080), 'NPP horizontal situation'),
    ('mach',     0,  0.095, -0.495, ('game', 'MACH', 0.082), 'Mach / true airspeed'),
    ('alt',      0,  0.095, -0.585, ('game', 'ALTIMETER', 0.080), 'VD-17 altimeter'),
    ('vvi',      0,   0.000, -0.475, ('game', 'VVI', 0.050), 'VAR-300 vertical speed'),
    ('aoa',      0,   0.000, -0.535, ('game', 'AOA', 0.046), 'UUA AoA'),
    ('radalt',   0, -0.095, -0.670, ('game', 'RADAR_ALT', 0.050), 'radio altimeter'),
    ('fuel',     0,  0.095, -0.670, ('game', 'FUEL_QTY', 0.050), 'fuel quantity'),
    # left wing
    ('ias',     -1,   0.085, -0.340, ('game', 'AIRSPEED', 0.070), 'KUS airspeed'),
    ('g',       -1,   0.085, -0.440, ('game', 'G_METER', 0.048), 'accelerometer'),
    ('weapons', -1,   0.070, -0.545, ('paint_rect', 0.115, 0.055), 'weapons panel'),
    ('gear',    -1,   0.150, -0.630, ('paint_rect', 0.045, 0.090), 'gear control panel'),
    # right wing
    ('rpm',      1,   0.065, -0.335, ('game', 'ENGINE_RPM', 0.056), 'ITE-2T twin tachometer'),
    ('egt',      1,   0.140, -0.400, ('game', 'EGT', 0.046), 'exhaust gas temperature'),
    ('hyd',      1,   0.065, -0.425, ('game', 'FILLER_HYDRAULIC', 0.040), 'hydraulic pressures'),
    ('oxy',      1,   0.140, -0.475, ('game', 'FILLER_OXYGEN', 0.034), 'oxygen'),
    ('spo10',    1,   0.070, -0.510, ('game', 'RWR', 0.052), 'SPO-10 radar warning'),
    ('warning',  1,   0.100, -0.600, ('annunciator', 0.120, 0.070), 'warning lights'),
    ('p_eng',    1,   0.100, -0.675, ('plate', 0.120, 0.016, 'ДВИГАТЕЛИ'), 'engine placard'),
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
    labs = ['Р-40', 'Р-60', 'ПТБ', 'ПУСК']
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
    """the PPV reflector sight's aft face: the reticle unit's window, the brightness / range / cage knobs"""
    S, Hh = im.width, im.height
    H.noisy(d, im, DGREY, 4, seed=9)
    d.rectangle([S * .22, Hh * .12, S * .78, Hh * .62], fill=(16, 22, 26, 255), outline=(90, 90, 90, 255), width=4)
    d.ellipse([S * .38, Hh * .2, S * .62, Hh * .54], fill=(34, 48, 58, 255))
    for k, lab in enumerate(['ЯРК', 'ДАЛЬН', 'ППВ']):
        x = S * (0.2 + k * 0.3)
        d.ellipse([x - 14, Hh * .74 - 14, x + 14, Hh * .74 + 14], fill=(22, 22, 22, 255), outline=(140, 140, 140, 255),
                  width=3)
        H.text(d, (x, Hh * .93), lab, Hh * .09)


def p_headbox(d, im):
    S, Hh = im.width, im.height
    H.noisy(d, im, HEADPAD, 5, seed=60)
    d.rectangle([S * .1, Hh * .1, S * .9, Hh * .9], outline=(60, 62, 58, 255), width=6)
    H.text(d, (S * .5, Hh * .5), 'КМ-1', Hh * .24, fill=(40, 40, 40), bold=True)


def p_armour(d, im):
    S, Hh = im.width, im.height
    H.noisy(d, im, (74, 78, 74), 4, seed=61)
    for k in range(1, 4):
        d.line([0, Hh * k / 4, S, Hh * k / 4], fill=(56, 60, 56, 255), width=3)
    for x in (S * .12, S * .88):
        for y in (Hh * .1, Hh * .9):
            d.ellipse([x - 5, y - 5, x + 5, y + 5], fill=(120, 124, 120, 255))


def plate_painter(label):
    return H.p_label_plate(label)


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
            # the Smerch scope is a round tube: the game's square raster stands in a round bezel and a round rubber hood
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
            yb_ = E[1] + SILL_Y - 0.10
            m.flat([p, q, [q[0], yb_, q[2]], [p[0], yb_, p[2]]], sw['black'], toward=[E[0], yb_, (p[2] + q[2]) / 2])

    # -- PPV reflector sight on the coaming: the body, its aft face, the reflector frame and its glass
    sight = atlas.paint(96, 64, p_sight)
    g_h, g_hw = 0.100, 0.060
    g_h = min(g_h, 2 * HUD_TOP[0] - 0.012)
    lean = math.radians(35)
    g_up = np.array([0, math.cos(lean), -math.sin(lean)])
    g_n = H.unit(np.cross(X, g_up))
    base_y = E[1] + TOP_C + 0.045                                  # the coaming's top at the lip
    g_c = np.array([E[0], E[1] - 0.005, E[2] + PANEL_Z - 0.05])
    g_bot, g_top = g_c - g_up * g_h / 2, g_c + g_up * g_h / 2
    body_top = base_y + 0.055
    body_c = np.array([E[0], (base_y + body_top) / 2, E[2] + PANEL_Z - 0.035])
    m.box(body_c, (X, Y, Z), (0.060, (body_top - base_y) / 2, 0.085), sw['dgrey'], front=(2, -1, sight, -X, Y))
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

    # -- side walls, consoles, the closing faces under the panel
    for side in (1, -1):                                            # +1 = left (x > 0)
        xw = E[0] + WALL_X * side                                   # the wall follows the fuselage's inner wall: 0.43 behind, 0.385 at the panel
        xb = E[0] + WALL_X_BACK * side
        wall = atlas.paint(120, 60, H.p_wall(TURQ_D, TURQ, 30 + side))
        z0, z1 = E[2] + BACK_Z, E[2] + PANEL_Z - 0.10
        # the wall stays inside the fuselage's own lining: vertical at x 0.43 behind the seat, leaning in toward the sill
        # ridge by the panel (the lining stands at 0.41 low and 0.32 at the sill there): two triangles of a warped quad
        xw1b, xw1t = E[0] + 0.385 * side, E[0] + 0.300 * side
        qa = [xb, E[1] + CONSOLE_Y - 0.02, z0]; qb = [xw1b, E[1] + CONSOLE_Y - 0.02, z1]
        qc = [xw1t, E[1] + SILL_Y - 0.01, z1]; qd = [xb, E[1] - 0.245, z0]
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
        top = [[xi, cy, zb], [xb, cy, zb], [xo, cy, zf_o], [xi, cy, zf_i]]
        m.face(top, [m.uv(con, 1, 0), m.uv(con, 1, 1), m.uv(con, 0, 1), m.uv(con, 0, 0)], normal=[0, 1, 0])
        m.flat([[xi, fy, zb], [xi, fy, zf_i], [xi, cy, zf_i], [xi, cy, zb]], sw['turq_d'], normal=[-side, 0, 0])
        m.flat([[xi, fy, zb], [xb, fy, zb], [xb, cy, zb], [xi, cy, zb]], sw['turq_d'], normal=[0, 0, -1])
    # the rear bulkhead behind the seat, closing the hinge fairing's inside (what shows through the glass behind the pilot):
    # the fairing's own outline (its front face, z 4.441) from the floor up over the top
    out_r = [(0.425, -1.075), (0.425, -0.240), (0.385, -0.160), (0.325, -0.070), (0.245, 0.020), (0.130, 0.108), (0.0, 0.150)]
    ring = [P(x, y, BULK_Z) for x, y in out_r] + [P(-x, y, BULK_Z) for x, y in out_r[::-1]]
    wallp = atlas.paint(120, 90, H.p_wall(TURQ_D, TURQ, 77))
    cb = np.mean(ring, 0)
    for i in range(len(ring) - 1):
        m.face([cb, ring[i], ring[i + 1]], [m.uv(wallp, 0.5, 0.5), m.uv(wallp, 0.1 + 0.8 * i / len(ring), 0.2),
                                            m.uv(wallp, 0.1 + 0.8 * (i + 1) / len(ring), 0.2)], normal=[0, 0, 1])
    ring2 = [P(x, y, -0.30) for x, y in out_r] + [P(-x, y, -0.30) for x, y in out_r[::-1]]    # the fairing's ceiling and walls, bulkhead to canopy end
    for i in range(1, len(ring) - 2):                       # (not the floor segment)
        m.flat([ring[i], ring[i + 1], ring2[i + 1], ring2[i]], sw['turq_d'], toward=cb + np.array([0, 0, 0.07]))
    # under the centre facet: its lower closing face back to the footwell
    bl, br = centre.at(HINGE, E[1] + BOT_C), centre.at(-HINGE, E[1] + BOT_C)
    m.flat([bl, br, br + np.array([0, 0, 0.12]), bl + np.array([0, 0, 0.12])], sw['turq_d'], normal=[0, -1, 0])
    # the floor: a dark plate over the model's own floor, from behind the seat to the pedals
    fw = 0.44
    m.flat([P(-fw, FLOOR_Y + 0.03, BACK_Z), P(fw, FLOOR_Y + 0.03, BACK_Z),
            P(fw, FLOOR_Y + 0.03, PANEL_Z + 0.30), P(-fw, FLOOR_Y + 0.03, PANEL_Z + 0.30)], sw['floor'],
           normal=[0, 1, 0])
    # rudder pedals under the panel
    for s in (1, -1):
        m.box(P(s * 0.12, -0.945, 0.56), (X, H.unit([0, 1, -0.35]), H.unit([0, 0.35, 1])), (0.045, 0.07, 0.01), sw['mgrey'])
        m.box(P(s * 0.12, -1.005, 0.60), (X, Y, Z), (0.012, 0.03, 0.06), sw['dgrey'])

    # -- two throttle levers (two R-15B-300) on the left console
    tz = 0.16
    for tx in (0.305, 0.355):
        m.box(P(tx, CONSOLE_Y + 0.055, tz), (X, H.unit([0, 1, 0.25]), H.unit([0, -0.25, 1])), (0.007, 0.06, 0.007), sw['metal'])
        grip_ax = (X, H.unit([0, 1, 0.3]), H.unit([0, -0.3, 1]))
        m.box(P(tx, CONSOLE_Y + 0.135, tz + 0.02), grip_ax, (0.019, 0.042, 0.034), sw['black'])
        m.box(P(tx, CONSOLE_Y + 0.183, tz + 0.03), grip_ax, (0.016, 0.009, 0.028), sw['dgrey'])
    m.box(P(0.305, CONSOLE_Y + 0.15, tz + 0.045), grip_ax, (0.003, 0.008, 0.008), sw['red'])

    # -- KM-1 ejection seat: an armoured back shield, headrest pad (no head deflector), arm rests, the handles at the
    # front centre; at the K-36 group's place in the model (back z -0.425, headbox front face z -0.245, top y +0.09)
    cushion = atlas.paint(64, 64, H.p_cushion(CUSHION))
    back = atlas.paint(48, 64, H.p_cushion(CUSHION, straps=True))
    armour = atlas.paint(48, 64, p_armour)
    headbox = atlas.paint(64, 64, p_headbox)
    stripes = atlas.paint(64, 16, H.p_stripes)
    ax_b = (X, H.unit([0, 1, -0.10]), H.unit([0, 0.10, 1]))
    m.box(P(0, -1.030, -0.10), (X, Y, Z), (0.18, 0.04, 0.28), sw['frame'])                       # the tub / seat base (floor -1.085)
    m.box(P(0, -0.94, -0.04), (X, Y, Z), (0.17, 0.03, 0.29), sw['cushion'], front=(1, 1, cushion, -X, Z))   # the pan
    for s in (1, -1):
        m.box(P(s * 0.205, -0.90, -0.10), (X, Y, Z), (0.012, 0.13, 0.27), sw['frame'])           # side plates
        m.box(P(s * 0.15, -0.56, -0.395), ax_b, (0.018, 0.42, 0.022), sw['frame'])               # guide rails up to the headbox
        m.box(P(s * 0.215, -0.865, 0.09), (X, Y, Z), (0.012, 0.012, 0.16), sw['dgrey'])          # arm rest
    m.box(P(0, -0.58, -0.375), ax_b, (0.19, 0.40, 0.018), sw['metal'], front=(2, 1, armour, X, ax_b[1]))   # the armoured shield
    m.box(P(0, -0.57, -0.345), ax_b, (0.16, 0.37, 0.016), sw['cushion'], front=(2, 1, back, X, ax_b[1]))
    m.box(P(0, -0.045, -0.335), (X, Y, Z), (0.16, 0.135, 0.09), sw['frame'])                    # headbox (top y +0.09, under the fairing)
    m.box(P(0, -0.03, -0.2455), (X, Y, Z), (0.11, 0.09, 0.0045), sw['cushion'], front=(2, 1, headbox, X, Y))
    # the ejection handles: a yellow-and-black striped D ring on two posts at the front centre of the pan
    for s in (1, -1):
        m.box(P(s * 0.035, -0.855, 0.355), (X, Y, Z), (0.006, 0.05, 0.006), sw['metal'])
    m.box(P(0, -0.795, 0.355), (X, Y, Z), (0.045, 0.011, 0.009), sw['yellow'], front=(2, 1, stripes, X, Y))

    # -- control stick (RUS): the boot stays with the cockpit, the rest goes into the stick bone
    sz = 0.46
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

# the old interior, cut out by runs of faces of the hull pieces: (bone, first, last, box lo, box hi). The box is checked
# before a run is cut; the old frame (spine, bow, windscreen rails, side rails) is canopy_mig_25pd.py's.
CUT_RUNS = [('wreck_fuselage_0__hull', 360, 623, (-0.429, 2.034, 4.306), (0.429, 2.984, 6.250)),      # seat group, old panel and deck boxes
            ('wreck_fuselage_0__hull', 636, 971, (-0.457, 2.079, 4.306), (0.457, 3.164, 6.204)),      # consoles, panel, coaming, mirrors, old sight
            ('wreck_fuselage_1__hull', 502, 603, (-0.226, 2.034, 4.306), (0.226, 3.255, 4.487))]      # the seat's back and headbox


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
    ac.bones[:] = [b for b in ac.bones if not b['name'].endswith('__cockpit')]
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
    if hud.get('glass'):                       # the reflector's glass, on top of the canopy's
        cg = a.get('CanopyGlass')
        if cg:                                 # (drop the sight glass of an earlier run: it lies inside the sight's box)
            t = np.array(cg['Triangles'], float).reshape(-1, 3, 3)
            c = t.mean(1)
            keep = ~((np.abs(c[:, 0] - eye[0]) < 0.08) & (np.abs(c[:, 1] - eye[1]) < 0.09) & (c[:, 2] > eye[2] + 0.40) &
                     (c[:, 2] < eye[2] + 0.62))
            cg['Triangles'] = [round(float(v), 4) + 0.0 for v in t[keep].reshape(-1)]
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
