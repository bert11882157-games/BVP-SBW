#!/usr/bin/env python3
"""Mirage IIIE cockpit, built by hand for this model (as mig_25pd.py, mig_23mld.py, mig_29.py), from the real panel layout.

Measured off the model (the faces of Dassault Mirage IIIE.mtb in checks/orig, sections.py, eyeview.py; all heights and
distances from the pilot's eye at (0, 2.31624, 3.73464); 1 block = 1 m on this model):
  * the tub's walls stand at x 0.35 (inner face) from the rear fairing (z -0.79) to z +0.22, then the nose shell closes in to
    x 0.26 (x 0.22 above y -0.55) from there forward; the walls' top (the sill) is at y -0.29 behind the seat, -0.33 at the
    panel and -0.40 under the windscreen arch; the floor's top at y -1.08, the belly at -1.21;
  * the old panel stood at z +0.22..0.55, its top at y -0.22; the nose deck under the windscreen falls from y -0.29 (z 0.40) to
    -0.36 at the windscreen's base bar (z +1.37); the model's seat group: headrest front face z -0.265, headbox top y +0.10;
  * the faired spine behind the seat (its front face z -0.79) carries the canopy's rear bow; the stick's foot (0, -0.953, +0.19).
Built:
  * a three-facet main panel 0.50 m ahead, 12 degrees back, its wings turned 25 degrees toward the pilot to the nose shell's
    wall: the Cyrano II radar scope at the top centre (round tube in its rubber hood), the attitude indicator and horizontal
    situation indicator, Mach / airspeed and altimeter, climb rate, clock (centre); airspeed, g-meter, the armament panel and
    the landing-gear panel on the left wing; the Atar's rev counter and exhaust temperature, fuel, radar warning and the warning
    lights on the right wing; French placards (CYRANO II, ARMEMENT, TRAIN, MOTEUR, ALARMES);
  * about 30 round dials in all (the real panel is packed with them): the live ones in an inner column on each wing, a second
    column and the free rows filled with painted small dials (STATIC_DIALS);
  * the black coaming forward to the windscreen's base, closed against the nose skin under the quarter panes, wrapped at both
    sides in a curved hood that rolls inward over the wing facets;
  * the CSF 97 reflector sight on the coaming, a big box (19 x 20 cm) with its reflector frame standing on the aft edge
    (HeadUpDisplay Style REFLECTOR, seat 0; the Mirage IIIE has no head-up display);
  * low side consoles with switch panels, side walls, rudder pedals, the Atar's throttle lever on the left console, a Martin-Baker
    RM4 ejection seat (seat pan and bucket, parachute back, headbox with the face-blind handle, the lower handle between the
    knees) and the control stick (into the stick bone), the rear bulkhead closing the spine fairing's front and a floor.
Seat: the Mirage IIIE's Martin-Baker RM4 (Mk 4 class seat, a face-blind handle above the headbox and a lower handle at the
front of the pan) replaces the model's own seat group; no photo reference was reachable, the proportions are the old group's.
Instruments the game draws (CockpitGauges, FlightDisplays, HeadUpDisplay) are live. The canopy glass is canopy_mirage_3e.py's.

Run on the airframe's originals (mirage3e/run_mirage3e_cockpit.ps1 restores them), after canopy_mirage_3e.py.
Usage: python3 tools/cockpit_models/mirage_3e.py [--dry-run]
"""
import json, math, os, sys
import numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import throttle_lib
THR = []                                 # the throttle levers (throttle_lib), one bone each

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import cockpit_lib as L   # noqa: E402
import handbuilt as H     # noqa: E402
import canopy_mirage_3e as CM   # noqa: E402

VID = 'mirage_3e'
SOURCE_BONE = 'wreck_fuselage_1__hull'
STICK_BONE = 'wreck_fuselage_1__control_stick_pitch'

PANEL = (46, 50, 55)           # French cockpit: dark blue-grey panels (references: Mirage III cockpit photos, War Thunder)
PANEL_D = (32, 35, 40)
BLACK = (22, 23, 25)
DGREY = (44, 46, 49)
MGREY = (88, 92, 96)
FRAME = (58, 62, 64)           # seat structure
CUSHION = (62, 66, 58)
HEADPAD = (88, 92, 86)
YELLOW = (226, 186, 30)
RED = (196, 36, 30)
METAL = (128, 132, 134)
FLOOR = (40, 44, 46)

# ---------------------------------------------------------------- the measured cockpit (metres from the eye)
WALL_B = 0.345                 # side lining behind z +0.22 (the hull's wall inner face is at 0.35)
WALL_F = 0.255                 # ... and forward of it (the nose shell: 0.26)
WALL_STEP = 0.22
SILL_Y = -0.30
FLOOR_Y = -1.08
BACK_Z = -0.775                # the rear bulkhead (the spine fairing's front face is at -0.79)
CONSOLE_Y = -0.58
CONSOLE_IN = 0.215
PANEL_Z = 0.50                 # centre facet reference plane
LEAN = math.radians(12)
HINGE = 0.105                  # centre facet half width
WING = math.radians(25)
WING_X = 0.225                 # the panel wings end here
WING_LEN = (WING_X - HINGE) / math.cos(WING)
TOP_C, TOP_W = -0.170, -0.340  # centre facet top, wing top at its end
BOT_C, BOT_W = -0.800, -0.720
FRONT_Z = 1.37                 # the coaming runs forward to the windscreen's base bar
FRONT_Y_C, FRONT_Y_W = -0.385, -0.425

HUD_TOP = [0.10]               # how far above the eye the sight's glass may reach (the windscreen's front bow at +0.11)
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


# Instrument layout of the Mirage IIIE front panel: the Cyrano II scope top centre, flight instruments below it, the engine
# group right, airspeed / armament / gear left.
# facet: 0 centre (a: metres to the pilot's right of the centre line), +1 right wing, -1 left wing (a: outward from the
# hinge). y: from the eye.
LAYOUT = [
    # centre facet
    ('scope',    0,   0.000, -0.265, ('display', 'RADAR', 0.090), 'Cyrano II radar scope (round tube, hooded)'),
    ('p_radar',  0,   0.000, -0.386, ('plate', 0.120, 0.014, 'RADAR CYRANO II'), 'scope placard'),
    ('adi',      0, -0.047, -0.455, ('game', 'ATTITUDE', 0.066), 'attitude indicator'),
    ('hsi',      0,  0.047, -0.455, ('game', 'HEADING', 0.066), 'horizontal situation indicator'),
    ('mach',     0, -0.047, -0.545, ('game', 'MACH', 0.066), 'Machmeter'),
    ('alt',      0,  0.047, -0.545, ('game', 'ALTIMETER', 0.066), 'altimeter'),
    ('vvi',      0, -0.047, -0.635, ('game', 'VVI', 0.062), 'variometer'),
    ('clock',    0,  0.047, -0.635, ('game', 'CLOCK', 0.060), 'clock'),
    ('warning',  0,   0.000, -0.735, ('annunciator', 0.190, 0.056), 'warning lights'),
    # left wing: the live dials in the inner column (a 0.043), painted ones beside and above them
    ('ias',     -1,   0.043, -0.430, ('game', 'AIRSPEED', 0.060), 'airspeed indicator'),
    ('g',       -1,   0.043, -0.497, ('game', 'G_METER', 0.046), 'accelerometer'),
    ('weapons', -1,   0.066, -0.610, ('paint_rect', 0.105, 0.052), 'ARMEMENT panel'),
    ('gear',    -1,   0.066, -0.685, ('paint_rect', 0.090, 0.040), 'TRAIN panel'),
    # right wing
    ('rpm',      1,   0.043, -0.430, ('game', 'ENGINE_RPM', 0.060), 'Atar rev counter'),
    ('egt',      1,   0.043, -0.497, ('game', 'EGT', 0.046), 'exhaust gas temperature'),
    ('fuel',     1,   0.043, -0.560, ('game', 'FUEL_QTY', 0.046), 'fuel quantity'),
    ('rwr',      1,   0.043, -0.623, ('game', 'RWR', 0.046), 'radar warning'),
]

# painted (not live) round dials: key -> title, lo, hi, step, label step, needle value, labels. The real panel is packed with
# about 25 black round dials in tight rows (photos of the IIIE's panel), so the wings carry a second column and the free
# rows above and below the live dials are filled with small ones.
STATIC_DIALS = {
    'TEMP': ('T7', 0, 10, 1, 2, 6.0, ['0', '2', '4', '6', '8', '10']),
    'HUILE': ('HUILE', 0, 10, 1, 2, 4.0, ['0', '2', '4', '6', '8', '10']),
    'CARB': ('CARB', 0, 50, 5, 10, 32.0, ['0', '10', '20', '30', '40', '50']),
    'TUYERE': ('TUY', 0, 10, 1, 2, 3.0, ['0', '2', '4', '6', '8', '10']),
    'OXY': ('OXY', 0, 5, 1, 1, 3.5, ['0', '1', '2', '3', '4', '5']),
    'CABINE': ('CAB', 0, 50, 5, 10, 12.0, ['0', '10', '20', '30', '40', '50']),
    'HYD': ('HYD', 0, 4, 1, 1, 3.0, ['0', '1', '2', '3', '4']),
    'VOLT': ('VOLT', 0, 30, 5, 10, 28.0, ['0', '10', '20', '30']),
    'FREIN': ('FREIN', 0, 3, 1, 1, 2.0, ['0', '1', '2', '3']),
    'AMP': ('AMP', 0, 300, 50, 100, 140.0, ['0', '1', '2', '3']),
}
DIAL_POOL = ['TEMP', 'HUILE', 'CARB', 'TUYERE', 'OXY', 'CABINE', 'HYD', 'VOLT', 'FREIN', 'AMP']


DIAL_KEY = {}                          # item name -> its painted face (STATIC_DIALS key)


def _dials(prefix, side, pts, dia, k0=0):
    out = []
    for k, (a, y) in enumerate(pts):
        key = DIAL_POOL[(k0 + k) % len(DIAL_POOL)]
        DIAL_KEY[f'{prefix}{k}'] = key
        out.append((f'{prefix}{k}', side, a, y, ('paint_disc', dia), key.lower()))
    return out


# left wing: a 2 x 2 block above the airspeed dial, a second column beside the live dials, a row between the g-meter and the
# ARMEMENT panel; right wing: the same block and a second column down the wing, a last row under the radar warning
LAYOUT += _dials('la', -1, [(0.040, -0.335), (0.090, -0.335), (0.040, -0.375), (0.090, -0.375)], 0.026) \
    + _dials('lb', -1, [(0.103, -0.428), (0.103, -0.497), (0.043, -0.548), (0.103, -0.548)], 0.034, 4) \
    + _dials('ra', 1, [(0.040, -0.335), (0.090, -0.335), (0.040, -0.375), (0.090, -0.375)], 0.026, 2) \
    + _dials('rb', 1, [(0.103, -0.428), (0.103, -0.497), (0.103, -0.560), (0.103, -0.623), (0.043, -0.684), (0.103, -0.684)],
             0.034, 6)

LAMPS = (3, [{'Text': t, 'Condition': c, 'Colour': k} for t, c, k in [
    ('ALARME|GENERALE', 'MASTER_CAUTION', 'amber'), ('TRAIN|SORTI', 'GEAR_DOWN', 'green'),
    ('TRAIN|INSTABLE', 'GEAR_TRANSIT', 'red'), ('CARBURANT|BAS', 'LOW_FUEL', 'amber'),
    ('FEU', 'FIRE', 'red'), ('PANNE|MOTEUR', 'ENGINE_OUT', 'red')]])


# ---------------------------------------------------------------- painted faces

def panel_paint(side, W_, H_, a0, a1, yb, yt):
    def fn(d, im):
        SS = H.A.Atlas.SS
        H.noisy(d, im, PANEL, 5, seed=3 + side)

        def to_px(a, y):
            s = (a - a0) / (a1 - a0)
            if side == -1:
                s = 1 - s
            return s * W_ * SS, (1 - (y - yb) / (yt - yb)) * H_ * SS
        for y in ((-0.40, -0.50, -0.59, -0.69) if side == 0 else (-0.38, -0.48, -0.56, -0.645)):
            x0, yy = to_px(a0, y); x1, _ = to_px(a1, y)
            d.line([x0, yy, x1, yy], fill=PANEL_D + (255,), width=SS * 2)
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
                d.ellipse([x - r, yy - r, x + r, yy + r], fill=(20, 24, 26, 255))
            elif spec[0] == 'plate':
                pass
            else:
                w, h = (spec[2], spec[2]) if spec[0] == 'display' else (spec[1], spec[2])
                sx, sy = (w / 2 + 0.006) * px_m, (h / 2 + 0.006) * px_m
                d.rectangle([x - sx, yy - sy, x + sx, yy + sy], fill=(20, 24, 26, 255))
    return fn


def p_dial_static(kind):
    def fn(d, im):
        t, lo, hi, st, ls, val, labs = STATIC_DIALS[kind]
        H.dial_face(d, im, t, '', lo, hi, st, ls, val, labels=labs)
    return fn


def p_switch_panel(seed):
    """a side console's sloped switch plate seen from above: a dark plate with a row of captions under every switch"""
    def fn(d, im):
        S, Hh = im.width, im.height
        H.noisy(d, im, PANEL_D, 4, seed=seed)
        rng = np.random.default_rng(seed)
        n = 20
        for k in range(n):
            y = Hh * (k + 0.5) / n
            d.line([S * .06, y - Hh / n / 2, S * .94, y - Hh / n / 2], fill=(60, 64, 68, 255), width=2)
            for fx in (0.22, 0.50, 0.78):
                w = S * (0.07 + 0.05 * rng.random())
                d.rectangle([S * fx - w / 2, y + Hh / n * 0.18, S * fx + w / 2, y + Hh / n * 0.30], fill=(196, 196, 188, 255))
        d.rectangle([S * .03, 0, S * .97, Hh - 1], outline=(10, 10, 10, 255), width=3)
    return fn


def p_weapons(d, im):
    S, Hh = im.width, im.height
    d.rectangle([0, 0, S, Hh], fill=(20, 22, 24, 255))
    labs = ['CAN', 'ROQ', 'MIS', 'BOMB']
    for k, lab in enumerate(labs):
        x = S * (0.14 + k * 0.24)
        H.lamp(d, [x - 18, Hh * .14, x + 18, Hh * .38], (210, 210, 200), None, lit=(k == 0))
        d.rectangle([x - 4, Hh * .48, x + 4, Hh * .70], fill=(190, 190, 190, 255))
        H.text(d, (x, Hh * .86), lab, Hh * .15)


def p_gear(d, im):
    S, Hh = im.width, im.height
    d.rectangle([0, 0, S, Hh], fill=(20, 22, 24, 255))
    for k in range(3):
        x = S * (0.2 + 0.3 * k)
        H.lamp(d, [x - 16, Hh * .12, x + 16, Hh * .52], (60, 150, 70), None, lit=True)
    H.text(d, (S / 2, Hh * .80), 'TRAIN', Hh * .26)


def p_sight(d, im):
    """the CSF 97 sight's aft face: the reticle unit's window, the brightness / range / depression knobs"""
    S, Hh = im.width, im.height
    H.noisy(d, im, DGREY, 4, seed=9)
    d.rectangle([S * .22, Hh * .12, S * .78, Hh * .62], fill=(16, 22, 26, 255), outline=(90, 90, 90, 255), width=4)
    d.ellipse([S * .38, Hh * .2, S * .62, Hh * .54], fill=(34, 48, 58, 255))
    for k, lab in enumerate(['LUM', 'ENVERG', 'CSF 97']):
        x = S * (0.2 + k * 0.3)
        d.ellipse([x - 14, Hh * .74 - 14, x + 14, Hh * .74 + 14], fill=(22, 22, 22, 255), outline=(140, 140, 140, 255),
                  width=3)
        H.text(d, (x, Hh * .93), lab, Hh * .085)


def p_headbox(d, im):
    S, Hh = im.width, im.height
    H.noisy(d, im, HEADPAD, 5, seed=60)
    d.rectangle([S * .1, Hh * .1, S * .9, Hh * .9], outline=(60, 62, 58, 255), width=6)
    H.text(d, (S * .5, Hh * .5), 'MARTIN-BAKER', Hh * .13, fill=(40, 40, 40), bold=True)


def p_back(d, im):
    S, Hh = im.width, im.height
    H.noisy(d, im, CUSHION, 5, seed=61)
    for k in range(1, 4):
        d.line([0, Hh * k / 4, S, Hh * k / 4], fill=(48, 52, 44, 255), width=3)
    H.text(d, (S * .5, Hh * .3), 'RM4', Hh * .16, fill=(30, 30, 30), bold=True)
    for x in (S * .12, S * .88):
        for y in (Hh * .1, Hh * .9):
            d.ellipse([x - 5, y - 5, x + 5, y + 5], fill=(120, 124, 120, 255))


# ---------------------------------------------------------------- build

def build(atlas, E):
    m = H.Mesh(atlas)
    sw = {k: atlas.swatch(c) for k, c in dict(panel=PANEL, panel_d=PANEL_D, black=BLACK, dgrey=DGREY, mgrey=MGREY,
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
        m.flat([c + back for c in corners], sw['panel_d'], normal=-fac[side].N)
        mid0 = np.mean(corners, 0)
        for i in range(4):
            p, q = corners[i], corners[(i + 1) % 4]
            mid = (p + q) / 2
            m.flat([p, q, q + back, p + back], sw['panel_d'], toward=mid + (mid - mid0))

    # -- instruments
    dial_cache = {}
    cols, lamps = LAMPS
    for name, side, a, y, spec, _ in LAYOUT:
        f = fac[side]
        c = at(side, a, y)
        kind = spec[0]
        if kind == 'game':
            gauges.append({'Seat': 0, 'Kind': spec[1], 'Center': H.r5(c + f.N * H.GAUGE_LIFT), 'Normal': H.r5(f.N),
                           'Up': H.r5(f.U), 'Diameter': spec[2], 'Depth': 0.03})
        elif kind == 'paint_disc':                                 # a painted round dial in a thin bezel
            dia, key = spec[1], DIAL_KEY[name]
            rect = dial_cache.get(key) or atlas.paint(96, 96, p_dial_static(key))
            dial_cache[key] = rect
            m.rect_tex(c + f.N * 0.004, f.N, f.U, dia, dia, rect)
            m.ring(c, f.N, f.U, dia / 2, dia / 2 * (1 + H.RIM), 0.010, sw['bezel'])
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
            # the Cyrano scope is a round tube: the game's square raster stands in a round bezel and a round rubber hood
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
        elif kind == 'paint_rect':
            w, h = spec[1], spec[2]
            painter = {'weapons': p_weapons, 'gear': p_gear}[name]
            ph = 96 if h >= w else max(16, int(round(160 * h / w)))
            pw = 160 if w >= h else max(16, int(round(96 * w / h)))
            rect = atlas.paint(pw, ph, painter)
            m.rect_tex(c + f.N * 0.004, f.N, f.U, w, h, rect)
            m.frame_rect(c, f.N, f.U, w, h, 0.004, 0.006, sw['bezel'])
            if name == 'gear':                                     # the gear lever stands out of its panel
                m.box(c + f.N * 0.03 + f.R * 0.0 - f.U * 0.0, (f.R, f.U, f.N), (0.005, 0.005, 0.028), sw['metal'])
                m.box(c + f.N * 0.058, (f.R, f.U, f.N), (0.011, 0.011, 0.009), sw['red'])
        elif kind == 'plate':                                      # a French placard: black plate, white letters
            w, h, label = spec[1], spec[2], spec[3]
            pw = max(24, int(w * 1000)); ph = max(12, int(h * 1000))
            rect = atlas.paint(pw, ph, H.p_label_plate(label))
            m.rect_tex(c + f.N * 0.003, f.N, f.U, w, h, rect)

    # -- coaming: lofted from the panel lip forward to the windscreen's base bar, closed against the nose skin
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
        zrow = lip_edge[0][2] + (E[2] + FRONT_Z - lip_edge[0][2]) * t - E[2]
        xmax = max(CM.x_out(min(max(zrow, CM.Z_WS[0]), CM.Z_WS[1])) - 0.035, 0.15) if t > 0.2 else WING_X + 0.01
        for le in lip_edge:
            s = (le[0] - E[0]) / max(abs(lip_edge[0][0] - E[0]), 1e-6)
            xf = E[0] + s * xmax
            yf = E[1] + FRONT_Y_C + (FRONT_Y_W - FRONT_Y_C) * s * s
            z = le[2] + (E[2] + FRONT_Z - le[2]) * t
            x = le[0] + (xf - le[0]) * t if t > 0 else le[0]
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
            yb_ = E[1] + SILL_Y - 0.15
            m.flat([p, q, [q[0], yb_, q[2]], [p[0], yb_, p[2]]], sw['black'], toward=[E[0], yb_, (p[2] + q[2]) / 2])

    # -- the coaming's two curved side hoods (the real coaming wraps the panel in them): from the panel's outer corners forward
    # along the coaming's edge, a thin shell that rises 9.5 cm, rolls inward over the wing facets and dies away toward the
    # windscreen's base
    HOOD_H, HOOD_W, HOOD_T = 0.095, 0.075, 0.008
    t_rows = np.linspace(0, 1, len(rows))
    stations = np.linspace(0, 0.80, 9)
    thetas = np.linspace(0, math.pi / 2, 7)
    for j in (0, len(lip_edge) - 1):
        edge = np.array([rows[i][j] for i in range(len(rows))])
        sgn = 1.0 if edge[0][0] > E[0] else -1.0                    # +1: the left side (x > 0)
        O, Iq = [], []
        for t in stations:
            p = np.array([np.interp(t, t_rows, edge[:, k]) for k in range(3)])
            h = HOOD_H * (1 - t / 0.80) ** 0.7
            o_ring, i_ring = [], []
            for th in thetas:
                q = p + np.array([-sgn * (0.012 + HOOD_W * (1 - math.cos(th))), 0.002 + h * math.sin(th), 0.0])
                nrm = H.unit([sgn * math.cos(th) / HOOD_W, math.sin(th) / max(h, 0.004), 0.0])
                o_ring.append(q); i_ring.append(q - nrm * HOOD_T)
            O.append(o_ring); Iq.append(i_ring)
        for k in range(len(stations) - 1):
            for i in range(len(thetas) - 1):
                th = (thetas[i] + thetas[i + 1]) / 2
                nrm = H.unit([sgn * math.cos(th) / HOOD_W, math.sin(th) / HOOD_H, 0.0])
                m.flat([O[k][i], O[k][i + 1], O[k + 1][i + 1], O[k + 1][i]], sw['black'], normal=nrm)
                m.flat([Iq[k][i], Iq[k][i + 1], Iq[k + 1][i + 1], Iq[k + 1][i]], sw['black'], normal=-nrm)
            m.flat([O[k][-1], O[k + 1][-1], Iq[k + 1][-1], Iq[k][-1]], sw['black'], normal=[-sgn, 0, 0])
        for i in range(len(thetas) - 1):                            # the rear end, toward the pilot
            m.flat([O[0][i], O[0][i + 1], Iq[0][i + 1], Iq[0][i]], sw['black'], normal=[0, 0, -1])

    # -- CSF 97 reflector sight on the coaming: the body, its aft face, the reflector frame and its glass
    sight = atlas.paint(96, 64, p_sight)
    # (the real one is a big box on top of the coaming at the centre, the most visible thing from the seat: about 19 cm wide
    # and 20 cm long, its reflector frame standing on the box's aft edge, leaning back to the pilot)
    g_h, g_hw = 0.110, 0.072
    g_h = min(g_h, 2 * HUD_TOP[0] - 0.012)
    lean = math.radians(35)
    g_up = np.array([0, math.cos(lean), -math.sin(lean)])
    g_n = H.unit(np.cross(X, g_up))
    base_y = E[1] + TOP_C - 0.020                                  # the box's foot, sunk into the coaming at the lip
    body_top = E[1] - 0.040                                        # its top, 4 cm under the eye line
    body_z0, body_z1 = E[2] + 0.527, E[2] + 0.727
    body_c = np.array([E[0], (base_y + body_top) / 2, (body_z0 + body_z1) / 2])
    m.box(body_c, (X, Y, Z), (0.090, (body_top - base_y) / 2, (body_z1 - body_z0) / 2), sw['dgrey'], front=(2, -1, sight, -X, Y))
    g_bot = np.array([E[0], body_top + 0.012, body_z0 + 0.024])    # the reflector frame's foot on the box's aft edge
    g_c = g_bot + g_up * g_h / 2
    g_top = g_c + g_up * g_h / 2
    m.box(np.array([E[0], body_top + 0.0115, body_z1 - 0.050]), (X, Y, Z), (0.070, 0.0135, 0.050), sw['black'])   # the lens hood at its front
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
        xb = E[0] + WALL_B * side
        xf = E[0] + WALL_F * side
        wall = atlas.paint(120, 60, H.p_wall(PANEL_D, PANEL, 30 + side))
        z0, z1, zs = E[2] + BACK_Z, E[2] + PANEL_Z - 0.08, E[2] + WALL_STEP
        y0, ys0 = E[1] + FLOOR_Y, E[1] + SILL_Y - 0.01
        ua, ub, uc, ud = m.uv(wall, 0, 0), m.uv(wall, 1, 0), m.uv(wall, 1, 1), m.uv(wall, 0, 1)
        if side < 0:
            ua, ub, uc, ud = ub, ua, ud, uc
        for (xa, za, xc, zc) in ((xb, z0, xb, zs), (xf, zs, xf, z1)):        # the lining behind and forward of the step
            qa = [xa, y0, za]; qb = [xc, y0, zc]; qc = [xc, ys0 - 0.03 * (zc - E[2] > 0), zc]; qd = [xa, ys0, za]
            m.face([qa, qb, qc], [ua, ub, uc], normal=[-side, 0, 0])
            m.face([qa, qc, qd], [ua, uc, ud], normal=[-side, 0, 0])
        m.flat([[xb, y0, zs], [xf, y0, zs], [xf, ys0 - 0.03, zs], [xb, ys0 - 0.03, zs]], sw['panel_d'], normal=[0, 0, -1])
        # the console: top at CONSOLE_Y from behind the seat to the step, then the narrow strip to the panel
        xi = E[0] + CONSOLE_IN * side
        cy, fy = E[1] + CONSOLE_Y, E[1] + FLOOR_Y + 0.02
        zb = E[2] + BACK_Z + 0.06
        con = atlas.paint(240, 36, H.rotated(H.p_console(PANEL, 20 + side, [0.11, 0.08, 0.13, 0.07, 0.12, 0.09, 0.10, 0.08] if side > 0
                                               else [0.09, 0.12, 0.08, 0.11, 0.10, 0.09, 0.13, 0.08],
                                               slot=(0.44, 0.30, 0.56, 0.62) if side > 0 else None)))
        top = [[xi, cy, zb], [xb, cy, zb], [xb, cy, zs], [xi, cy, zs]]
        m.face(top, [m.uv(con, 1, 0), m.uv(con, 1, 1), m.uv(con, 0, 1), m.uv(con, 0, 0)], normal=[0, 1, 0])
        m.flat([[xi, cy, zs], [xf, cy, zs], [xf, cy, z1], [xi, cy, z1]], sw['panel'], normal=[0, 1, 0])
        m.flat([[xi, fy, zb], [xi, fy, z1], [xi, cy, z1], [xi, cy, zb]], sw['panel_d'], normal=[-side, 0, 0])
        m.flat([[xi, fy, zb], [xb, fy, zb], [xb, cy, zb], [xi, cy, zb]], sw['panel_d'], normal=[0, 0, -1])
        # the switch-dense side panel: a sloped plate along the outer half of the console top, rows of toggle switches, rotary
        # knobs and a guarded switch or two (the left one stops behind the throttle lever's sweep)
        pa, pb = (E[2] - 0.640, E[2] - 0.270) if side > 0 else (E[2] - 0.640, E[2] + 0.150)
        xp_i, xp_o = E[0] + (WALL_B - 0.090) * side, E[0] + (WALL_B - 0.004) * side
        yp_i, yp_o = cy + 0.004, cy + 0.070
        plate = [np.array(q, float) for q in ([xp_i, yp_i, pa], [xp_o, yp_o, pa], [xp_o, yp_o, pb], [xp_i, yp_i, pb])]
        across = H.unit(plate[1] - plate[0]); along = Z
        pn = H.unit(np.cross(along, across))
        if pn[1] < 0:
            pn = -pn
        sp = atlas.paint(96, 240, p_switch_panel(40 + side))
        m.face(plate, [m.uv(sp, 0, 0), m.uv(sp, 1, 0), m.uv(sp, 1, 1), m.uv(sp, 0, 1)], normal=pn)
        for zc_, nn in ((pa, -1.0), (pb, 1.0)):                      # the plate's two end caps and its outer wall
            m.flat([[xp_i, cy, zc_], [xp_o, cy, zc_], [xp_o, yp_o, zc_]] if nn > 0 else [[xp_i, cy, zc_], [xp_o, yp_o, zc_], [xp_o, cy, zc_]],
                   sw['panel_d'], normal=[0, 0, nn])
        m.flat([[xp_o, cy, pa], [xp_o, yp_o, pa], [xp_o, yp_o, pb], [xp_o, cy, pb]], sw['panel_d'], normal=[side, 0, 0])
        rows = np.arange(pa + 0.030, pb - 0.012, 0.029)
        for ri, zr_ in enumerate(rows):
            for ci, fr in enumerate((0.22, 0.50, 0.78)):
                base = plate[0] + (plate[1] - plate[0]) * fr
                base = np.array([base[0], base[1], zr_])
                key = (ri * 3 + ci + (5 if side > 0 else 0)) % 11
                if key == 4:                                          # a guarded switch: a red cover
                    m.box(base + pn * 0.0030, (along, across, pn), (0.0060, 0.0060, 0.0050), sw['red'])
                elif key == 8:                                        # a rotary knob
                    m.box(base + pn * 0.0040, (along, across, pn), (0.0095, 0.0095, 0.0060), sw['black'])
                    m.box(base + pn * 0.0105, (along, across, pn), (0.0014, 0.0075, 0.0020), sw['metal'])
                else:                                                 # a toggle, leaning a little forward or back
                    tilt = 0.004 * (1 if (ri + ci) % 2 else -1)
                    m.box(base + pn * 0.0065 + along * tilt * 0.5, (along, across, pn), (0.0028, 0.0028, 0.0085), sw['metal'])
        # the lower sub-panel on the console's inner wall, facing the pilot's knee: a black plate, three round dials, a row of
        # toggles under them
        xq = E[0] + (CONSOLE_IN - 0.0075) * side
        za, zb2 = E[2] + 0.20, E[2] + 0.40
        m.box([E[0] + (CONSOLE_IN - 0.003) * side, E[1] - 0.8075, 0.5 * (za + zb2)], (X, Y, Z), (0.0045, 0.1675, 0.5 * (zb2 - za)), sw['black'])
        for dk, dy in zip(('HYD', 'VOLT', 'CARB') if side > 0 else ('OXY', 'CABINE', 'FREIN'), (-0.700, -0.770, -0.840)):
            rect = dial_cache.get(dk) or atlas.paint(96, 96, p_dial_static(dk))
            dial_cache[dk] = rect
            cd = np.array([xq, E[1] + dy, E[2] + 0.30])
            m.rect_tex(cd + np.array([-side * 0.003, 0, 0]), [-side, 0, 0], Y, 0.052, 0.052, rect)
            m.ring(cd, [-side, 0, 0], Y, 0.026, 0.026 * (1 + H.RIM), 0.008, sw['bezel'])
        for k in range(5):
            tz = E[2] + 0.23 + 0.037 * k
            m.box([xq - side * 0.007, E[1] - 0.925, tz], (Z, Y, [-side, 0, 0]), (0.0028, 0.0028, 0.007), sw['metal'])
            m.box([xq - side * 0.0145, E[1] - 0.925, tz], (Z, Y, [-side, 0, 0]), (0.0040, 0.0040, 0.0025), sw['mgrey'])
    # the rear bulkhead: the spine fairing's front closed from the floor up over the top (inside the rear bow)
    sec = CM.section(CM.Z_RB[0] + 0.01)
    out_r = [(WALL_B, FLOOR_Y + 0.01), (WALL_B, SILL_Y - 0.01)] + [(max(x - 0.012, 0.0), y - 0.006) for x, y in sec[3:-1]] + [(0.0, sec[-1][1] - 0.01)]
    ring = [P(x, y, BACK_Z + 0.01) for x, y in out_r] + [P(-x, y, BACK_Z + 0.01) for x, y in out_r[::-1] if x > 1e-6]
    wallp = atlas.paint(120, 90, H.p_wall(PANEL_D, PANEL, 77))
    cb = np.mean(ring, 0)
    for i in range(len(ring)):
        a_, b_ = ring[i], ring[(i + 1) % len(ring)]
        m.face([cb, a_, b_], [m.uv(wallp, 0.5, 0.5), m.uv(wallp, 0.1 + 0.8 * i / len(ring), 0.2),
                              m.uv(wallp, 0.1 + 0.8 * (i + 1) / len(ring), 0.2)], normal=[0, 0, 1])
    # under the centre facet: its lower closing face back to the footwell, and the footwell's front down to the floor
    bl, br = centre.at(HINGE, E[1] + BOT_C), centre.at(-HINGE, E[1] + BOT_C)
    m.flat([bl, br, br + np.array([0, 0, 0.12]), bl + np.array([0, 0, 0.12])], sw['panel_d'], normal=[0, -1, 0])
    # the floor: a dark plate over the model's own floor, from the bulkhead to the pedals
    m.flat([P(-WALL_B, FLOOR_Y + 0.01, BACK_Z), P(WALL_B, FLOOR_Y + 0.01, BACK_Z),
            P(WALL_B, FLOOR_Y + 0.01, WALL_STEP), P(-WALL_B, FLOOR_Y + 0.01, WALL_STEP)], sw['floor'], normal=[0, 1, 0])
    m.flat([P(-WALL_F, FLOOR_Y + 0.01, WALL_STEP), P(WALL_F, FLOOR_Y + 0.01, WALL_STEP),
            P(WALL_F, FLOOR_Y + 0.01, PANEL_Z + 0.30), P(-WALL_F, FLOOR_Y + 0.01, PANEL_Z + 0.30)], sw['floor'], normal=[0, 1, 0])
    # rudder pedals under the panel
    for s in (1, -1):
        m.box(P(s * 0.12, -0.935, 0.43), (X, H.unit([0, 1, -0.35]), H.unit([0, 0.35, 1])), (0.045, 0.07, 0.01), sw['mgrey'])
        m.box(P(s * 0.12, -1.000, 0.47), (X, Y, Z), (0.0105, 0.03, 0.06), sw['dgrey'])

    # -- the Atar's single throttle lever on the left console
    # The Atar's throttle lever is its own bone (throttle_lever), hinged under the console top, at idle.
    tz, tx = -0.08, 0.28
    THR.clear()
    THR.extend(throttle_lib.jet_levers(m, lambda: H.Mesh(atlas), sw, 'ball', x=E[0] + tx, top_y=E[1] + CONSOLE_Y,
                                       z_idle=E[2] + tz - 0.06, hx=0.040, friction=False))
    # -- Martin-Baker RM4 ejection seat: bucket and pan, a parachute back, headbox, the face-blind handle above, the lower
    # handle between the knees; at the model's old seat group (headrest front face z -0.265, headbox top y +0.10)
    cushion = atlas.paint(64, 64, H.p_cushion(CUSHION))
    back = atlas.paint(48, 64, p_back)
    headbox = atlas.paint(64, 64, p_headbox)
    stripes = atlas.paint(64, 16, H.p_stripes)
    ax_b = (X, H.unit([0, 1, -0.10]), H.unit([0, 0.10, 1]))
    m.box(P(0, -1.040, -0.10), (X, Y, Z), (0.18, 0.025, 0.28), sw['frame'])                      # the seat base (floor -1.08)
    m.box(P(0, -0.925, -0.12), (X, Y, Z), (0.17, 0.030, 0.27), sw['cushion'], front=(1, 1, cushion, -X, Z))   # the pan
    for s in (1, -1):
        m.box(P(s * 0.195, -0.88, -0.14), (X, Y, Z), (0.012, 0.12, 0.25), sw['frame'])           # bucket sides
        m.box(P(s * 0.15, -0.46, -0.415), ax_b, (0.018, 0.55, 0.020), sw['frame'])               # guide rails up to the headbox
        m.box(P(s * 0.215, -0.82, 0.0), (X, Y, Z), (0.012, 0.012, 0.15), sw['dgrey'])            # arm rest
    m.box(P(0, -0.58, -0.375), ax_b, (0.19, 0.30, 0.020), sw['cushion'], front=(2, 1, back, X, ax_b[1]))   # the parachute back
    m.box(P(0, -0.095, -0.335), (X, Y, Z), (0.15, 0.20, 0.070), sw['frame'])                    # headbox (top y +0.105)
    m.box(P(0, -0.07, -0.2655), (X, Y, Z), (0.11, 0.10, 0.0045), sw['cushion'], front=(2, 1, headbox, X, Y))
    # the face-blind: a housing on the headbox top with the yellow-and-black striped handle at its front
    m.box(P(0, 0.082, -0.335), (X, Y, Z), (0.080, 0.022, 0.050), sw['frame'])
    for s in (1, -1):
        m.box(P(s * 0.062, 0.085, -0.262), (X, Y, Z), (0.006, 0.012, 0.030), sw['metal'])
    m.box(P(0, 0.093, -0.235), (X, Y, Z), (0.076, 0.009, 0.009), sw['yellow'], front=(2, 1, stripes, X, Y))
    # the lower handle: a striped D ring on two posts at the front centre of the pan
    for s in (1, -1):
        m.box(P(s * 0.035, -0.855, 0.15), (X, Y, Z), (0.006, 0.05, 0.006), sw['metal'])
    m.box(P(0, -0.795, 0.15), (X, Y, Z), (0.045, 0.011, 0.009), sw['yellow'], front=(2, 1, stripes, X, Y))

    # -- control stick: the boot stays with the cockpit, the rest goes into the stick bone
    sz = 0.19
    m.box(P(0, -0.945, sz), (X, Y, Z), (0.040, 0.04, 0.040), sw['dgrey'])
    stick = H.Mesh(atlas)
    col_ax = (X, H.unit([0, 1, -0.07]), H.unit([0, 0.07, 1]))
    stick.box(P(0, -0.76, sz - 0.002), col_ax, (0.011, 0.19, 0.011), sw['metal'])
    g_ax = (X, H.unit([0, 1, 0.21]), H.unit([0, -0.21, 1]))
    gc = P(0, -0.56, sz - 0.006)
    stick.box(gc, g_ax, (0.021, 0.062, 0.027), sw['black'])
    stick.box(gc + g_ax[1] * 0.066, g_ax, (0.017, 0.006, 0.022), sw['dgrey'])
    stick.box(gc + g_ax[1] * 0.07 + g_ax[2] * 0.010, g_ax, (0.006, 0.004, 0.006), sw['red'])
    stick.box(gc + g_ax[2] * 0.031 + g_ax[1] * 0.015, g_ax, (0.008, 0.012, 0.005), sw['metal'])
    foot = P(0, -0.953, sz)
    return m, (stick, foot), gauges, displays, hud


# ---------------------------------------------------------------- the airframe

def install(ac, mesh, stick, gauges, displays, hud, eye, seat=0):
    """as handbuilt.install, but the canopy's CanopyGlass stays (the sight's glass is added to it)"""
    pm = mesh.poly_mesh()
    piece = SOURCE_BONE.split('__', 1)[0]
    ac.bones[:] = [b for b in ac.bones if not b['name'].endswith('__cockpit')]
    hull = ac.bone('hull')
    at = next(i for i, b in enumerate(ac.bones) if b['name'] == SOURCE_BONE) + 1
    ac.bones.insert(at, {'name': piece + '__cockpit', 'parent': 'hull', 'pivot': list(hull['pivot']), 'poly_mesh': pm})
    thr = throttle_lib.add_bones(ac.bones, THR, piece + '__cockpit')
    print(f'{piece} throttle bones {[(n, p) for n, p, _ in thr]}')
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
            keep = ~((np.abs(c[:, 0] - eye[0]) < 0.08) & (np.abs(c[:, 1] - eye[1]) < 0.07) & (c[:, 2] > eye[2] + 0.38) &
                     (c[:, 2] < eye[2] + 0.52))
            cg['Triangles'] = [round(float(v), 4) + 0.0 for v in t[keep].reshape(-1)]
        L.add_glass(a, hud['glass'])
    return pm


def main(argv):
    dry = '--dry-run' in argv
    ac = L.Aircraft(VID)
    E = ac.eye(0)
    atlas = H.A.Atlas(VID)
    mesh, stick, gauges, displays, hud = build(atlas, E)
    pm = install(ac, mesh, stick, gauges, displays, hud, E)
    print(f'{VID}: new cockpit {len(pm["polys"])} faces, {len(gauges)} gauges, {len(displays)} display')
    if not dry:
        H.write(ac, atlas)


if __name__ == '__main__':
    main(sys.argv[1:])
